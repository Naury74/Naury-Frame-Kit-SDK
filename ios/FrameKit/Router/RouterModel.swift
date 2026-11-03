import Observation
import SwiftUI
import UniformTypeIdentifiers

/// 하나의 진입점. 원본을 고르고, 종류를 보고, 알맞은 편집기를 연다.
@MainActor
@Observable
final class RouterModel {
    enum Editor {
        case image(EditorModel), video(VideoEditorModel), motion(MotionEditorModel), audio(AudioEditorModel), mixed(MixedEditorModel)
    }

    struct Choice {
        let items: [URL]
        let kinds: [SourceKind]
        let actions: [EditorAction]
        let mixed: Bool
    }

    enum Phase {
        case starting, picking(MediaKind, Int), working, chooser(Choice), editor(Editor)
    }

    static let thumbnailPx = 192
    static let maxThumbnails = 6

    let request: FrameKitRequest
    private let onFinish: (FrameKitResult) -> Void
    private var delivered = false
    private(set) var phase = Phase.starting
    private(set) var thumbnails: [Int: CGImage] = [:]
    /// 진입점이 만든 임시 파일. 끝나면 지운다.
    private var temporary: [URL] = []
    /// 연 편집기가 마지막으로 적용한 편집을 남기는 곳. 사진과 영상을 함께 편집하면 남기지 않는다.
    private let session: EditSession?

    init(request: FrameKitRequest, onFinish: @escaping (FrameKitResult) -> Void) {
        self.request = request
        self.onFinish = onFinish
        session = EditSession(slot: "router", input: request.input)
    }

    func start() {
        guard case .starting = phase else { return }
        let issues = request.validate()
        guard issues.isEmpty else {
            frameKitLog.error("invalid FrameKit request: \(issues.map(\.description).joined(separator: ", "), privacy: .public)")
            finish(.failure(EditorError(code: .invalidConfiguration)))
            return
        }
        do {
            try CatalogInstaller.install(request.catalog)
        } catch {
            finish(.failure(error))
            return
        }
        if let stored = session?.storedKind, resume(stored) { return }
        openInput()
    }

    private func openInput() {
        let kind = request.mediaKind
        switch request.input {
        case .capture:
            switch kind {
            case .image: open(.image(EditorModel(request: request.imageRequest(.capture), session: session(.image), onFinish: finishEditor)))
            case .video: open(.video(VideoEditorModel(request: request.videoRequest(.capture), session: session(.video), onFinish: finishEditor)))
            case .audio: open(.audio(AudioEditorModel(request: request.audioRequest(.capture), session: session(.audio), onFinish: finishEditor)))
            case .any: finish(.failure(EditorError(code: .invalidConfiguration)))
            }
        case .pick, .pickMultiple:
            let limit = if case .pickMultiple(let max) = request.input { max } else { 1 }
            // 묻기·사진과 영상·움직이는 이미지 판별이 필요하면 진입점이 직접 고른다.
            if request.chooser == .ask || kind == .any || (kind == .image && limit == 1 && request.openAnimatedInMotionEditor) {
                phase = .picking(kind, limit)
            } else {
                delegatePick(kind, limit: limit)
            }
        case .file(let url): route([url])
        case .files(let urls): route(urls)
        case .data(let data):
            do {
                let url = try MotionEditorModel.writeTemporary(data)
                temporary.append(url)
                route([url])
            } catch {
                finish(.failure(error))
            }
        }
    }

    private func delegatePick(_ kind: MediaKind, limit: Int) {
        func input(_ max: Int) -> EditorInput { min(limit, max) <= 1 ? .pick : .pickMultiple(maxItems: min(limit, max)) }
        switch kind {
        case .image: open(.image(EditorModel(request: request.imageRequest(input(request.image.maxImageCount)), session: session(.image), onFinish: finishEditor)))
        case .video: open(.video(VideoEditorModel(request: request.videoRequest(input(request.video.maxClipCount)), session: session(.video), onFinish: finishEditor)))
        case .audio: open(.audio(AudioEditorModel(request: request.audioRequest(input(request.audio.maxSourceCount)), session: session(.audio), onFinish: finishEditor)))
        case .any: phase = .picking(kind, limit)
        }
    }

    /// 진입점 선택기 결과. 비어 있으면 닫은 것이다.
    func picked(_ urls: [URL]) {
        temporary += urls
        guard !urls.isEmpty else { finish(.cancelled); return }
        route(urls)
    }

    private func route(_ urls: [URL]) {
        phase = .working
        Task {
            var kinds: [SourceKind] = []
            for url in urls {
                guard let kind = await SourceInspector.kind(url) else {
                    finish(.failure(.logged(urls.count > 1 ? .unsupportedOperation : .unsupportedFormat, "unknown source kind")))
                    return
                }
                kinds.append(kind)
            }
            if request.chooser == .ask { showChooser(urls, kinds) } else { openAutomatically(urls, kinds) }
        }
    }

    private func openAutomatically(_ urls: [URL], _ kinds: [SourceKind]) {
        let groups = Set(kinds.map(\.mediaKind))
        guard groups.count == 1, let group = groups.first else {
            if groups == [.image, .video] && request.mixedSelection == .edit {
                perform(.editEach, urls, kinds)
            } else {
                finish(.failure(.logged(.unsupportedOperation, "mixed media kinds")))
            }
            return
        }
        switch group {
        case .image:
            if urls.count == 1 && kinds[0] == .animated && request.openAnimatedInMotionEditor { perform(.motion, urls, kinds) }
            else { perform(.editPhoto, urls, kinds) }
        case .video: perform(.editVideo, urls, kinds)
        case .audio: perform(.editAudio, urls, kinds)
        case .any: finish(.failure(.logged(.unsupportedFormat, "any")))
        }
    }

