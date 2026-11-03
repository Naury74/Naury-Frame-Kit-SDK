import AVFoundation
import Observation
import UIKit
import UniformTypeIdentifiers
internal import FrameKitCore

/// 영상 실행 취소 기록. 기준 상태와 비교해 바뀌었는지 판단한다.
struct VideoHistory {
    static let capacity = 50
    private(set) var current: VideoEdit
    private(set) var baseline: VideoEdit
    private var past: [VideoEdit] = []
    private var future: [VideoEdit] = []

    init(_ initial: VideoEdit) {
        current = initial
        baseline = initial
    }

    /// 세션에서 복원한다. 실행 취소 기록은 비어 있다.
    init(current: VideoEdit, baseline: VideoEdit) {
        self.current = current
        self.baseline = baseline
    }

    var canUndo: Bool { !past.isEmpty }
    var canRedo: Bool { !future.isEmpty }
    var isDirty: Bool { !current.sameContent(as: baseline) }

    mutating func commit(_ next: VideoEdit) {
        guard !next.sameContent(as: current) else { return }
        past.append(current)
        if past.count > Self.capacity { past.removeFirst(past.count - Self.capacity) }
        current = next
        future.removeAll()
    }

    mutating func undo() {
        guard let previous = past.popLast() else { return }
        future.append(current)
        current = previous
    }

    mutating func redo() {
        guard let next = future.popLast() else { return }
        past.append(current)
        current = next
    }
}

/// 영상 편집기 상태와 동작.
@MainActor
@Observable
final class VideoEditorModel: ColorToolHost {
    enum Phase: Equatable {
        case awaitingSource, loading, failed(EditorError), ready
    }

    /// 이번 판에서 화면이 있는 도구.
    static let implemented = Set(VideoTool.allCases)
    static let defaultItemUs: Micros = 3_000_000
    static let previewShortSide = 720
    static let frameUs: Micros = 33_333

    let request: VideoEditorRequest
    let tools: [VideoTool]
    private let onFinish: (FrameKitResult) -> Void
    private var delivered = false
    /// 결과를 넘겼다.
    var isFinished: Bool { delivered }
    /// 혼합 편집 화면이 결과를 받는다.
    var onExternalFinish: ((FrameKitResult) -> Void)?
    /// 마지막으로 적용한 편집을 남기는 곳.
    let session: EditSession?
    /// 세션을 이어 열지 못했을 때 부른다. 없으면 요청 입력으로 처음부터 연다.
    var onRestoreFailed: (() -> Void)?
    private var started = false

    private(set) var phase: Phase { didSet { persistSession() } }
    private(set) var sources: [VideoSource] = []
    var audioSources: [AudioSource] = []
    /// 소리 원본 위치별 파형.
    var audioWaves: [Int: Waveform] = [:]
    var history = VideoHistory(VideoEdit(clips: [])) { didSet { persistSession() } }
    var working: VideoEdit?
    private(set) var activeTool: VideoTool?
    private var toolEntry: VideoEdit?
    var selectedClipId: String?
    private(set) var position: Micros = 0
    private(set) var isPlaying = false
    let player = AVPlayer()
    private(set) var previewFailed = false
    private(set) var renderSize = CGSize(width: 16, height: 9)
    private(set) var export: ExportUI?
    var showDiscardDialog = false
    var showDraftDialog = false
    private(set) var notice: String?
    var busy = false
    private(set) var thumbnailer: VideoThumbnailer?
    /// 자르기·회전 도구에서 보여 줄 클립 프레임(똑바로 선 원본).
    private(set) var cropFrame: CGImage?
    private(set) var cropAspect: CropAspect = .free
    var adjustKind: AdjustKind = .brightness
    private(set) var filterThumbnails: [String: CGImage] = [:]
    var showAddPicker = false
    // 요소·가리기·소리 선택
    var selectedOverlayId: String?
    var selectedMaskId: String?
    var selectedAudioId: String?
    var originalSelected = false
    /// 소리를 끌 구간(결과 시간). 있으면 구간 음소거 막대가 보인다.
    var muteSelection: TimeRangeUs?
    var liveStroke: [NPoint]?
    var privacy = PrivacySettings(mode: .rectangle)
    var stickerCategory = 0
    /// 화면 위에 그린 지금 시각의 요소·워터마크(미리 보기 크기).
    var overlayImage: CGImage?
    var overlayKey = ""
    var showMusicPicker = false
    var musicPickerRole = AudioRole.music
    var showRecorder = false
    private var lastWatermark: WatermarkSpec?

