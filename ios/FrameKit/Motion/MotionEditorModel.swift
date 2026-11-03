import AVFoundation
import Observation
import SwiftUI
import UniformTypeIdentifiers
internal import FrameKitCore

/// 움직이는 이미지 편집기 상태와 동작.
@MainActor
@Observable
final class MotionEditorModel {
    enum Phase: Equatable {
        case awaitingSource, loading, failed(EditorError), ready
    }

    nonisolated static let previewLongEdge = 720
    nonisolated static let thumbnailPx = 160
    static let frameMs: Int64 = 33
    static let speedPresets: [Double] = [0.5, 0.75, 1, 1.5, 2, 3]
    /// 미리 보기 소리가 이만큼 어긋나면 다시 맞춘다.
    static let audioDriftMs: Int64 = 250

    let request: MotionEditorRequest
    let tools: [MotionTool]
    private let onFinish: (FrameKitResult) -> Void
    private var delivered = false

    private(set) var phase: Phase
    private(set) var sources: [MotionSource] = []
    private(set) var media: MotionMedia?
    var audioSources: [AudioSource] = []
    var history = MotionHistory(MotionProject(kind: .slideshow, slides: []))
    var working: MotionProject?
    private(set) var activeTool: MotionTool?
    private var toolEntry: MotionProject?
    private(set) var position: Int64 = 0
    private(set) var isPlaying = false
    private(set) var frame: CGImage?
    private(set) var thumbnails: [Int: CGImage] = [:]
    var selectedSlide = 0
    private(set) var export: ExportUI?
    var showDiscardDialog = false
    var showDraftDialog = false
    var showSaveSheet = false
    var saveFormat: MotionFormat
    var saveSize: MotionSize
    private(set) var notice: String?
    var busy = false
    var showAddPicker = false
    var showMusicPicker = false
    var musicPickerRole = AudioRole.music
    var showRecorder = false
    var adjustKind: AdjustKind = .brightness
    private(set) var filterThumbnails: [String: CGImage] = [:]
    var selectedOverlayId: String?
    var selectedAudioId: String?
    var stickerCategory = 0
    var privacy = PrivacySettings()

    private let audioPlayer = AVPlayer()
    private var audioKey = ""
    private var playTask: Task<Void, Never>?
    private var exportTask: Task<Void, Never>?
    private var noticeTask: Task<Void, Never>?
    private var rendering = false
    private var renderAgain = false
    private var audioDragStart: Micros?

    init(request: MotionEditorRequest, onFinish: @escaping (FrameKitResult) -> Void) {
        self.request = request
        self.onFinish = onFinish
        var seen = Set<MotionTool>()
        tools = request.config.enabledTools.filter { seen.insert($0).inserted }
        saveFormat = request.export.format
        saveSize = request.export.size
        switch request.input {
        case .pick, .pickMultiple: phase = .awaitingSource
        default: phase = .loading
        }
        audioPlayer.actionAtItemEnd = .pause
    }

    // MARK: - 표시 값

    var displayed: MotionProject { working ?? history.current }
    var duration: Int64 { media.map { MotionTimeline.durationMs(displayed, $0) } ?? 1 }
    var durationUs: Micros { duration * 1000 }
    var positionUs: Micros { position * 1000 }
    var renderSize: CGSize { media.map { MotionTimeline.outputSize(displayed, $0, maxLongEdge: Self.previewLongEdge) } ?? CGSize(width: 2, height: 2) }
    var isSlideshow: Bool { displayed.kind == .slideshow }
    var maxImageCount: Int { request.config.maxImageCount }
    var isDirty: Bool { history.isDirty || (working.map { !$0.sameContent(as: history.current) } ?? false) }
    var canUndo: Bool { request.config.allowUndo && history.canUndo && export == nil && working == nil }
    var canRedo: Bool { request.config.allowRedo && history.canRedo && export == nil && working == nil }
    var hasAudio: Bool { !displayed.audio.isEmpty }
    var animationDurationMs: Int64 { media?.animation?.totalMs ?? 0 }

    /// 지금 원본에서 쓸 수 있는 도구.
    var railTools: [MotionTool] {
        tools.filter { tool in
            switch tool {
            case .motion: isSlideshow
            case .transition: isSlideshow && displayed.slides.count > 1
            default: true
            }
        }
    }

    var toolHasChanges: Bool {
        guard let tool = activeTool else { return false }
        if tool == .text {
            guard var working else { return false }
            working.overlays.removeAll { Self.isBlankText($0.overlay) }
            return !working.sameContent(as: history.current)
        }
        return toolEntry.map { !$0.sameContent(as: displayed) } ?? false
    }

