import AVFoundation
import Observation
import SwiftUI

/// 음성 편집기 상태와 동작.
@MainActor
@Observable
final class AudioEditorModel {
    enum Phase: Equatable {
        case awaitingSource, recording, loading, failed(EditorError), ready
    }

    enum RecordTarget: Hashable { case insert, overlay }

    /// 끌기 대상.
    enum DragTarget: Equatable {
        case clipStart(String), clipEnd(String), move(String), rangeStart, rangeEnd
    }

    static let minPiece: Micros = 200_000
    static let defaultRange: Micros = 3_000_000
    static let skip: Micros = 5_000_000
    static let maxVolume = 2.0

    let request: AudioEditorRequest
    let tools: [AudioTool]
    let recorder: VoiceRecorder
    private let onFinish: (FrameKitResult) -> Void
    private var delivered = false
    private var started = false

    private(set) var phase: Phase
    private(set) var sources: [AudioSource] = []
    private(set) var waveforms: [Int: Waveform] = [:]
    private(set) var history = AudioHistory(AudioProjectState())
    private(set) var draft: AudioProjectState?
    private(set) var position: Micros = 0
    private(set) var isPlaying = false
    var selectedClipId: String?
    private(set) var range: TimeRangeUs?
    var recordTarget: RecordTarget?
    private(set) var export: ExportUI?
    var showDiscardDialog = false
    var showFilePicker = false
    private(set) var notice: String?
    private(set) var busy = false

    private let player = AVPlayer()
    private var timeObserver: Any?
    private var rebuildTask: Task<Void, Never>?
    private var exportTask: Task<Void, Never>?
    private var noticeTask: Task<Void, Never>?
    private var dragStart: (project: AudioProjectState, range: TimeRangeUs?)?

    init(request: AudioEditorRequest, onFinish: @escaping (FrameKitResult) -> Void) {
        self.request = request
        self.onFinish = onFinish
        var seen = Set<AudioTool>()
        tools = request.config.enabledTools.filter { seen.insert($0).inserted && ($0 != .record || VoiceRecorder.micDeclared) }
        recorder = VoiceRecorder(maxDuration: request.config.maxRecordingDuration)
        switch request.input {
        case .pick, .pickMultiple: phase = .awaitingSource
        case .capture: phase = .recording
        default: phase = .loading
        }
        player.actionAtItemEnd = .pause
    }

    // MARK: - 표시 값

    var project: AudioProjectState { draft ?? history.current }
    var duration: Micros { project.duration }
    var selectedClip: AudioClipState? { project.clips.first { $0.id == selectedClipId } }
    var isDirty: Bool { history.isDirty }
    var canUndo: Bool { request.config.allowUndo && history.canUndo && draft == nil && export == nil }
    var canRedo: Bool { request.config.allowRedo && history.canRedo && draft == nil && export == nil }

    var pickLimit: Int {
        if case .pickMultiple(let max) = request.input { return min(max, request.config.maxSourceCount) }
        return 1
    }

    // MARK: - 열기

    func start() {
        // 화면이 다시 나타나도 원본을 다시 열지 않는다.
        guard !started else { return }
        started = true
        OutputStore.removeStalePartials()
        let issues = request.validate()
        guard issues.isEmpty else {
            frameKitLog.error("invalid audio configuration: \(issues.map(\.description).joined(separator: ", "), privacy: .public)")
            finish(.failure(EditorError(code: .invalidConfiguration)))
            return
        }
        observePlayer()
        recorder.onLimit = { [weak self] in self?.recordingLimitReached() }
        switch request.input {
        case .capture:
            guard VoiceRecorder.micDeclared else {
                frameKitLog.error("NSMicrophoneUsageDescription is missing in the host Info.plist")
                finish(.failure(EditorError(code: .invalidConfiguration)))
                return
            }
        case .file(let url): add([url], at: 0, target: .insert, copy: true, initial: true)
        case .files(let urls): add(Array(urls.prefix(request.config.maxSourceCount)), at: 0, target: .insert, copy: true, initial: true)
        default: break
        }
    }

    /// 처음 고른 파일. 비어 있으면 닫은 것이다.
    func picked(_ urls: [URL]) {
        guard !urls.isEmpty else { finish(.cancelled); return }
        add(Array(urls.prefix(request.config.maxSourceCount)), at: 0, target: .insert, copy: false, initial: true)
    }

    /// 편집 중 고른 파일을 재생 위치에 끼워 넣는다.
    func importFiles(_ urls: [URL]) {
        showFilePicker = false
        let room = request.config.maxSourceCount - sources.count
        guard room > 0 else { showNotice("audio_add_failed"); return }
        add(Array(urls.prefix(room)), at: position, target: .insert, copy: false, initial: false)
    }

