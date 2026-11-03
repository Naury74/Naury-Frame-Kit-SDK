import Foundation

/// 영상 편집 도구.
public enum VideoTool: String, Sendable, CaseIterable {
    /// 클립 구간 고르기. 타임라인 손잡이로도 할 수 있다.
    case trim
    case crop
    case rotate
    /// 결과 화면 비율과 맞추기·채우기.
    case canvas
    case adjust
    case filter
    /// 0.25~4배.
    case speed
    /// 원본 소리 볼륨·음소거, 음악·녹음 추가.
    case audio
    case text
    case sticker
    /// 시간 범위가 있는 모자이크·흐림(사각형·원).
    case privacy
    case watermark

    public static let defaults: [VideoTool] = allCases

    /// 적용·취소가 있는 도구.
    var isDraft: Bool { [.trim, .crop, .rotate, .speed, .text, .watermark].contains(self) }
    var isGeometry: Bool { self == .crop || self == .rotate }
}

/// 영상 편집기 동작 설정. 시간은 초 단위다.
public struct VideoEditorConfig: Sendable, Hashable {
    public var enabledTools: [VideoTool]
    public var allowUndo: Bool
    public var allowRedo: Bool
    /// 자르기·속도 뒤 클립 하나의 최소 길이(초), 0.1 이상. 이보다 짧은 원본은 열지 않는다.
    public var minClipDuration: TimeInterval
    /// 결과 전체 최대 길이(초), `minClipDuration`...3600. 더 긴 원본은 앞부분만 쓴다.
    public var maxTimelineDuration: TimeInterval
    /// 클립 수 상한, 1...20. 1이면 클립 추가·나누기가 숨는다.
    public var maxClipCount: Int
    public var watermark: WatermarkConfig?

    public static let maxClips = 20

    public init(enabledTools: [VideoTool] = VideoTool.defaults, allowUndo: Bool = true, allowRedo: Bool = true,
                minClipDuration: TimeInterval = 1, maxTimelineDuration: TimeInterval = 600, maxClipCount: Int = 10,
                watermark: WatermarkConfig? = nil) {
        self.enabledTools = enabledTools
        self.allowUndo = allowUndo
        self.allowRedo = allowRedo
        self.minClipDuration = minClipDuration
        self.maxTimelineDuration = maxTimelineDuration
        self.maxClipCount = maxClipCount
        self.watermark = watermark
    }
}

/// 영상 저장 설정. 결과는 H.264 + AAC MP4(SDR)다.
public struct VideoExportConfig: Sendable, Hashable {
    /// 짧은 변 상한(px), 144...2160. 원본보다 키우지 않는다. 긴 변은 4096을 넘지 않는다.
    public var maxShortSide: Int
    /// 초당 프레임 상한, 1...60. 넘으면 프레임을 줄이고 `frameRateReduced` 경고를 붙인다.
    public var maxFrameRate: Int

    public init(maxShortSide: Int = 1080, maxFrameRate: Int = 30) {
        self.maxShortSide = maxShortSide
        self.maxFrameRate = maxFrameRate
    }
}

/// 영상 편집기 요청. `input`은 `.pick`·`.pickMultiple`(영상만 보임), `.file`·`.files`, `.capture`(영상 촬영)를 쓴다.
public struct VideoEditorRequest: Sendable, Hashable {
    public var input: EditorInput
    public var config: VideoEditorConfig
    public var export: VideoExportConfig
    public var ui: EditorUIConfig
    public var catalog: EditorCatalog

    public init(input: EditorInput, config: VideoEditorConfig = VideoEditorConfig(), export: VideoExportConfig = VideoExportConfig(),
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
        case .data: issues.append(.init(path: "input", reason: .invalidValue))
        case .pickMultiple(let max) where !(1...config.maxClipCount).contains(max): issues.append(.init(path: "input", reason: .outOfRange))
        case .files(let urls) where urls.isEmpty || urls.count > config.maxClipCount || urls.contains(where: { !$0.isFileURL }):
            issues.append(.init(path: "input", reason: .invalidValue))
        case .file(let url) where !url.isFileURL: issues.append(.init(path: "input", reason: .invalidValue))
        default: break
        }
        if config.allowRedo && !config.allowUndo { issues.append(.init(path: "config.allowRedo", reason: .requiresOther)) }
        if !(config.minClipDuration >= 0.1) { issues.append(.init(path: "config.minClipDuration", reason: .outOfRange)) }
        if !(config.minClipDuration...3600).contains(config.maxTimelineDuration) {
            issues.append(.init(path: "config.maxTimelineDuration", reason: .outOfRange))
        }
        if !(1...VideoEditorConfig.maxClips).contains(config.maxClipCount) { issues.append(.init(path: "config.maxClipCount", reason: .outOfRange)) }
        if let watermark = config.watermark { issues += watermark.issues(path: "config.watermark") }
        if !(144...2160).contains(export.maxShortSide) { issues.append(.init(path: "export.maxShortSide", reason: .outOfRange)) }
        if !(1...60).contains(export.maxFrameRate) { issues.append(.init(path: "export.maxFrameRate", reason: .outOfRange)) }
        issues += ui.issues(catalog: catalog)
        issues += catalog.issues()
        return issues
    }
}