    private func showChooser(_ urls: [URL], _ kinds: [SourceKind]) {
        let (actions, mixed) = SourceInspector.actions(kinds, allowed: request.chooserActions, mixedEdit: request.mixedSelection == .edit)
        if actions.isEmpty {
            if mixed { finish(.failure(.logged(.unsupportedOperation, "no action for mixed media"))) } else { openAutomatically(urls, kinds) }
            return
        }
        if actions.count == 1 { perform(actions[0], urls, kinds); return }
        phase = .chooser(Choice(items: urls, kinds: kinds, actions: actions, mixed: mixed))
        for (i, url) in urls.prefix(Self.maxThumbnails).enumerated() {
            let kind = kinds[i]
            Task { thumbnails[i] = await SourceInspector.thumbnail(url, kind: kind, maxSide: Self.thumbnailPx) }
        }
    }

    func choose(_ action: EditorAction) {
        guard case .chooser(let choice) = phase else { return }
        perform(action, choice.items, choice.kinds)
    }

    private func perform(_ action: EditorAction, _ urls: [URL], _ kinds: [SourceKind]) {
        func files(_ limit: Int) -> EditorInput {
            let chosen = Array(urls.prefix(max(1, limit)))
            return chosen.count == 1 ? .file(chosen[0]) : .files(chosen)
        }
        switch action {
        case .editPhoto: open(.image(EditorModel(request: request.imageRequest(files(request.image.maxImageCount)), session: session(.image), onFinish: finishEditor)))
        case .makePDF: open(.image(EditorModel(request: request.imageRequest(files(request.image.maxImageCount), pdf: true), session: session(.pdf), onFinish: finishEditor)))
        case .motion: open(.motion(MotionEditorModel(request: request.motionRequest(files(request.motion.maxImageCount)), session: session(.motion), onFinish: finishEditor)))
        case .editVideo: open(.video(VideoEditorModel(request: request.videoRequest(files(request.video.maxClipCount)), session: session(.video), onFinish: finishEditor)))
        case .editAudio: open(.audio(AudioEditorModel(request: request.audioRequest(files(request.audio.maxSourceCount)), session: session(.audio), onFinish: finishEditor)))
        case .editEach: open(.mixed(MixedEditorModel(request: request, urls: urls, kinds: kinds, onFinish: finishEditor)))
        case .sendOriginal:
            phase = .working
            Task {
                do {
                    var outputs: [EditedMedia] = []
                    for (url, kind) in zip(urls, kinds) { outputs.append(try await SourceInspector.original(url, kind: kind)) }
                    guard let first = outputs.first else { throw EditorError.logged(.sourceUnavailable, "no original") }
                    finish(.success(output: first, outputs: outputs))
                } catch {
                    finish(.failure((error as? EditorError) ?? .logged(.sourceUnavailable, "original: \(error)")))
                }
            }
        }
    }

    private func open(_ editor: Editor) { phase = .editor(editor) }

    // MARK: - 세션

    private enum SessionEditor: String { case image, pdf, video, motion, audio }

    /// 편집기에 넘길 세션. 남길 때 어느 편집기였는지 함께 적는다.
    private func session(_ editor: SessionEditor) -> EditSession? {
        session?.kind = editor.rawValue
        return session
    }

    /// 남은 세션의 편집기를 바로 연다. 그 편집기가 이어 열지 못하면 요청 입력으로 처음부터 연다.
    private func resume(_ stored: String) -> Bool {
        guard let editor = SessionEditor(rawValue: stored) else { session?.discard(); return false }
        let restart: () -> Void = { [weak self] in self?.openInput() }
        switch editor {
        case .image, .pdf:
            let model = EditorModel(request: request.imageRequest(.pick, pdf: editor == .pdf), session: session(editor), onFinish: finishEditor)
            model.onRestoreFailed = restart
            open(.image(model))
        case .video:
            let model = VideoEditorModel(request: request.videoRequest(.pick), session: session(editor), onFinish: finishEditor)
            model.onRestoreFailed = restart
            open(.video(model))
        case .motion:
            let model = MotionEditorModel(request: request.motionRequest(.pick), session: session(editor), onFinish: finishEditor)
            model.onRestoreFailed = restart
            open(.motion(model))
        case .audio:
            let model = AudioEditorModel(request: request.audioRequest(.pick), session: session(editor), onFinish: finishEditor)
            model.onRestoreFailed = restart
            open(.audio(model))
        }
        return true
    }

    private func finishEditor(_ result: FrameKitResult) { finish(result) }

    func cancelChooser() { finish(.cancelled) }

    func finish(_ result: FrameKitResult) {
        guard !delivered else { return }
        delivered = true
        session?.discard()
        temporary.forEach { try? FileManager.default.removeItem(at: $0) }
        onFinish(result)
    }

    /// 호스트가 결과 전에 화면을 내렸다.
    func dismissedWithoutResult() {
        if case .editor(let editor) = phase {
            switch editor {
            case .image(let model): model.dismissedWithoutResult()
            case .video(let model): model.dismissedWithoutResult()
            case .motion(let model): model.dismissedWithoutResult()
            case .audio(let model): model.dismissedWithoutResult()
            case .mixed(let model): model.finish(.cancelled)
            }
        }
        finish(.cancelled)
    }
}