    private var rebuildTask: Task<Void, Never>?
    private var exportTask: Task<Void, Never>?
    private var noticeTask: Task<Void, Never>?
    private var timeObserver: Any?
    private var straightenStart: GeometryState?
    private var cropDragStart: (rect: NRect, handle: CropHandle)?
    private(set) var draggingCrop = false
    private var trimStart: VideoClipState?

    init(request: VideoEditorRequest, session: EditSession? = nil, onFinish: @escaping (FrameKitResult) -> Void) {
        self.request = request
        self.session = session
        self.onFinish = onFinish
        var seen = Set<VideoTool>()
        let locked = request.config.watermark?.locked == true
        tools = request.config.enabledTools.filter { seen.insert($0).inserted && !($0 == .watermark && locked) && Self.implemented.contains($0) }
        switch request.input {
        case .pick, .pickMultiple, .capture: phase = session?.storedKind == nil ? .awaitingSource : .loading
        default: phase = .loading
        }
        player.actionAtItemEnd = .pause
    }

    // MARK: - 표시 값

    var displayed: VideoEdit { working ?? history.current }
    var duration: Micros { displayed.duration }
    var minClip: Micros { Micros(request.config.minClipDuration * 1_000_000) }
    var maxTimeline: Micros { Micros(request.config.maxTimelineDuration * 1_000_000) }
    var selectedIndex: Int? { displayed.clips.firstIndex { $0.id == selectedClipId } ?? (displayed.clips.isEmpty ? nil : 0) }
    var selectedClip: VideoClipState? { selectedIndex.map { displayed.clips[$0] } }
    var selectedSource: VideoSource? { selectedClip.flatMap { sources.indices.contains($0.source) ? sources[$0.source] : nil } }
    var isDirty: Bool { history.isDirty || (working.map { !$0.sameContent(as: history.current) } ?? false) }
    var canUndo: Bool { request.config.allowUndo && history.canUndo && export == nil && working == nil }
    var canRedo: Bool { request.config.allowRedo && history.canRedo && export == nil && working == nil }
    var railTools: [VideoTool] { tools.filter { $0 != .trim || request.config.maxClipCount == 1 } }
    var toolHasChanges: Bool {
        guard activeTool != nil else { return false }
        if activeTool?.isDraft == true {
            guard var working else { return false }
            working.overlays.removeAll { timed in
                if case .text(let text, _) = timed.overlay.content { return text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
                return false
            }
            if let watermark = working.watermark, !watermark.isValid { working.watermark = history.current.watermark }
            return !working.sameContent(as: history.current)
        }
        return toolEntry.map { !$0.sameContent(as: displayed) } ?? false
    }
    var selectedHasAudio: Bool { selectedSource?.hasAudio == true }

    // MARK: - 열기

    func start() {
        // 화면이 다시 나타나도 원본을 다시 열지 않는다.
        guard !started else { return }
        started = true
        OutputStore.removeStalePartials()
        let issues = request.validate()
        guard issues.isEmpty else {
            frameKitLog.error("invalid video configuration: \(issues.map(\.description).joined(separator: ", "), privacy: .public)")
            finish(.failure(EditorError(code: .invalidConfiguration)))
            return
        }
        do {
            try CatalogInstaller.install(request.catalog)
        } catch {
            finish(.failure(error))
            return
        }
        if !restoreSession() { openInput() }
    }

    private func openInput() {
        switch request.input {
        case .file(let url): open([url], append: false, copy: true)
        case .files(let urls): open(Array(urls.prefix(request.config.maxClipCount)), append: false, copy: true)
        case .capture:
            guard Bundle.main.object(forInfoDictionaryKey: "NSCameraUsageDescription") != nil else {
                frameKitLog.error("NSCameraUsageDescription is missing in the host Info.plist")
                finish(.failure(EditorError(code: .invalidConfiguration)))
                return
            }
            // 영상 촬영은 소리도 녹음하므로 마이크 설명이 없으면 시스템이 앱을 끝낸다.
            guard Bundle.main.object(forInfoDictionaryKey: "NSMicrophoneUsageDescription") != nil else {
                frameKitLog.error("NSMicrophoneUsageDescription is missing in the host Info.plist")
                finish(.failure(EditorError(code: .invalidConfiguration)))
                return
            }
            guard UIImagePickerController.isSourceTypeAvailable(.camera) else { finish(.failure(EditorError(code: .cameraUnavailable))); return }
            if [.denied, .restricted].contains(AVCaptureDevice.authorizationStatus(for: .video)) {
                finish(.failure(EditorError(code: .permissionDenied)))
            }
        default: break
        }
    }

    var initialPickLimit: Int {
        if case .pickMultiple(let max) = request.input { return min(max, request.config.maxClipCount) }
        return 1
    }

    /// 선택기·카메라 결과. 비어 있으면 닫은 것이다.
    func picked(_ urls: [URL], append: Bool = false) {
        showAddPicker = false
        guard !urls.isEmpty else {
            if !append { finish(.cancelled) }
            return
        }
        // 선택기·카메라가 이미 임시 폴더에 복사했다.
        open(urls, append: append, copy: false)
    }

    private func open(_ urls: [URL], append: Bool, copy: Bool) {
        if append { busy = true } else { phase = .loading }
        let minClip = minClip, maxTimeline = maxTimeline, limit = request.config.maxClipCount
        let existing = append ? displayed : nil
        let baseIndex = sources.count
        Task {
            var opened: [VideoSource] = []
            var failure: EditorError?
            for url in urls {
                do {
                    let local = copy ? try Self.copyToTemporary(url) : url
                    let source = try await VideoSource.open(url: local, removeOnClose: true)
                    guard source.duration >= minClip else {
                        source.close()
                        throw EditorError.logged(.invalidSource, "shorter than minimum clip")
                    }
                    opened.append(source)
                } catch {
                    failure = (error as? EditorError) ?? .logged(.unknown, "open video: \(error)")
                    if !append { break }
                }
            }
            if let failure, !append || opened.isEmpty {
                opened.forEach { $0.close() }
                if append { busy = false; showNotice("add_failed") } else { phase = .failed(failure) }
                return
            }
            var edit = existing ?? VideoEdit(clips: [])
            var used = edit.duration
            var skipped = false
            for (offset, source) in opened.enumerated() {
                let remaining = maxTimeline - used
                guard edit.clips.count < limit, remaining >= minClip else { skipped = true; source.close(); continue }
                let end = min(source.duration, remaining)
                edit.clips.append(VideoClipState(id: "clip-\(UUID().uuidString.prefix(8))", source: baseIndex + offset,
                                                 sourceRange: TimeRangeUs(start: 0, end: end)))
                used += end
            }
            sources += opened
            thumbnailer = VideoThumbnailer(sources: sources)
            if append {
                busy = false
                history.commit(edit)
                if skipped { showNotice(edit.clips.count >= limit ? "clip_limit" : "timeline_full") }
            } else {
                if let config = request.config.watermark, config.applyOnOpen, config.spec.isValid { edit.watermark = config.spec }
                history = VideoHistory(edit)
                selectedClipId = edit.clips.first?.id
                phase = .ready
                observePlayer()
            }
            rebuildPreview(immediately: true)
        }
    }

    // MARK: - 세션

    /// 남은 세션이 있으면 이어 연다. 열기 시작했으면 `true`.
    private func restoreSession() -> Bool {
        guard let session, let state = session.load(VideoSessionState.self), !state.current.clips.isEmpty else { return false }
        guard let videoURLs = session.restoreFiles(state.sources) else { session.discard(); return false }
        guard let audioURLs = session.restoreFiles(state.audio.map(\.file)) else {
            videoURLs.forEach { try? FileManager.default.removeItem(at: $0) }
            session.discard()
            return false
        }
        phase = .loading
        Task {
            var opened: [VideoSource] = []
            var audio: [AudioSource] = []
            do {
                for url in videoURLs { opened.append(try await VideoSource.open(url: url, removeOnClose: true)) }
                for (url, saved) in zip(audioURLs, state.audio) { audio.append(try await AudioSource.open(url: url, name: saved.name)) }
            } catch {
                opened.forEach { $0.close() }
                audio.forEach { $0.close() }
                restoreFailed()
                return
            }
            sources = opened
            audioSources = audio
            thumbnailer = VideoThumbnailer(sources: sources)
            audioSources.indices.forEach(loadWave)
            history = VideoHistory(current: state.current, baseline: state.baseline)
            selectedClipId = state.current.clips.first?.id
            phase = .ready
            observePlayer()
            showNotice("session_restored")
            rebuildPreview(immediately: true)
        }
        return true
    }

    private func restoreFailed() {
        session?.discard()
        guard onRestoreFailed == nil else { onRestoreFailed?(); return }
        switch request.input {
        case .pick, .pickMultiple, .capture: phase = .awaitingSource
        default: phase = .loading
        }
        openInput()
    }

    /// 적용한 편집만 남긴다. 도구에서 바꾸는 중인 값은 남기지 않는다.
    private func persistSession() {
        guard let session, phase == .ready, !delivered, !history.current.clips.isEmpty else { return }
        var files = Set<String>()
        let videos = sources.map { source in
            let file = session.name(for: source.url)
            files.insert(file)
            return file
        }
        let audio = session.audioFiles(audioSources, into: &files)
        session.save(VideoSessionState(sources: videos, audio: audio, current: history.current, baseline: history.baseline), files: files)
    }

    /// 선택기가 넘긴 항목.
    struct ItemBox: @unchecked Sendable { let provider: NSItemProvider }

    nonisolated static func copyToTemporary(_ url: URL) throws(EditorError) -> URL {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        let target = try OutputStore.temporaryDirectory.appendingPathComponent(UUID().uuidString)
            .appendingPathExtension(url.pathExtension.isEmpty ? "mov" : url.pathExtension)
        do {
            try FileManager.default.copyItem(at: url, to: target)
            return target
        } catch let error as CocoaError where error.code == .fileReadNoPermission {
            throw .logged(.permissionDenied, "copy: \(error)")
        } catch {
            throw .logged(.sourceUnavailable, "copy: \(error)")
        }
    }

    // MARK: - 재생

    private func observePlayer() {
        guard timeObserver == nil else { return }
        timeObserver = player.addPeriodicTimeObserver(forInterval: CMTime(value: 1, timescale: 30), queue: .main) { [weak self] time in
            MainActor.assumeIsolated {
                guard let self else { return }
                let local = time.micros
                self.position = min(self.duration, self.globalTime(local))
                self.refreshOverlayImage()
                self.isPlaying = self.player.timeControlStatus == .playing
            }
        }
    }

    /// 자르기·회전 도구는 그 클립만 재생하므로 시각을 바꿔 준다.
    private var singleClipStart: Micros? {
        guard let tool = activeTool, tool.isGeometry, let index = selectedIndex else { return nil }
        return displayed.clipStarts[index]
    }

    private func globalTime(_ local: Micros) -> Micros { local + (singleClipStart ?? 0) }
    private func localTime(_ global: Micros) -> Micros { max(0, global - (singleClipStart ?? 0)) }

    func togglePlay() {
        if isPlaying {
            player.pause()
        } else {
            if position >= duration - Self.frameUs { seek(to: 0) }
            player.play()
        }
        isPlaying.toggle()
    }

    func pause() {
        player.pause()
        isPlaying = false
    }

    func seek(to t: Micros, scrubbing: Bool = false) {
        let clamped = min(max(0, t), max(0, duration - 1))
        position = clamped
        if scrubbing { player.pause(); isPlaying = false }
        player.seek(to: localTime(clamped).cmTime, toleranceBefore: .zero, toleranceAfter: .zero)
    }

    /// 편집이 바뀌면 120ms 뒤 미리 보기를 다시 만든다. 위치는 유지한다.
    func rebuildPreview(immediately: Bool = false) {
        rebuildTask?.cancel()
        let edit = displayed
        let sources = sources, audio = audioSources
        let single = activeTool?.isGeometry == true ? selectedIndex : nil
        rebuildTask = Task {
            if !immediately { try? await Task.sleep(for: .milliseconds(120)) }
            guard !Task.isCancelled, !edit.clips.isEmpty else { return }
            do {
                let built = try VideoCompositionBuilder.build(edit, sources: sources, audio: audio, maxShortSide: Self.previewShortSide,
                                                              frameRate: 30, overlays: false, singleClip: single)
                let item = AVPlayerItem(asset: built.asset)
                item.videoComposition = built.videoComposition
                item.audioMix = built.audioMix
                item.audioTimePitchAlgorithm = .spectral
                let wasPlaying = isPlaying
                player.replaceCurrentItem(with: item)
                renderSize = built.size
                previewFailed = false
                refreshOverlayImage(force: true)
                seek(to: position)
                if wasPlaying { player.play() }
            } catch {
                previewFailed = true
            }
        }
    }

    // MARK: - 도구

    func selectTool(_ tool: VideoTool) {
        guard phase == .ready, export == nil else { return }
        if tool.isDraft || tool == .sticker { pause() }
        activeTool = tool
        if tool.isDraft { working = history.current } else { toolEntry = history.current }
        if tool == .crop { cropAspect = .free }
        if tool == .filter { makeFilterThumbnails() }
        if tool.isGeometry { loadCropFrame() }
        clearSelection()
        if tool == .text { beginText() }
        if tool == .watermark, working?.watermark == nil {
            working?.watermark = lastWatermark ?? request.config.watermark.map { config in
                var spec = config.spec
                if !config.applyOnOpen { spec.text = "" }
                return spec
            } ?? WatermarkSpec(text: "")
        }
        rebuildPreview(immediately: true)
    }

    func applyTool() {
        guard let tool = activeTool else { return }
        if tool.isDraft, var working {
            working.overlays.removeAll { timed in
                if case .text(let text, _) = timed.overlay.content { return text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
                return false
            }
            if let watermark = working.watermark, !watermark.isValid { working.watermark = nil }
            if let watermark = working.watermark { lastWatermark = watermark }
            working.clampTimed()
            history.commit(working)
        }
        closeTool()
    }

    private func closeTool() {
        let wasGeometry = activeTool?.isGeometry == true
        let start = singleClipStart
        working = nil
        activeTool = nil
        toolEntry = nil
        cropFrame = nil
        if wasGeometry, let start { position = min(duration, position + 0 * start) }
        rebuildPreview(immediately: true)
    }

    func discardTool() {
        guard let tool = activeTool else { return }
        if !tool.isDraft, let entry = toolEntry { history.commit(entry) }
        closeTool()
    }

    func onBack() {
        guard activeTool != nil else { requestClose(); return }
        if toolHasChanges { showDraftDialog = true } else { closeTool() }
    }

    func requestClose() {
        if export != nil { cancelExport() } else if isDirty { showDiscardDialog = true } else { finish(.cancelled) }
    }

    func discardAndClose() {
        showDiscardDialog = false
        finish(.cancelled)
    }

    func undo() {
        guard canUndo else { return }
        history.undo()
        ensureSelection()
        rebuildPreview()
    }

    func redo() {
        guard canRedo else { return }
        history.redo()
        ensureSelection()
        rebuildPreview()
    }

    private func ensureSelection() {
        if !displayed.clips.contains(where: { $0.id == selectedClipId }) { selectedClipId = displayed.clips.first?.id }
    }

    /// 바로 반영되는 도구는 기록에 남기고, 적용 전 도구는 작업 상태만 바꾼다.
    func change(commit: Bool = true, _ body: (inout VideoEdit) -> Void) {
        var edit = working ?? history.current
        body(&edit)
        if activeTool?.isDraft == true || !commit {
            working = edit
        } else {
            working = nil
            history.commit(edit)
        }
        rebuildPreview()
        refreshOverlayImage(force: true)
    }

    func commitGesture() {
        guard activeTool?.isDraft != true, let working else { return }
        history.commit(working)
        self.working = nil
        rebuildPreview()
    }

    private func changeSelectedClip(commit: Bool = true, _ body: (inout VideoClipState) -> Void) {
        guard let index = selectedIndex else { return }
        change(commit: commit) { edit in body(&edit.clips[index]) }
    }

    // MARK: - 클립

    func selectClip(at t: Micros) {
        if let found = displayed.locate(t) { selectedClipId = displayed.clips[found.index].id }
    }

    func splitAtPlayhead() {
        guard displayed.clips.count < request.config.maxClipCount else { showNotice("clip_limit"); return }
        guard let found = displayed.locate(position), found.index < displayed.clips.count else { return }
        let clip = displayed.clips[found.index]
        let leftOut = position - found.clipStart
        let rightOut = clip.outputDuration - leftOut
        guard leftOut >= minClip, rightOut >= minClip else { showNotice("split_unavailable"); return }
        var left = clip, right = clip
        left.sourceRange.end = found.sourceTime
        right.sourceRange.start = found.sourceTime
        right.id = "clip-\(UUID().uuidString.prefix(8))"
        change { edit in edit.clips.replaceSubrange(found.index...found.index, with: [left, right]) }
        selectedClipId = right.id
    }

    func moveSelectedClip(by offset: Int) {
        guard let index = selectedIndex else { return }
        let target = index + offset
        guard displayed.clips.indices.contains(target) else { return }
        change { $0.clips.swapAt(index, target) }
        seek(to: displayed.clipStarts[target])
    }

    func deleteSelectedClip() {
        guard let index = selectedIndex, displayed.clips.count > 1 else { return }
        change { edit in
            edit.clips.remove(at: index)
            edit.clampTimed()
        }
        selectedClipId = displayed.clips[min(index, displayed.clips.count - 1)].id
        seek(to: min(position, duration))
    }

    func requestAddClips() {
        guard displayed.clips.count < request.config.maxClipCount else { showNotice("clip_limit"); return }
        guard maxTimeline - duration >= minClip else { showNotice("timeline_full"); return }
        showAddPicker = true
    }

    // MARK: - 구간

    /// 구간 손잡이를 끈다. 값은 원본 시각.
    func dragTrim(start: Bool, to sourceTime: Micros) {
        guard let index = selectedIndex else { return }
        let clip = displayed.clips[index]
        if trimStart == nil { trimStart = clip }
        let others = duration - clip.outputDuration
        let minLen = Micros((Double(minClip) * clip.speed).rounded())
        let maxLen = Micros(Double(maxTimeline - others) * clip.speed)
        let sourceDuration = sources[clip.source].duration
        var range = clip.sourceRange
        if start {
            range.start = min(max(0, sourceTime), range.end - minLen)
            range.start = max(range.start, range.end - maxLen)
        } else {
            range.end = max(min(sourceDuration, sourceTime), range.start + minLen)
            range.end = min(range.end, range.start + maxLen)
        }
        change(commit: false) { $0.clips[index].sourceRange = range }
        let clipStart = displayed.clipStarts[index]
        seek(to: start ? clipStart : clipStart + displayed.clips[index].outputDuration - Self.frameUs, scrubbing: true)
    }

    func endTrim() {
        trimStart = nil
        if activeTool == nil { commitGesture() }
    }

    // MARK: - 속도

    func speedAllowed(_ speed: Double) -> String? {
        guard let clip = selectedClip else { return nil }
        let out = Micros((Double(clip.sourceRange.duration) / speed).rounded())
        if duration - clip.outputDuration + out > maxTimeline { return "speed_too_long" }
        if out < minClip { return "speed_too_short" }
        return nil
    }

    func selectSpeed(_ speed: Double) {
        if let problem = speedAllowed(speed) { showNotice(problem); return }
        changeSelectedClip { $0.speed = speed }
        if let index = selectedIndex { seek(to: displayed.clipStarts[index]) }
    }

    // MARK: - 소리

    func setVolume(_ volume: Double, commit: Bool) {
        changeSelectedClip(commit: commit) { clip in
            clip.volume = min(VideoClipState.maxVolume, max(0, volume))
            clip.muted = false
        }
    }

    func toggleMute() {
        changeSelectedClip { $0.muted.toggle() }
    }

    // MARK: - 화면 비율

    func setCanvas(aspect: (Int, Int)?, fit: CanvasFit) {
        change { $0.canvas = CanvasState(aspectWidth: aspect?.0, aspectHeight: aspect?.1, fit: fit) }
    }

    // MARK: - 초기화

    func resetTool() {
        switch activeTool {
        case .trim:
            guard let clip = selectedClip else { return }
            let end = min(sources[clip.source].duration, Micros(Double(maxTimeline - (duration - clip.outputDuration)) * clip.speed))
            changeSelectedClip { $0.sourceRange = TimeRangeUs(start: 0, end: end) }
        case .crop:
            cropAspect = .free
            let size = selectedSource?.uprightSize.kotlin
            changeSelectedClip { clip in
                guard let size else { return }
                clip.effects.geometry = GeometryState(GeometryOperations.shared.withAspect(sourceSize: size, geometry: clip.effects.geometry.kotlin,
                                                                                            aspect: CropAspect.free.kotlin))
            }
        case .rotate: changeSelectedClip { $0.effects.geometry = GeometryState() }
        case .speed: changeSelectedClip { $0.speed = 1 }
        case .adjust: changeSelectedClip { $0.effects.adjustments = [:] }
        case .filter: changeSelectedClip { $0.effects.filterId = "original"; $0.effects.filterIntensity = 0 }
        case .audio: changeSelectedClip { $0.muted = false; $0.volume = 1 }
        case .canvas: change { $0.canvas = CanvasState() }
        case .text: setTextStyle { $0 = TextStyle() }
        case .sticker:
            change { edit in edit.overlays.removeAll { if case .sticker = $0.overlay.content { true } else { false } } }
            showNotice("cleared")
        case .privacy:
            change { $0.masks = [] }
            showNotice("cleared")
        case .watermark: setWatermark { spec in spec = WatermarkSpec(text: spec.text) }
        default: break
        }
    }

    // MARK: - 저장

    func save() {
        guard phase == .ready, export == nil, !displayed.clips.isEmpty else { return }
        if let tool = activeTool {
            if tool.isDraft { applyTool() } else if working != nil { showNotice("apply_first"); return } else { closeTool() }
        }
        pause()
        var edit = history.current
        if let config = request.config.watermark, config.locked { edit.watermark = config.spec }
        let sources = sources, audio = audioSources, config = request.export
        export = .running(.encoding, nil)
        IdleTimerHold.acquire()
        exportTask = Task {
            defer { IdleTimerHold.release() }
            do {
                let media = try await VideoExporter.export(edit, sources: sources, audio: audio, config: config) { fraction in
                    Task { @MainActor in if case .running = self.export { self.export = .running(.encoding, fraction) } }
                }
                finish(.success(output: media, outputs: [media]))
            } catch is CancellationError {
                export = nil
            } catch {
                export = .failed((error as? EditorError) ?? .logged(.unknown, "video export: \(error)"))
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

    // MARK: - 기타

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
        session?.discard()
        onExternalFinish?(result)
        rebuildTask?.cancel()
        exportTask?.cancel()
        player.pause()
        player.replaceCurrentItem(with: nil)
        if let timeObserver { player.removeTimeObserver(timeObserver) }
        sources.forEach { $0.close() }
        audioSources.forEach { $0.close() }
        onFinish(result)
    }

    func dismissedWithoutResult() { finish(.cancelled) }
}

// MARK: - 색·기하 도구

extension VideoEditorModel {
    var currentGeometry: GeometryState { selectedClip?.effects.geometry ?? GeometryState() }
    func adjustmentValue(_ kind: AdjustKind) -> Double { selectedClip?.effects.adjustments[kind] ?? 0 }
    var currentFilterId: String { selectedClip?.effects.filterId ?? "original" }
    var currentFilterIntensity: Double { selectedClip?.effects.filterIntensity ?? 0 }
    var catalog: EditorCatalog { request.catalog }

    private var uprightKotlin: PixelSize? { selectedSource?.uprightSize.kotlin }

    func setAspect(_ aspect: CropAspect) {
        cropAspect = aspect
        guard let size = uprightKotlin else { return }
        changeSelectedClip { clip in
            clip.effects.geometry = GeometryState(GeometryOperations.shared.withAspect(sourceSize: size, geometry: clip.effects.geometry.kotlin,
                                                                                        aspect: aspect.kotlin))
        }
    }

    func rotate(clockwise: Bool) {
        guard let size = uprightKotlin else { return }
        let aspect = cropAspect
        changeSelectedClip { clip in
            let ops = GeometryOperations.shared
            var g = clockwise ? ops.rotateClockwise(geometry: clip.effects.geometry.kotlin) : ops.rotateCounterClockwise(geometry: clip.effects.geometry.kotlin)
            if case .fixed = aspect { g = ops.withAspect(sourceSize: size, geometry: g, aspect: aspect.kotlin) }
            clip.effects.geometry = GeometryState(g)
        }
    }

    func flip(horizontal: Bool) {
        changeSelectedClip { clip in
            let ops = GeometryOperations.shared
            clip.effects.geometry = GeometryState(horizontal ? ops.flipHorizontal(geometry: clip.effects.geometry.kotlin)
                : ops.flipVertical(geometry: clip.effects.geometry.kotlin))
        }
    }

    func setStraighten(_ degrees: Double) {
        guard let size = uprightKotlin else { return }
        let start = straightenStart ?? currentGeometry
        straightenStart = start
        changeSelectedClip { clip in
            clip.effects.geometry = GeometryState(GeometryOperations.shared.withStraighten(sourceSize: size, start: start.kotlin, degrees: degrees))
        }
    }

    func finishStraighten() { straightenStart = nil }

    func setAdjustment(_ kind: AdjustKind, display: Double) {
        let value = kind.fromDisplay(display)
        changeSelectedClip(commit: false) { $0.effects.adjustments[kind] = value == 0 ? nil : value }
    }

    func setFilterIntensity(display: Double) {
        changeSelectedClip(commit: false) { $0.effects.filterIntensity = min(1, max(0, display / 100)) }
    }

    func selectFilter(_ id: String) {
        changeSelectedClip { clip in
            if id == "original" {
                clip.effects.filterId = id
                clip.effects.filterIntensity = 0
            } else if clip.effects.filterId != id {
                clip.effects.filterId = id
                clip.effects.filterIntensity = 1
            }
        }
    }

    func beginCropDrag(_ handle: CropHandle) {
        cropDragStart = (currentGeometry.crop, handle)
        draggingCrop = true
    }

    func dragCrop(dx: Double, dy: Double) {
        guard let start = cropDragStart, let source = selectedSource else { return }
        let math = GeometryMath(source: source.uprightSize, geometry: currentGeometry)
        let pixelAspect: KotlinDouble? = cropAspect == .free ? nil
            : KotlinDouble(double: CropBoundsCalculator.shared.resolvePixelAspect(frame: math.frame, aspect: cropAspect.kotlin))
        let rect = CropHandleDrag.shared.drag(frame: math.frame, start: start.rect.kotlin, handle: start.handle, dx: dx, dy: dy, pixelAspect: pixelAspect)
        changeSelectedClip { $0.effects.geometry.crop = NRect(rect) }
    }

    func endCropDrag() {
        cropDragStart = nil
        draggingCrop = false
    }

    /// 자르기 화면에 쓸 지금 위치의 클립 프레임.
    private func loadCropFrame() {
        guard let clip = selectedClip, let source = selectedSource, let found = displayed.locate(position) else { return }
        let time = found.index == selectedIndex ? found.sourceTime : clip.sourceRange.start
        Task {
            if let image = await source.frame(at: time, maxSide: 1280) { cropFrame = image.cgImage }
        }
    }

    /// 필터 작은 그림(선택 클립 첫 프레임).
    private func makeFilterThumbnails() {
        guard filterThumbnails.isEmpty, let clip = selectedClip, let source = selectedSource else { return }
        let seed = displayed.grainSeed
        Task {
            guard let upright = await source.frame(at: clip.sourceRange.start, maxSide: 200) else { return }
            let thumbnails = await Task.detached {
                let frame = upright.cgImage
                var result: [String: CGImage] = [:]
                let size = PixelSizeValue(width: frame.width, height: frame.height)
                for id in FilterCatalog.shared.all.map(\.id) {
                    var edit = PhotoEdit(grainSeed: seed)
                    edit.filterId = id
                    edit.filterIntensity = id == "original" ? 0 : 1
                    if let image = try? PhotoRenderer.render(image: UprightImage(cgImage: frame), uprightSize: size, edit: edit, crop: .full,
                                                             output: size, background: nil, includeCanvasEffects: true) {
                        result[id] = image
                    }
                }
                return result
            }.value
            filterThumbnails = thumbnails
        }
    }
}