    private func add(_ urls: [URL], at: Micros, target: RecordTarget, copy: Bool, initial: Bool) {
        guard !urls.isEmpty else { return }
        if initial { phase = .loading } else { busy = true }
        Task {
            var opened: [AudioSource] = []
            var failure: EditorError?
            for url in urls {
                do {
                    let local = copy ? try VideoEditorModel.copyToTemporary(url) : url
                    let source = try await AudioSource.open(url: local, name: url.deletingPathExtension().lastPathComponent)
                    guard source.duration > 0 else { source.close(); throw EditorError.logged(.unsupportedFormat, "empty audio") }
                    opened.append(source)
                } catch {
                    let editorError = (error as? EditorError) ?? .logged(.unsupportedFormat, "audio open: \(error)")
                    failure = editorError.code == .decodeFailed || editorError.code == .invalidSource
                        ? EditorError.logged(.unsupportedFormat, "audio source") : editorError
                }
            }
            busy = false
            if initial && opened.isEmpty {
                phase = .failed(failure ?? .logged(.unsupportedFormat, "no audio"))
                return
            }
            if failure != nil { showNotice("audio_add_failed") }
            place(opened, at: at, target: target)
            if initial {
                history = AudioHistory(project)
                draft = nil
                position = 0
                phase = .ready
            }
        }
    }

    private func place(_ opened: [AudioSource], at: Micros, target: RecordTarget) {
        guard !opened.isEmpty else { return }
        var next = history.current
        var cursor = at
        for source in opened {
            let index = sources.count
            sources.append(source)
            loadWaveform(index)
            let clip = AudioClipState(id: Self.newId(), source: index, sourceRange: TimeRangeUs(start: 0, end: source.duration), timelineStart: cursor,
                                      role: target == .insert ? .voice : .music)
            if target == .insert {
                next = AudioProjectEditing.insert(next, clip, at: cursor, newId: Self.newId)
                cursor += source.duration
            } else {
                next = AudioProjectEditing.overlay(next, clip, at: cursor)
            }
        }
        commit(next)
        if target == .insert { seek(to: min(cursor, max(0, duration - 1))) }
    }

    private func loadWaveform(_ index: Int) {
        let source = sources[index]
        Task { waveforms[index] = await Waveform.extract(source) }
    }

    nonisolated static func newId() -> String { "clip-\(UUID().uuidString.prefix(8))" }

    // MARK: - 녹음

    /// 녹음을 마쳤다. `nil`이면 너무 짧았다.
    func recorded(_ url: URL?) {
        let target = recordTarget ?? .insert
        recordTarget = nil
        guard let url else {
            showNotice("audio_too_short")
            return
        }
        if phase == .recording {
            if !request.config.editAfterRecording {
                phase = .loading
                Task {
                    do {
                        let media = try await AudioExporter.publishRecording(url)
                        finish(.success(output: media, outputs: [media]))
                    } catch {
                        phase = .failed((error as? EditorError) ?? .logged(.outputWriteFailed, "recording: \(error)"))
                    }
                }
                return
            }
            add([url], at: 0, target: .insert, copy: false, initial: true)
        } else {
            add([url], at: position, target: target, copy: false, initial: false)
        }
    }

    private func recordingLimitReached() {
        guard recorder.state != .idle else { return }
        recorded(recorder.finish())
    }

    /// 녹음 중이면 버리고 시트를 닫는다. 여러 번 불러도 된다.
    func closeRecordSheet() {
        guard recordTarget != nil else { return }
        if recorder.state != .idle { recorder.discard() }
        recordTarget = nil
    }

    func openRecordSheet(_ target: RecordTarget) {
        guard tools.contains(.record), export == nil else { return }
        pause()
        clearSelection()
        recordTarget = target
    }

    // MARK: - 재생

    private func observePlayer() {
        guard timeObserver == nil else { return }
        timeObserver = player.addPeriodicTimeObserver(forInterval: CMTime(value: 1, timescale: 30), queue: .main) { [weak self] time in
            MainActor.assumeIsolated {
                guard let self, self.isPlaying else { return }
                self.position = min(self.duration, time.micros)
                if self.player.timeControlStatus == .paused && self.position >= self.duration - 30_000 {
                    self.isPlaying = false
                    self.position = self.duration
                }
            }
        }
    }

    func togglePlay() { isPlaying ? pause() : play() }

    func play() {
        guard phase == .ready, duration > 0, export == nil else { return }
        if position >= duration - 30_000 { position = 0 }
        rebuildPreview(immediately: true, thenPlay: true)
    }

