import Foundation

/// 움직이는 이미지 편집 도구.
public enum MotionTool: String, Sendable, CaseIterable {
    /// 사진마다 천천히 확대·이동. 슬라이드쇼에서만 보인다.
    case motion
    /// 사진 사이 전환. 사진이 2장 이상인 슬라이드쇼에서만 보인다.
    case transition
    /// 슬라이드쇼는 사진당 시간, 움직이는 원본은 속도와 구간.
    case timing
    /// 반복·부메랑.
    case loop
    /// 화면 비율, 채우기·맞추기, 배경 색.
    case canvas
    case adjust
    case filter
    case text
    case sticker
    /// 음악·음성. MP4로 저장할 때만 담긴다.
    case audio

    public static let defaults: [MotionTool] = allCases
}

/// 움직이는 이미지 편집기 동작 설정.
public struct MotionEditorConfig: Sendable, Hashable {
    public var enabledTools: [MotionTool]
    /// 슬라이드쇼 사진 수 상한, 1...50.
    public var maxImageCount: Int
    /// 사진당 처음 시간(초), 0.3...10.
    public var slideDuration: TimeInterval
    /// 움직이는 원본에서 읽을 프레임 수 상한, 10...1000. 넘는 프레임은 버린다.
    public var maxAnimationFrames: Int
    public var allowUndo: Bool
    public var allowRedo: Bool

    public static let maxImages = 50

    public init(enabledTools: [MotionTool] = MotionTool.defaults, maxImageCount: Int = 20, slideDuration: TimeInterval = 2,
                maxAnimationFrames: Int = 300, allowUndo: Bool = true, allowRedo: Bool = true) {
        self.enabledTools = enabledTools
        self.maxImageCount = maxImageCount
        self.slideDuration = slideDuration
        self.maxAnimationFrames = maxAnimationFrames
        self.allowUndo = allowUndo
        self.allowRedo = allowRedo
    }
}

/// 저장 형식.
public enum MotionFormat: String, Sendable, CaseIterable {
    /// H.264 MP4. 소리를 담을 수 있다.
    case mp4
    /// GIF(프레임마다 256색). 소리는 담기지 않는다.
    case gif

    var mimeType: String { self == .mp4 ? "video/mp4" : "image/gif" }
}

/// 저장 크기(긴 변). MP4 540·720·1080px, GIF 240·360·480px.
public enum MotionSize: String, Sendable, CaseIterable {
    case small, medium, large

    public func longEdge(_ format: MotionFormat) -> Int {
        switch (format, self) {
        case (.mp4, .small): 540
        case (.mp4, .medium): 720
        case (.mp4, .large): 1080
        case (.gif, .small): 240
        case (.gif, .medium): 360
        case (.gif, .large): 480
        }
    }
}

/// 저장 설정. 사용자는 저장 시트에서 형식·크기를 바꿀 수 있다.
public struct MotionExportConfig: Sendable, Hashable {
    /// 저장 시트에 보일 형식. 비어 있으면 안 된다.
    public var formats: [MotionFormat]
    /// 처음 고른 형식. `formats` 안에 있어야 한다.
    public var format: MotionFormat
    public var size: MotionSize
    /// 초당 프레임, 5...60. `nil`이면 MP4 30, GIF 15. 움직이는 원본은 원본보다 높이지 않는다.
    public var frameRate: Int?
    /// GIF 반복 횟수, 0...65535. 0은 무한 반복.
    public var loopCount: Int

    public init(formats: [MotionFormat] = MotionFormat.allCases, format: MotionFormat = .mp4, size: MotionSize = .medium,
                frameRate: Int? = nil, loopCount: Int = 0) {
        self.formats = formats
        self.format = format
        self.size = size
        self.frameRate = frameRate
        self.loopCount = loopCount
    }

    func frameRate(for format: MotionFormat) -> Int { frameRate ?? (format == .mp4 ? 30 : 15) }
}

/// 움직이는 이미지 편집기 요청.
///
/// 사진 한 장이 움직이는 GIF·WebP·PNG면 그 원본을 편집하고, 그 밖에는 사진들로 슬라이드쇼를 만든다.
/// `input`은 `.pick`·`.pickMultiple`, `.file`·`.files`, `.data`를 쓴다. `.capture`는 쓸 수 없다.
public struct MotionEditorRequest: Sendable, Hashable {
    public var input: EditorInput
    public var config: MotionEditorConfig
    public var export: MotionExportConfig
    public var ui: EditorUIConfig
    public var catalog: EditorCatalog

    public init(input: EditorInput, config: MotionEditorConfig = MotionEditorConfig(), export: MotionExportConfig = MotionExportConfig(),
                ui: EditorUIConfig = EditorUIConfig(), catalog: EditorCatalog = EditorCatalog()) {
        self.input = input
        self.config = config
        self.export = export
        self.ui = ui
        self.catalog = catalog
    }

    /// 설정 오류 목록. 비어 있으면 열 수 있다.
    public func validate() -> [ConfigurationIssue] {
        var issues: [ConfigurationIssue] = []
        switch input {
        case .capture: issues.append(.init(path: "input", reason: .invalidValue))
        case .pickMultiple(let max) where !(1...config.maxImageCount).contains(max): issues.append(.init(path: "input", reason: .outOfRange))
        case .files(let urls) where urls.isEmpty || urls.count > config.maxImageCount || urls.contains(where: { !$0.isFileURL }):
            issues.append(.init(path: "input", reason: .invalidValue))
        case .file(let url) where !url.isFileURL: issues.append(.init(path: "input", reason: .invalidValue))
        case .data(let data) where data.isEmpty: issues.append(.init(path: "input", reason: .invalidValue))
        default: break
        }
        if config.allowRedo && !config.allowUndo { issues.append(.init(path: "config.allowRedo", reason: .requiresOther)) }
        if !(1...MotionEditorConfig.maxImages).contains(config.maxImageCount) { issues.append(.init(path: "config.maxImageCount", reason: .outOfRange)) }
        if !(0.3...10).contains(config.slideDuration) { issues.append(.init(path: "config.slideDuration", reason: .outOfRange)) }
        if !(10...1000).contains(config.maxAnimationFrames) { issues.append(.init(path: "config.maxAnimationFrames", reason: .outOfRange)) }
        if export.formats.isEmpty { issues.append(.init(path: "export.formats", reason: .invalidValue)) }
        if !export.formats.contains(export.format) { issues.append(.init(path: "export.format", reason: .requiresOther)) }
        if let rate = export.frameRate, !(5...60).contains(rate) { issues.append(.init(path: "export.frameRate", reason: .outOfRange)) }
        if !(0...0xFFFF).contains(export.loopCount) { issues.append(.init(path: "export.loopCount", reason: .outOfRange)) }
        issues += ui.issues(catalog: catalog)
        issues += catalog.issues()
        return issues
    }
}
