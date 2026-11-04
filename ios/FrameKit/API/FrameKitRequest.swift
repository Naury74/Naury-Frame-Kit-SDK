import Foundation

/// 고르거나 찍을 미디어 종류.
public enum MediaKind: String, Sendable, CaseIterable {
    case image
    case video
    /// 사진과 영상. 소리는 들어가지 않는다.
    case any
    case audio
}

/// 여러 편집이 가능할 때 고르는 방식.
public enum EditorChooser: String, Sendable, CaseIterable {
    /// 종류에 맞는 편집기를 바로 연다.
    case auto
    /// 할 일을 묻는 시트를 보인다. 할 수 있는 일이 하나면 묻지 않는다.
    case ask
}

/// 사진과 영상을 섞어 골랐을 때.
public enum MixedSelection: String, Sendable, CaseIterable {
    /// 혼합 편집 화면을 연다. 아래 줄에서 고른 항목에 맞는 편집 화면을 보이고, 저장은 한 번에 한다(최대 20개).
    case edit
    /// 열지 않는다. `.auto`에서는 `unsupportedOperation`, `.ask`에서는 원본 보내기만 보인다.
    case reject
}

/// 고르기 시트의 할 일.
public enum EditorAction: String, Sendable, CaseIterable {
    case editPhoto
    /// 섞어 고른 사진·영상을 하나씩 편집한다.
    case editEach
    /// 사진들을 PDF 하나로 묶는다.
    case makePDF
    /// 움직이는 이미지를 만들거나 편집한다.
    case motion
    case editVideo
    case editAudio
    /// 편집하지 않고 원본을 돌려준다(`EditedMedia.isOriginal == true`).
    case sendOriginal
}

/// 하나의 진입점으로 사진·영상·움직이는 이미지·소리를 여는 요청. 원본 종류에 맞는 편집기로 보낸다.
///
/// - `.pick`·`.pickMultiple`·`.capture`는 [mediaKind]를 따른다. `.capture`에는 `.any`를 쓸 수 없다.
/// - 사진과 영상을 섞으면 [mixedSelection]을 따른다. 소리는 다른 종류와 섞을 수 없다.
/// - 결과는 각 편집기 결과와 같다.
public struct FrameKitRequest: Sendable, Hashable {
    public var input: EditorInput
    public var mediaKind: MediaKind
    public var image: ImageEditorConfig
    public var imageExport: ImageExportConfig
    public var video: VideoEditorConfig
    public var videoExport: VideoExportConfig
    public var motion: MotionEditorConfig
    public var motionExport: MotionExportConfig
    public var audio: AudioEditorConfig
    public var audioExport: AudioExportConfig
    public var ui: EditorUIConfig
    public var catalog: EditorCatalog
    /// 사진 한 장이 움직이는 이미지면 움직이는 이미지 편집기로 연다.
    public var openAnimatedInMotionEditor: Bool
    public var chooser: EditorChooser
    /// 고르기 시트에 보일 할 일. 비어 있으면 안 된다.
    public var chooserActions: Set<EditorAction>
    public var mixedSelection: MixedSelection

    /// 고르기 한 번에 고를 수 있는 최대 수.
    public static let maxPickItems = 100

    public init(input: EditorInput, mediaKind: MediaKind = .any, image: ImageEditorConfig = ImageEditorConfig(),
                imageExport: ImageExportConfig = ImageExportConfig(), video: VideoEditorConfig = VideoEditorConfig(),
                videoExport: VideoExportConfig = VideoExportConfig(), motion: MotionEditorConfig = MotionEditorConfig(),
                motionExport: MotionExportConfig = MotionExportConfig(), audio: AudioEditorConfig = AudioEditorConfig(),
                audioExport: AudioExportConfig = AudioExportConfig(), ui: EditorUIConfig = EditorUIConfig(),
                catalog: EditorCatalog = EditorCatalog(), openAnimatedInMotionEditor: Bool = true, chooser: EditorChooser = .auto,
                chooserActions: Set<EditorAction> = Set(EditorAction.allCases), mixedSelection: MixedSelection = .edit) {
        self.input = input
        self.mediaKind = mediaKind
        self.image = image
        self.imageExport = imageExport
        self.video = video
        self.videoExport = videoExport
        self.motion = motion
        self.motionExport = motionExport
        self.audio = audio
        self.audioExport = audioExport
        self.ui = ui
        self.catalog = catalog
        self.openAnimatedInMotionEditor = openAnimatedInMotionEditor
        self.chooser = chooser
        self.chooserActions = chooserActions
        self.mixedSelection = mixedSelection
    }

    /// 설정 오류 목록. 각 편집기 설정도 함께 검사한다.
    public func validate() -> [ConfigurationIssue] {
        var issues: [ConfigurationIssue] = []
        switch input {
        case .pickMultiple(let max) where !(1...Self.maxPickItems).contains(max): issues.append(.init(path: "input", reason: .outOfRange))
        case .files(let urls) where urls.isEmpty || urls.contains(where: { !$0.isFileURL }): issues.append(.init(path: "input", reason: .invalidValue))
        case .file(let url) where !url.isFileURL: issues.append(.init(path: "input", reason: .invalidValue))
        case .data(let data) where data.isEmpty: issues.append(.init(path: "input", reason: .invalidValue))
        case .capture where mediaKind == .any: issues.append(.init(path: "mediaKind", reason: .invalidValue))
        default: break
        }
        if chooserActions.isEmpty { issues.append(.init(path: "chooserActions", reason: .invalidValue)) }
        func prefixed(_ list: [ConfigurationIssue], _ prefix: String) -> [ConfigurationIssue] {
            list.filter { !$0.path.hasPrefix("input") }.map { .init(path: "\(prefix).\($0.path)", reason: $0.reason) }
        }
        issues += prefixed(imageRequest(.pick).validate(), "image")
        issues += prefixed(videoRequest(.pick).validate(), "video")
        issues += prefixed(motionRequest(.pick).validate(), "motion")
        issues += prefixed(audioRequest(.pick).validate(), "audio")
        // 같은 ui·catalog 오류가 네 번 나오지 않게 한 번만 남긴다.
        var seen = Set<String>()
        return issues.filter { issue in
            let key = issue.path.replacingOccurrences(of: #"^(image|video|motion|audio)\.(ui|catalog)"#, with: "$2", options: .regularExpression)
            return seen.insert(key + issue.reason.rawValue).inserted
        }
    }

    func imageRequest(_ input: EditorInput, pdf: Bool = false) -> ImageEditorRequest {
        var export = imageExport
        if pdf { export.format = .pdf }
        return ImageEditorRequest(input: input, config: image, export: export, ui: ui, catalog: catalog)
    }

    func videoRequest(_ input: EditorInput) -> VideoEditorRequest {
        VideoEditorRequest(input: input, config: video, export: videoExport, ui: ui, catalog: catalog)
    }

    func motionRequest(_ input: EditorInput) -> MotionEditorRequest {
        MotionEditorRequest(input: input, config: motion, export: motionExport, ui: ui, catalog: catalog)
    }

    func audioRequest(_ input: EditorInput) -> AudioEditorRequest {
        AudioEditorRequest(input: input, config: audio, export: audioExport, ui: ui)
    }
}

extension FrameKitRequest {
    /// 소리 녹음만 하는 요청이라 녹음 시트로 띄운다.
    var usesRecorderSheet: Bool { mediaKind == .audio && audio.usesRecorderSheet(input) }
}