    func pause() {
        player.pause()
        isPlaying = false
    }

    func seek(to t: Micros) {
        let clamped = min(max(0, t), duration)
        position = clamped
        player.seek(to: clamped.cmTime, toleranceBefore: .zero, toleranceAfter: .zero)
    }

    func skip(forward: Bool) {
        let playing = isPlaying
        pause()
        seek(to: position + (forward ? Self.skip : -Self.skip))
        if playing { play() }
    }

    /// 편집이 바뀌면 재생 합성을 다시 만든다.
    private func rebuildPreview(immediately: Bool = false, thenPlay: Bool = false) {
        rebuildTask?.cancel()
        let project = project, sources = sources
        rebuildTask = Task {
            if !immediately { try? await Task.sleep(for: .milliseconds(120)) }
            guard !Task.isCancelled else { return }
            let (composition, mix) = AudioComposer.build(project, sources: sources)
            let item = AVPlayerItem(asset: composition)
            item.audioMix = mix
            item.forwardPlaybackEndTime = project.duration.cmTime
            player.replaceCurrentItem(with: item)
            await player.seek(to: position.cmTime, toleranceBefore: .zero, toleranceAfter: .zero)
            if thenPlay || isPlaying {
                isPlaying = true
                player.play()
            }
        }
    }

    // MARK: - 편집

    private func commit(_ next: AudioProjectState) {
        history.commit(next)
        draft = nil
        afterChange()
    }

    private func afterChange() {
        if let id = selectedClipId, !project.clips.contains(where: { $0.id == id }) { selectedClipId = nil }
        position = min(position, duration)
        rebuildPreview()
    }

    func undo() {
        guard canUndo else { return }
        history.undo()
        clearSelection()
        afterChange()
    }

    func redo() {
        guard canRedo else { return }
        history.redo()
        clearSelection()
        afterChange()
    }

    func clearSelection() {
        selectedClipId = nil
        range = nil
    }

    func selectClip(_ id: String?) {
        range = nil
        selectedClipId = selectedClipId == id ? nil : id
    }

    /// 재생 위치의 조각(본 줄 먼저).
    private func clipAtPlayhead() -> AudioClipState? {
        let total = duration
        let hits = project.clips.filter { $0.outputRange(videoDuration: total).contains(position) }
        return hits.first { $0.role == .voice } ?? hits.first
    }

    func selectClipAtPlayhead() {
        guard let clip = clipAtPlayhead() else { showNotice("audio_split_unavailable"); return }
        range = nil
        selectedClipId = clip.id
    }

    func splitAtPlayhead() {
        guard let clip = selectedClip ?? clipAtPlayhead() else { showNotice("audio_split_unavailable"); return }
        let next = AudioProjectEditing.split(project, id: clip.id, at: position, newId: Self.newId(), min: Self.minPiece)
        guard next != project else { showNotice("audio_split_unavailable"); return }
        commit(next)
    }

    func deleteSelectedClip() {
        guard let id = selectedClipId else { return }
        commit(AudioProjectEditing.deleteClip(project, id: id))
        selectedClipId = nil
    }

    func toggleLoop() {
        changeSelected { clip in
            clip.loop.toggle()
            if clip.loop { clip.timelineEnd = nil }
        }
    }

    /// 볼륨·페이드. [live]면 손을 뗄 때 `finishGesture`로 한 번에 기록한다.
    func changeSelected(live: Bool = false, _ body: (inout AudioClipState) -> Void) {
        guard let id = selectedClipId, let index = project.clips.firstIndex(where: { $0.id == id }) else { return }
        var next = project
        body(&next.clips[index])
        let length = next.range(of: next.clips[index]).duration
        next.clips[index].volume = min(max(0, next.clips[index].volume), Self.maxVolume)
        next.clips[index].fadeIn = min(max(0, next.clips[index].fadeIn), length / 2)
        next.clips[index].fadeOut = min(max(0, next.clips[index].fadeOut), length / 2)
        if live {
            draft = next
            rebuildPreview()
        } else {
            commit(next)
        }
    }

    func finishGesture() {
        guard let draft else { return }
        commit(draft)
    }

    // MARK: - 구간

    func beginRange() {
        guard duration > 0 else { return }
        pause()
        selectedClipId = nil
        let length = min(Self.defaultRange, duration)
        let start = min(position, duration - length)
        range = TimeRangeUs(start: start, end: start + length)
    }

    func cancelRange() { range = nil }

    func keepRange() {
        guard let range else { return }
        commit(AudioProjectEditing.keepRange(project, range, newId: Self.newId))
        self.range = nil
        seek(to: 0)
    }