    static func isBlankText(_ overlay: Overlay) -> Bool {
        if case .text(let text, _) = overlay.content { return text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
        return false
    }

    // MARK: - 열기

    func start() {
        OutputStore.removeStalePartials()
        let issues = request.validate()
        guard issues.isEmpty else {
            frameKitLog.error("invalid motion configuration: \(issues.map(\.description).joined(separator: ", "), privacy: .public)")
            finish(.failure(EditorError(code: .invalidConfiguration)))
            return
        }
        do {
            try CatalogInstaller.install(request.catalog)
        } catch {
            finish(.failure(error))
            return
        }
        switch request.input {
        case .file(let url): load([{ try Self.copyToTemporary(url) }], append: false)
        case .files(let urls): load(urls.map { url in { try Self.copyToTemporary(url) } }, append: false)
        case .data(let data): load([{ try Self.writeTemporary(data) }], append: false)
        default: break
        }
    }

    var initialPickLimit: Int {
        if case .pickMultiple(let max) = request.input { return min(max, maxImageCount) }
        return 1
    }

    /// 선택기 결과. 비어 있으면 닫은 것이다.
    func picked(_ providers: [NSItemProvider], append: Bool = false) {
        showAddPicker = false
        guard !providers.isEmpty else {
            if !append { finish(.cancelled) }
            return
        }
        let room = append ? max(0, maxImageCount - displayed.slides.count) : maxImageCount
        let chosen = Array(providers.prefix(room))
        if chosen.count < providers.count { showNotice("motion_notice_slide_limit") }
        guard !chosen.isEmpty else { return }
        if !append { phase = .loading } else { busy = true }
        Task {
            var prepares: [@Sendable () throws -> URL] = []
            for provider in chosen {
                let copied = await Self.copy(ItemProviderBox(provider: provider))
                prepares.append { try copied.get() }
            }
            load(prepares, append: append)
        }
    }

    private func load(_ prepares: [@Sendable () throws -> URL], append: Bool) {
        if !append { phase = .loading }
        let maxFrames = request.config.maxAnimationFrames
        let room = append ? max(0, maxImageCount - displayed.slides.count) : maxImageCount
        Task {
            let result = await Task.detached(priority: .userInitiated) { () -> Result<([MotionSource], AnimatedFrames?, Bool), EditorError> in
                var opened: [MotionSource] = []
                do {
                    for prepare in prepares {
                        opened.append(try MotionSource(url: try prepare(), removeOnClose: true))
                    }
                    // 한 장이 움직이는 원본이면 그것만 편집한다. 여러 장 가운데 움직이는 원본은 첫 프레임만 쓴다.
                    if !append, opened.count == 1, opened[0].isAnimated {
                        let budget = min(MotionSource.maxAnimationBytes, Int(ProcessInfo.processInfo.physicalMemory / 8))
                        let (frames, trimmed) = try opened[0].decodeAnimation(maxLongEdge: Self.previewLongEdge, maxFrames: maxFrames, maxBytes: budget)
                        return .success((opened, frames, trimmed))
                    }
                    if opened.count > room { opened[room...].forEach { $0.close() }; opened = Array(opened.prefix(room)) }
                    return .success((opened, nil, false))
                } catch {
                    opened.forEach { $0.close() }
                    return .failure((error as? EditorError) ?? .logged(.unknown, "motion load: \(error)"))
                }
            }.value
            busy = false
            switch result {
            case .success(let (opened, animation, trimmed)):
                if append { appendSlides(opened) } else { didOpen(opened, animation: animation) }
                if trimmed { showNotice("motion_notice_frames_trimmed") }
            case .failure(let error):
                if append { showNotice(errorKey(error.code)) } else { phase = .failed(error) }
            }
        }
    }

    private func didOpen(_ opened: [MotionSource], animation: AnimatedFrames?) {
        guard !opened.isEmpty else { phase = .failed(.logged(.invalidSource, "no motion source")); return }
        sources = opened
        rebuildMedia(animation: animation)
        var project = MotionProject(kind: animation == nil ? .slideshow : .animation, slides: Array(opened.indices))
        project.slideDurationMs = min(max(Int64((request.config.slideDuration * 1000).rounded()), MotionProject.minSlideMs), MotionProject.maxSlideMs)
        project.transitionMs = min(MotionProject.defaultTransitionMs, project.slideDurationMs / 2)
        if animation != nil { project.cameraMotion = .none }
        history = MotionHistory(project)
        phase = .ready
        makeThumbnails(opened.indices)
        requestRender()
    }

    private func appendSlides(_ opened: [MotionSource]) {
        guard !opened.isEmpty else { return }
        let base = sources.count
        sources += opened
        rebuildMedia(animation: nil)
        change { $0.slides += Array(base..<(base + opened.count)) }
        makeThumbnails(base..<sources.count)
        selectSlide(displayed.slides.count - 1)
    }

    private func rebuildMedia(animation: AnimatedFrames?) {
        let cache = SlideCache(sources: sources, longEdge: Self.previewLongEdge, capacity: 6)
        media = MotionMedia(kind: animation == nil ? .slideshow : .animation, aspects: sources.map(\.aspect),
                            animation: animation ?? media?.animation) { cache.image($0) }
    }

    private func makeThumbnails(_ range: Range<Int>) {
        let items = range.map { ($0, sources[$0]) }
        Task {
            for (index, source) in items {
                let box = SourceBox(source: source)
                if let image = await Task.detached(operation: { box.source.decodeStill(maxLongEdge: Self.thumbnailPx) }).value {
                    thumbnails[index] = image
                }
            }
        }
    }

    struct SourceBox: @unchecked Sendable { let source: MotionSource }
    struct ItemProviderBox: @unchecked Sendable { let provider: NSItemProvider }

    /// 움직이는 형식을 먼저 고른다(사진 앱의 GIF가 정지 사진으로 바뀌지 않게).
    nonisolated static func copy(_ box: ItemProviderBox) async -> Result<URL, EditorError> {
        let provider = box.provider
        let preferred: [UTType] = [.gif, .webP, .png, .heic, .heif, .jpeg]
        let registered = provider.registeredTypeIdentifiers.compactMap(UTType.init)
        let type = preferred.first { p in registered.contains { $0.conforms(to: p) } }
            ?? registered.first { $0.conforms(to: .image) } ?? .image
        return await withCheckedContinuation { continuation in
            provider.loadFileRepresentation(forTypeIdentifier: type.identifier) { url, error in
                continuation.resume(returning: Result { () throws(EditorError) -> URL in
                    guard let url else { throw .logged(.sourceUnavailable, "picker: \(String(describing: error))") }
                    return try copyToTemporary(url)
                })
            }
        }
    }

    nonisolated static func copyToTemporary(_ url: URL) throws(EditorError) -> URL {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        let target = try OutputStore.temporaryDirectory.appendingPathComponent(UUID().uuidString)
            .appendingPathExtension(url.pathExtension.isEmpty ? "img" : url.pathExtension)
        do {
            try FileManager.default.copyItem(at: url, to: target)
            return target
        } catch let error as CocoaError where error.code == .fileReadNoPermission {
            throw .logged(.permissionDenied, "copy: \(error)")
        } catch let error as CocoaError where error.code == .fileWriteOutOfSpace {
            throw .logged(.insufficientStorage, "copy: \(error)")
        } catch {
            throw .logged(.sourceUnavailable, "copy: \(error)")
        }
    }

    nonisolated static func writeTemporary(_ data: Data) throws(EditorError) -> URL {
        let target = try OutputStore.temporaryDirectory.appendingPathComponent(UUID().uuidString).appendingPathExtension("img")
        do {
            try data.write(to: target)
            return target
        } catch {
            throw .logged(.outputWriteFailed, "write data: \(error)")
        }
    }

    // MARK: - 재생

    func togglePlay() { isPlaying ? pause() : play() }

    func play() {
        guard phase == .ready, export == nil, !isPlaying else { return }
        if position >= duration - Self.frameMs { position = 0 }
        isPlaying = true
        let startPosition = position
        let started = ContinuousClock.now
        syncAudio(force: true)
        playTask?.cancel()
        playTask = Task {
            var previous = startPosition
            while !Task.isCancelled, isPlaying {
                let begin = ContinuousClock.now
                let total = max(1, duration)
                let elapsed = (begin - started).components
                let ms = elapsed.seconds * 1000 + elapsed.attoseconds / 1_000_000_000_000_000
                position = (startPosition + ms) % total
                syncAudio(force: position < previous)
                previous = position
                await renderFrame()
                let spent = ContinuousClock.now - begin
                try? await Task.sleep(for: max(.milliseconds(1), .milliseconds(Self.frameMs) - spent))
            }
        }
    }

    func pause() {
        guard isPlaying else { return }
        isPlaying = false
        playTask?.cancel()
        audioPlayer.pause()
        requestRender()
    }

    func seek(to t: Int64) {
        if isPlaying { pause() }
        position = min(max(0, t), max(0, duration - 1))
        requestRender()
    }

    /// 멈춰 있을 때 지금 시각을 다시 그린다. 그리는 중이면 끝난 뒤 한 번 더 그린다.
    func requestRender() {
        guard phase == .ready, !isPlaying else { return }
        if rendering { renderAgain = true; return }
        rendering = true
        Task {
            repeat {
                renderAgain = false
                await renderFrame()
            } while renderAgain
            rendering = false
        }
    }

    private struct RenderJob: @unchecked Sendable {
        let project: MotionProject
        let media: MotionMedia
        let time: Int64
        let size: CGSize
        let spec: ColorEffectSpec?
        let animate: Bool
    }

    private func renderFrame() async {
        guard let media else { return }
        let project = displayed
        let job = RenderJob(project: project, media: media, time: position, size: renderSize, spec: project.hasColor ? project.colorSpec : nil,
                            animate: activeTool != .text && activeTool != .sticker)
        let image = await Task.detached(priority: .userInitiated) {
            autoreleasepool { MotionRenderer.render(job.project, media: job.media, timeMs: job.time, size: job.size, colorSpec: job.spec,
                                                    animateOverlays: job.animate) }
        }.value
        if let image { frame = image }
    }

    /// 음악·음성 미리 보기. 소리 조각이 바뀌면 다시 만든다.
    private func rebuildAudio() {
        let clips = displayed.audio
        let key = "\(clips)|\(duration)|\(audioSources.count)"
        guard key != audioKey else { return }
        audioKey = key
        guard !clips.isEmpty else { audioPlayer.replaceCurrentItem(with: nil); return }
        let composition = AVMutableComposition()
        let mix = AVMutableAudioMix()
        mix.inputParameters = AudioClipMixer.add(clips, sources: audioSources, duration: durationUs, to: composition)
        let item = AVPlayerItem(asset: composition)
        item.audioMix = mix
        audioPlayer.replaceCurrentItem(with: item)
        if isPlaying { syncAudio(force: true) }
    }

    private func syncAudio(force: Bool) {
        guard isPlaying, audioPlayer.currentItem != nil else { return }
        let current = audioPlayer.currentTime().micros / 1000
        if force || abs(current - position) > Self.audioDriftMs || audioPlayer.timeControlStatus == .paused {
            audioPlayer.seek(to: positionUs.cmTime, toleranceBefore: .zero, toleranceAfter: .zero)
        }
        if audioPlayer.timeControlStatus != .playing { audioPlayer.play() }
    }

    // MARK: - 도구

    func selectTool(_ tool: MotionTool) {
        guard phase == .ready, export == nil else { return }
        if tool == .text || tool == .sticker { pause() }
        activeTool = tool
        if tool == .text { working = history.current } else { toolEntry = history.current }
        if tool != .sticker { selectedOverlayId = nil }
        if tool != .audio { selectedAudioId = nil }
        if tool == .filter { makeFilterThumbnails() }
        if tool == .text { beginText() }
        requestRender()
    }

    func applyTool() {
        guard let tool = activeTool else { return }
        if tool == .text, var working {
            working.overlays.removeAll { Self.isBlankText($0.overlay) }
            history.commit(working)
        } else if let working {
            history.commit(working)
        }
        closeTool()
    }

    private func closeTool() {
        working = nil
        activeTool = nil
        toolEntry = nil
        selectedOverlayId = nil
        selectedAudioId = nil
        afterChange()
    }

    func discardTool() {
        guard let tool = activeTool else { return }
        working = nil
        if tool != .text, let entry = toolEntry { history.commit(entry) }
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
        afterChange()
    }

    func redo() {
        guard canRedo else { return }
        history.redo()
        afterChange()
    }

    /// 글자 도구는 적용 전까지 작업 상태만 바꾸고, 그 밖에는 바로 기록에 남긴다.
    func change(commit: Bool = true, _ body: (inout MotionProject) -> Void) {
        var project = working ?? history.current
        body(&project)
        if activeTool == .text || !commit {
            working = project
        } else {
            working = nil
            history.commit(project)
        }
        afterChange()
    }

    func commitGesture() {
        guard activeTool != .text, let working else { return }
        history.commit(working)
        self.working = nil
        afterChange()
    }

    private func afterChange() {
        if !displayed.audio.contains(where: { $0.id == selectedAudioId }) { selectedAudioId = nil }
        if !displayed.overlays.contains(where: { $0.overlay.id == selectedOverlayId }) && activeTool != .text { selectedOverlayId = nil }
        selectedSlide = min(selectedSlide, max(0, displayed.slides.count - 1))
        position = min(position, max(0, duration - 1))
        rebuildAudio()
        requestRender()
    }

    func resetTool() {
        let config = request.config
        switch activeTool {
        case .motion: change { $0.cameraMotion = .auto }
        case .transition: change { $0.transition = .fade; $0.transitionMs = min(MotionProject.defaultTransitionMs, $0.slideDurationMs / 2) }
        case .timing:
            change { project in
                if project.kind == .slideshow {
                    project.slideDurationMs = min(max(Int64((config.slideDuration * 1000).rounded()), MotionProject.minSlideMs), MotionProject.maxSlideMs)
                    project.transitionMs = min(project.transitionMs, project.slideDurationMs / 2)
                } else {
                    project.speed = 1
                    project.trimStartMs = 0
                    project.trimEndMs = nil
                }
            }
        case .loop: change { $0.loop = .forward }
        case .canvas: change { $0.aspect = .original; $0.fit = .fill; $0.backgroundArgb = 0xFF00_0000 }
        case .adjust: change { $0.color.adjustments = [:] }
        case .filter: change { $0.color.filterId = "original"; $0.color.filterIntensity = 0 }
        default: break
        }
    }

    var resettable: Bool { [.motion, .transition, .timing, .loop, .canvas, .adjust, .filter].contains(activeTool) }

    // MARK: - 슬라이드

    func selectSlide(_ index: Int) {
        guard displayed.slides.indices.contains(index) else { return }
        selectedSlide = index
        seek(to: Int64(index) * displayed.slideDurationMs)
    }

    func moveSlide(by offset: Int) {
        let index = selectedSlide, target = index + offset
        guard displayed.slides.indices.contains(index), displayed.slides.indices.contains(target) else { return }
        change { $0.slides.swapAt(index, target) }
        selectSlide(target)
    }

    func removeSlide() {
        guard displayed.slides.count > 1, displayed.slides.indices.contains(selectedSlide) else { return }
        let index = selectedSlide
        change { $0.slides.remove(at: index) }
        selectSlide(min(index, displayed.slides.count - 1))
    }

    func requestAddSlides() {
        guard displayed.slides.count < maxImageCount else { showNotice("motion_notice_slide_limit"); return }
        showAddPicker = true
    }

    // MARK: - 길이·반복·비율

    func setSlideDuration(_ ms: Int64, commit: Bool) {
        change(commit: commit) { project in
            project.slideDurationMs = min(max(ms, MotionProject.minSlideMs), MotionProject.maxSlideMs)
            project.transitionMs = min(project.transitionMs, project.slideDurationMs / 2)
        }
    }

    func setTransitionLength(_ ms: Int64, commit: Bool) {
        change(commit: commit) { $0.transitionMs = min(max(0, ms), $0.slideDurationMs / 2) }
    }

    func setSpeed(_ speed: Double) {
        change { $0.speed = min(max(speed, MotionProject.minSpeed), MotionProject.maxSpeed) }
        seek(to: 0)
    }

    func setTrim(start: Bool, ms: Int64, commit: Bool) {
        let total = animationDurationMs
        change(commit: commit) { project in
            let end = min(project.trimEndMs ?? total, total)
            if start {
                project.trimStartMs = min(max(0, ms), max(0, end - MotionProject.minTrimMs))
            } else {
                let value = max(min(ms, total), project.trimStartMs + MotionProject.minTrimMs)
                project.trimEndMs = value >= total ? nil : value
            }
        }
        seek(to: start ? 0 : max(0, duration - 1))
    }

    // MARK: - 텍스트·스티커 움직임

    var selectedAnimation: OverlayAnimation? { displayed.overlays.first { $0.overlay.id == selectedOverlayId }?.animation }

    func setSelectedAnimation(_ animation: OverlayAnimation) {
        guard let id = selectedOverlayId else { return }
        change { project in
            if let i = project.overlays.firstIndex(where: { $0.overlay.id == id }) { project.overlays[i].animation = animation }
        }
    }

    // MARK: - 음악·음성

    var micDeclared: Bool { Bundle.main.object(forInfoDictionaryKey: "NSMicrophoneUsageDescription") != nil }
    var selectedAudio: AudioClipState? { displayed.audio.first { $0.id == selectedAudioId } }

    /// 소리 파일을 재생 위치부터 넣는다. 음악은 반복한다.
    func addAudio(url: URL, role: AudioRole, copy: Bool = true) {
        busy = true
        Task {
            do {
                let local = copy ? try Self.copyToTemporary(url) : url
                let source = try await AudioSource.open(url: local, name: url.deletingPathExtension().lastPathComponent)
                audioSources.append(source)
                let id = "\(role.rawValue)-\(UUID().uuidString.prefix(8))"
                let start = min(positionUs, max(0, durationUs - 1_000_000))
                let index = audioSources.count - 1
                change { project in
                    project.audio.append(AudioClipState(id: id, source: index, sourceRange: TimeRangeUs(start: 0, end: source.duration),
                                                        timelineStart: start, loop: role == .music, role: role))
                }
                selectedAudioId = id
            } catch {
                showNotice("motion_notice_audio_failed")
            }
            busy = false
        }
    }

    func selectAudio(_ id: String?) {
        selectedAudioId = id
        if id != nil && activeTool != .audio && tools.contains(.audio) {
            if activeTool != nil { applyTool() }
            selectTool(.audio)
            selectedAudioId = id
        }
    }

    func changeSelectedAudio(commit: Bool = true, _ body: (inout AudioClipState) -> Void) {
        guard let id = selectedAudioId else { return }
        change(commit: commit) { project in if let i = project.audio.firstIndex(where: { $0.id == id }) { body(&project.audio[i]) } }
    }

    func deleteSelectedAudio() {
        guard let id = selectedAudioId else { return }
        change { $0.audio.removeAll { $0.id == id } }
        selectedAudioId = nil
    }

    /// 재생 위치에서 소리 조각을 둘로 나눈다. 양쪽 모두 0.2초 이상이어야 한다.
    func splitSelectedAudio() {
        guard let clip = selectedAudio else { return }
        let range = clip.outputRange(videoDuration: durationUs)
        let at = positionUs
        guard at - range.start >= 200_000, range.end - at >= 200_000 else { showNotice("split_unavailable"); return }
        let length = clip.sourceRange.duration
        let elapsed = at - clip.timelineStart
        let firstPass = length - clip.startOffset
        let offset: Micros = elapsed < firstPass ? clip.startOffset + elapsed : (clip.loop ? (elapsed - firstPass) % length : length - 1)
        var first = clip, second = clip
        first.timelineEnd = at
        first.fadeOut = 0
        second.id = "\(clip.role.rawValue)-\(UUID().uuidString.prefix(8))"
        second.timelineStart = at
        second.startOffset = min(max(0, offset), length - 1)
        second.fadeIn = 0
        change { project in
            guard let i = project.audio.firstIndex(where: { $0.id == clip.id }) else { return }
            project.audio.replaceSubrange(i...i, with: [first, second])
        }
        selectedAudioId = second.id
    }

    /// 소리 조각을 끈다. [delta]는 끌기 시작부터의 결과 시간 차이.
    func dragSelectedAudio(by delta: Micros) {
        guard let clip = selectedAudio else { return }
        let start = audioDragStart ?? clip.timelineStart
        audioDragStart = start
        let total = durationUs
        changeSelectedAudio(commit: false) { clip in
            let length = clip.outputRange(videoDuration: total).duration
            let end = clip.timelineEnd.map { $0 - clip.timelineStart }
            clip.timelineStart = min(max(0, start + delta), max(0, total - min(length, 200_000)))
            if let end { clip.timelineEnd = clip.timelineStart + end }
        }
    }

    func endAudioDrag() {
        audioDragStart = nil
        commitGesture()
    }

    // MARK: - 저장

    func requestSave() {
        guard phase == .ready, export == nil else { return }
        if activeTool != nil { applyTool() }
        pause()
        if !request.export.formats.contains(saveFormat) { saveFormat = request.export.formats.first ?? .mp4 }
        showSaveSheet = true
    }

    func save() {
        showSaveSheet = false
        guard phase == .ready, export == nil, let media else { return }
        pause()
        let project = history.current
        let base = request.export.frameRate(for: saveFormat)
        let settings = MotionExportSettings(format: saveFormat, longEdge: saveSize.longEdge(saveFormat),
                                            frameRate: MotionTimeline.frameRate(project, media, base: base), loopCount: request.export.loopCount)
        let audio = audioSources
        // 사진은 저장 크기로 다시 푼다. 움직이는 원본은 미리 보기 프레임을 그대로 쓴다.
        let cache = SlideCache(sources: sources, longEdge: settings.longEdge, capacity: 3)
        let exportMedia = media.kind == .slideshow
            ? MotionMedia(kind: .slideshow, aspects: media.aspects, animation: nil) { cache.image($0) } : media
        export = .running(nil, 0)
        UIApplication.shared.isIdleTimerDisabled = true
        exportTask = Task {
            defer { UIApplication.shared.isIdleTimerDisabled = false }
            do {
                let output = try await Task.detached(priority: .userInitiated) {
                    try await MotionExporter.export(project, media: exportMedia, audio: audio, settings: settings) { fraction in
                        Task { @MainActor in if case .running = self.export { self.export = .running(nil, fraction) } }
                    }
                }.value
                finish(.success(output: output, outputs: [output]))
            } catch is CancellationError {
                export = nil
            } catch {
                export = .failed((error as? EditorError) ?? .logged(.unknown, "motion export: \(error)"))
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
        showSaveSheet = true
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
        isPlaying = false
        playTask?.cancel()
        exportTask?.cancel()
        audioPlayer.pause()
        audioPlayer.replaceCurrentItem(with: nil)
        sources.forEach { $0.close() }
        audioSources.forEach { $0.close() }
        onFinish(result)
    }

    func dismissedWithoutResult() { finish(.cancelled) }

    // MARK: - 필터 작은 그림

    private func makeFilterThumbnails() {
        guard filterThumbnails.isEmpty, let frame else { return }
        let seed = displayed.grainSeed
        let source = UprightImage(cgImage: frame)
        Task {
            filterThumbnails = await Task.detached {
                let scale = Double(Self.thumbnailPx) / Double(max(source.cgImage.width, source.cgImage.height))
                let size = PixelSizeValue(width: max(1, Int(Double(source.cgImage.width) * min(1, scale))),
                                          height: max(1, Int(Double(source.cgImage.height) * min(1, scale))))
                var result: [String: CGImage] = [:]
                for id in FilterCatalog.shared.all.map(\.id) {
                    var edit = PhotoEdit(grainSeed: seed)
                    edit.filterId = id
                    edit.filterIntensity = id == "original" ? 0 : 1
                    if let image = try? PhotoRenderer.render(image: source, uprightSize: PixelSizeValue(width: source.cgImage.width, height: source.cgImage.height),
                                                             edit: edit, crop: .full, output: size, background: nil, includeCanvasEffects: true) {
                        result[id] = image
                    }
                }
                return result
            }.value
        }
    }
}

// MARK: - 색 도구

extension MotionEditorModel: ColorToolHost {
    var cropAspect: CropAspect { .free }
    func setAspect(_: CropAspect) {}
    func rotate(clockwise _: Bool) {}
    func flip(horizontal _: Bool) {}
    var currentGeometry: GeometryState { GeometryState() }
    func setStraighten(_: Double) {}
    func finishStraighten() {}
    func adjustmentValue(_ kind: AdjustKind) -> Double { displayed.color.adjustments[kind] ?? 0 }
    var currentFilterId: String { displayed.color.filterId }
    var currentFilterIntensity: Double { displayed.color.filterIntensity }
    var catalog: EditorCatalog { request.catalog }

    func setAdjustment(_ kind: AdjustKind, display: Double) {
        let value = kind.fromDisplay(display)
        change(commit: false) { $0.color.adjustments[kind] = value == 0 ? nil : value }
    }

    func setFilterIntensity(display: Double) {
        change(commit: false) { $0.color.filterIntensity = min(1, max(0, display / 100)) }
    }

    func selectFilter(_ id: String) {
        change { project in
            if id == "original" {
                project.color.filterId = id
                project.color.filterIntensity = 0
            } else if project.color.filterId != id {
                project.color.filterId = id
                project.color.filterIntensity = 1
            }
        }
    }
}

// MARK: - 텍스트·스티커

extension MotionEditorModel: LayerToolHost {
    var renderedCanvas: CGSize { renderSize }
    var liveStroke: [NPoint]? { nil }
    var canManipulateOverlays: Bool { activeTool == .text || activeTool == .sticker }
    var isDrawingMode: Bool { false }
    var liveStrokeIsShape: Bool { false }
    var liveStrokeStyle: (widthRatio: Double, color: Color, square: Bool) { (0.01, .white, false) }
    var privacyModes: [PrivacySettings.Mode] { [] }
    var watermarkDraft: WatermarkSpec? { nil }
    func setWatermark(_: (inout WatermarkSpec) -> Void) {}
    func removeWatermark() {}
    var hasCommittedWatermark: Bool { false }
    var watermarkSuggestions: [String] { [] }
    var customStickerIds: [String] { StickerAssets.ids }
    func strokeBegan(_: NPoint) {}
    func strokeMoved(_: NPoint) {}
    func strokeEnded() {}

    var selectedOverlay: Overlay? { displayed.overlays.first { $0.overlay.id == selectedOverlayId }?.overlay }

    var editingText: (text: String, style: TextStyle)? {
        guard let overlay = selectedOverlay, case .text(let text, let style) = overlay.content else { return nil }
        return (text, style)
    }

    /// 도구가 없을 때 누르면 재생·멈춤.
    func toggleChrome() { if activeTool == nil { togglePlay() } }

    /// 도구 없이 요소를 누르면 스티커 도구로 고른다.
    func selectOverlayFromIdle(at point: CGPoint) {
        let canvas = renderSize
        let hit = OverlayRenderer.hitTest(displayed.overlays.map(\.overlay), point: CGPoint(x: point.x * canvas.width, y: point.y * canvas.height),
                                          canvas: canvas, slop: 24)
        guard let hit, tools.contains(.sticker) else { togglePlay(); return }
        selectTool(.sticker)
        selectedOverlayId = hit
    }

    func selectOverlay(at point: CGPoint) {
        let canvas = renderSize
        selectedOverlayId = OverlayRenderer.hitTest(displayed.overlays.map(\.overlay), point: CGPoint(x: point.x * canvas.width, y: point.y * canvas.height),
                                                    canvas: canvas, slop: 24)
    }

    func editSelectedText() {
        guard activeTool != .text, tools.contains(.text) else { return }
        let keep = selectedOverlayId
        if activeTool != nil { applyTool() }
        selectTool(.text)
        if let keep, displayed.overlays.contains(where: { $0.overlay.id == keep }) {
            working?.overlays.removeAll { Self.isBlankText($0.overlay) }
            selectedOverlayId = keep
        }
    }

    func beginText() {
        if let overlay = selectedOverlay, overlay.isText { return }
        let id = "text-\(UUID().uuidString.prefix(8))"
        change(commit: false) { $0.overlays.append(MotionOverlay(overlay: Overlay(id: id, content: .text("", TextStyle())))) }
        selectedOverlayId = id
    }

    func setText(_ text: String) {
        mutateSelected { overlay in
            if case .text(_, let style) = overlay.content { overlay.content = .text(text, style) }
        }
    }

    func setTextStyle(_ body: (inout TextStyle) -> Void) {
        mutateSelected { overlay in
            if case .text(let text, var style) = overlay.content {
                body(&style)
                overlay.content = .text(text, style)
            }
        }
    }

    func mutateSelected(_ body: (inout Overlay) -> Void) {
        guard let id = selectedOverlayId else { return }
        change(commit: false) { project in
            guard let i = project.overlays.firstIndex(where: { $0.overlay.id == id }) else { return }
            body(&project.overlays[i].overlay)
            let scale = project.overlays[i].overlay.transform.scale
            project.overlays[i].overlay.transform.scale = min(OverlayTransform.scaleRange.upperBound, max(OverlayTransform.scaleRange.lowerBound, scale))
        }
    }

    func addSticker(_ assetId: String) {
        let id = "sticker-\(UUID().uuidString.prefix(8))"
        change { $0.overlays.append(MotionOverlay(overlay: Overlay(id: id, content: .sticker(assetId: assetId, widthRatio: 0.25)))) }
        selectedOverlayId = id
    }

    func transformSelected(from start: OverlayTransform, move: CGSize, scale: Double, rotation: Double) {
        let canvas = renderSize
        mutateSelected { overlay in
            var t = start
            t.center.x = min(1, max(0, start.center.x + move.width / canvas.width))
            t.center.y = min(1, max(0, start.center.y + move.height / canvas.height))
            t.scale = start.scale * scale
            var degrees = start.rotationDegrees + rotation
            let snapped = (degrees / 90).rounded() * 90
            if abs(degrees - snapped) < 3 { degrees = snapped }
            t.rotationDegrees = degrees.truncatingRemainder(dividingBy: 360)
            overlay.transform = t
        }
    }

    func deleteSelected() {
        guard let id = selectedOverlayId else { return }
        change { $0.overlays.removeAll { $0.overlay.id == id } }
        selectedOverlayId = nil
    }

    func duplicateSelected() {
        guard let id = selectedOverlayId, let item = displayed.overlays.first(where: { $0.overlay.id == id }) else { return }
        var copy = item
        copy.overlay.id = "copy-\(UUID().uuidString.prefix(8))"
        copy.overlay.transform.center.x = min(1, copy.overlay.transform.center.x + 0.04)
        copy.overlay.transform.center.y = min(1, copy.overlay.transform.center.y + 0.04)
        change { $0.overlays.append(copy) }
        selectedOverlayId = copy.overlay.id
    }
}