    func deleteRange() {
        guard let range else { return }
        commit(AudioProjectEditing.deleteRange(project, range, newId: Self.newId))
        self.range = nil
        seek(to: range.start)
    }

    // MARK: - 타임라인 끌기

    func beginDrag(_ target: DragTarget) {
        pause()
        dragStart = (project, range)
    }

    /// 끌기 시작부터 [delta]만큼(결과 시간) 옮긴다.
    func drag(_ target: DragTarget, by delta: Micros) {
        guard let start = dragStart else { return }
        let base = start.project
        let total = base.duration
        switch target {
        case .rangeStart, .rangeEnd:
            guard var r = start.range else { return }
            if target == .rangeStart { r.start = min(max(0, r.start + delta), r.end - Self.minPiece) }
            else { r.end = max(min(total, r.end + delta), r.start + Self.minPiece) }
            range = r
            seek(to: target == .rangeStart ? r.start : r.end)
        case .clipStart(let id), .clipEnd(let id), .move(let id):
            guard let index = base.clips.firstIndex(where: { $0.id == id }) else { return }
            let clip = base.clips[index]
            var next = base
            // 본 줄 끝을 늘리면 결과도 길어질 수 있어 원본 길이를 한도로 쓴다.
            let limit = clip.role == .voice ? AudioProjectState.unbounded : total
            switch target {
            case .clipStart:
                let r = clip.outputRange(videoDuration: total)
                next.clips[index] = AudioClipEditing.trimStart(clip, to: r.start + delta, total: total, min: Self.minPiece)
            case .clipEnd:
                let r = clip.outputRange(videoDuration: total)
                next.clips[index] = AudioClipEditing.trimEnd(clip, to: r.end + delta, total: limit, min: Self.minPiece)
            default:
                guard clip.role != .voice else { return }
                next.clips[index] = AudioClipEditing.move(clip, to: clip.timelineStart + delta, total: total)
            }
            if clip.role == .voice { next = AudioProjectEditing.closeGaps(next) }
            draft = next
        }
    }

    func endDrag() {
        dragStart = nil
        guard let draft else { return }
        if draft == history.current { self.draft = nil } else { commit(draft) }
    }

    func scrub(to t: Micros) {
        pause()
        seek(to: t)
    }

    // MARK: - 저장

    func save() {
        guard phase == .ready, export == nil, duration > 0 else { return }
        finishGesture()
        pause()
        let project = history.current, sources = sources, bitrate = request.export.bitrate
        export = .running(nil, 0)
        IdleTimerHold.acquire()
        exportTask = Task {
            defer { IdleTimerHold.release() }
            do {
                let media = try await Task.detached(priority: .userInitiated) {
                    try await AudioExporter.export(project, sources: sources, bitrate: bitrate) { fraction in
                        Task { @MainActor in if case .running = self.export { self.export = .running(nil, fraction) } }
                    }
                }.value
                finish(.success(output: media, outputs: [media]))
            } catch is CancellationError {
                export = nil
            } catch {
                export = .failed((error as? EditorError) ?? .logged(.unknown, "audio export: \(error)"))
            }
        }
    }

    func cancelExport() {
        guard case .running = export else { return }
        export = .cancelling
        exportTask?.cancel()
    }

    func dismissExportError() { export = nil }

    func retryExport() {
        export = nil
        save()
    }

    // MARK: - 닫기

    /// 뒤로: 녹음 시트 → 구간 → 선택 → 닫기 순.
    func onBack() {
        if recordTarget != nil { closeRecordSheet() }
        else if range != nil { range = nil }
        else if selectedClipId != nil { selectedClipId = nil }
        else { requestClose() }
    }

    func requestClose() {
        if export != nil { cancelExport() } else if isDirty { showDiscardDialog = true } else { finish(.cancelled) }
    }

    func discardAndClose() {
        showDiscardDialog = false
        finish(.cancelled)
    }

    func showNotice(_ key: String) {
        notice = key
        noticeTask?.cancel()
        noticeTask = Task {
            try? await Task.sleep(for: .seconds(3))
            if !Task.isCancelled { notice = nil }
        }
    }

    func finish(_ result: FrameKitResult) {
        guard !delivered else { return }
        delivered = true
        rebuildTask?.cancel()
        exportTask?.cancel()
        if recorder.state != .idle { recorder.discard() }
        player.pause()
        player.replaceCurrentItem(with: nil)
        if let timeObserver { player.removeTimeObserver(timeObserver) }
        sources.forEach { $0.close() }
        onFinish(result)
    }

    func dismissedWithoutResult() { finish(.cancelled) }
}
