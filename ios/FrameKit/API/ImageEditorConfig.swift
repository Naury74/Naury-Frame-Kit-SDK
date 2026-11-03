import Foundation

/// 사진 편집 도구.
public enum ImageTool: String, Sendable, CaseIterable {
    /// 자유·비율 고정 자르기.
    case crop
    /// 90° 회전, 좌우·상하 반전, 수평 맞추기(±45°).
    case rotate
    /// 12가지 보정.
    case adjust
    /// 필터와 강도.
    case filter
    /// 글자 넣기.
    case text
    /// 이모지와 호스트 스티커.
    case sticker
    /// 펜·마커·형광펜·지우개·도형.
    case draw
    /// 모자이크·흐림으로 가리기.
    case privacy
    /// 워터마크. 호스트 워터마크가 `locked`면 보이지 않는다.
    case watermark

    /// 기본 도구 순서.
    public static let defaults: [ImageTool] = [.crop, .rotate, .adjust, .filter, .text, .sticker, .draw, .privacy, .watermark]

    /// 적용·취소가 있는 도구. 저장 전에 적용해야 한다.
    var isDraft: Bool { self == .crop || self == .rotate || self == .text || self == .watermark }
}

/// 사진 편집기 동작 설정.
public struct ImageEditorConfig: Sendable, Hashable {
    /// 보여 줄 도구와 순서. 비우면 미리 보기와 저장만 된다. 중복은 무시한다.
    public var enabledTools: [ImageTool]
    /// 실행 취소 단추를 보인다.
    public var allowUndo: Bool
    /// 다시 실행 단추를 보인다. `allowUndo`가 `true`여야 한다.
    public var allowRedo: Bool
    /// 호스트가 정하는 워터마크. `nil`이면 사용자가 워터마크 도구로 직접 넣는다.
    public var watermark: WatermarkConfig?

    public init(enabledTools: [ImageTool] = ImageTool.defaults, allowUndo: Bool = true, allowRedo: Bool = true,
                watermark: WatermarkConfig? = nil) {
        self.enabledTools = enabledTools
        self.allowUndo = allowUndo
        self.allowRedo = allowRedo
        self.watermark = watermark
    }
}

/// 워터마크 무늬.
public enum WatermarkStyle: String, Sendable, CaseIterable {
    /// 한 곳에 하나.
    case single
    /// 기울여 반복.
    case tile
    /// 대각선으로 크게.
    case diagonal
    /// 위나 아래 띠.
    case band
}

/// 워터마크 위치. `band`는 위·아래만 쓴다.
public enum WatermarkAnchor: String, Sendable, CaseIterable {
    case topStart, topCenter, topEnd, centerStart, center, centerEnd, bottomStart, bottomCenter, bottomEnd
}

/// 호스트가 정하는 워터마크.
public struct WatermarkConfig: Sendable, Hashable {
    /// 한 줄, 1...60자. 비우면 사용자가 입력한다.
    public var text: String
    public var style: WatermarkStyle
    public var anchor: WatermarkAnchor
    /// 짧은 변 대비 글자 크기, 0.015...0.2.
    public var sizeRatio: Double
    /// 0.05...1.
    public var opacity: Double
    /// 글자색(0xAARRGGBB).
    public var colorARGB: UInt32
    /// 반복 무늬의 기울기(도), -90...90.
    public var rotationDegrees: Double
    /// 반복 무늬의 촘촘함, 0.5...2.
    public var density: Double
    /// 글자 뒤 상자.
    public var background: Bool
    /// 편집기를 열 때 바로 넣는다.
    public var applyOnOpen: Bool
    /// 사용자가 바꾸거나 지울 수 없다. `applyOnOpen`이 `true`이고 `text`가 있어야 한다.
    public var locked: Bool
    /// 워터마크 도구에서 고를 수 있는 문구, 최대 8개.
    public var suggestions: [String]

    public init(
        text: String = "",
        style: WatermarkStyle = .single,
        anchor: WatermarkAnchor = .bottomEnd,
        sizeRatio: Double = 0.04,
        opacity: Double = 0.7,
        colorARGB: UInt32 = 0xFFFF_FFFF,
        rotationDegrees: Double = -30,
        density: Double = 1,
        background: Bool = false,
        applyOnOpen: Bool = false,
        locked: Bool = false,
        suggestions: [String] = []
    ) {
        self.text = text
        self.style = style
        self.anchor = anchor
        self.sizeRatio = sizeRatio
        self.opacity = opacity
        self.colorARGB = colorARGB
        self.rotationDegrees = rotationDegrees
        self.density = density
        self.background = background
        self.applyOnOpen = applyOnOpen
        self.locked = locked
        self.suggestions = suggestions
    }

    static let maxSuggestions = 8

    var spec: WatermarkSpec {
        WatermarkSpec(text: text.replacingOccurrences(of: "\n", with: " ").trimmingCharacters(in: .whitespaces),
                      pattern: WatermarkPattern(rawValue: style.rawValue) ?? .single,
                      position: WatermarkPosition(rawValue: anchor.rawValue) ?? .bottomEnd,
                      sizeRatio: sizeRatio, opacity: opacity, color: colorARGB, rotationDegrees: rotationDegrees,
                      density: density, shadow: true, background: background)
    }

    func issues(path: String) -> [ConfigurationIssue] {
        var issues: [ConfigurationIssue] = []
        if text.count > WatermarkSpec.maxTextLength || text.contains("\n") { issues.append(.init(path: "\(path).text", reason: .invalidValue)) }
        if !(0.015...0.2).contains(sizeRatio) { issues.append(.init(path: "\(path).sizeRatio", reason: .outOfRange)) }
        if !(0.05...1).contains(opacity) { issues.append(.init(path: "\(path).opacity", reason: .outOfRange)) }
        if !(-90...90).contains(rotationDegrees) { issues.append(.init(path: "\(path).rotationDegrees", reason: .outOfRange)) }
        if !(0.5...2).contains(density) { issues.append(.init(path: "\(path).density", reason: .outOfRange)) }
        if suggestions.count > Self.maxSuggestions { issues.append(.init(path: "\(path).suggestions", reason: .outOfRange)) }
        if locked && (!applyOnOpen || text.trimmingCharacters(in: .whitespaces).isEmpty) {
            issues.append(.init(path: "\(path).locked", reason: .requiresOther))
        }
        return issues
    }
}

/// 저장 형식.
public enum ImageFormat: String, Sendable, CaseIterable {
    /// 투명 영역은 `ImageExportConfig.jpegBackground`로 채운다.
    case jpeg
    /// 무손실, 투명 유지. `quality`는 무시한다.
    case png
    /// HEIC(HEVC). 투명 유지.
    case heic

    public var mimeType: String {
        switch self {
        case .jpeg: "image/jpeg"
        case .png: "image/png"
        case .heic: "image/heic"
        }
    }

    public var fileExtension: String {
        switch self {
        case .jpeg: "jpg"
        case .png: "png"
        case .heic: "heic"
        }
    }

    var supportsAlpha: Bool { self != .jpeg }
}

/// 원본 메타데이터를 얼마나 옮길지. JPEG·HEIC에만 쓴다. 방향은 항상 '정상'으로, 크기는 결과 크기로 쓰고 원본 썸네일은 버린다.
public enum MetadataPolicy: String, Sendable {
    /// 촬영 시각과 카메라 설정만 옮긴다.
    case safe
    /// 원본 메타데이터를 쓰지 않는다.
    case none
    /// `safe`에 위치(GPS)·일련번호·저작권·렌즈 정보를 더한다.
    case all
}

/// 저장 설정.
public struct ImageExportConfig: Sendable, Hashable {
    public var format: ImageFormat
    /// 0...100. PNG는 무시한다.
    public var quality: Int
    /// 결과 너비 상한(px). `nil`이면 제한 없음. 양수여야 한다.
    public var maxWidth: Int?
    /// 결과 높이 상한(px). `nil`이면 제한 없음. 양수여야 한다.
    public var maxHeight: Int?
    /// 결과 픽셀 수 상한. 1...100,000,000. 자른 크기보다 키우지 않는다.
    public var maxOutputPixels: Int
    public var metadataPolicy: MetadataPolicy
    /// JPEG의 투명 영역을 채울 색(0xAARRGGBB). 알파는 무시한다.
    public var jpegBackgroundARGB: UInt32

    public init(
        format: ImageFormat = .jpeg,
        quality: Int = 92,
        maxWidth: Int? = nil,
        maxHeight: Int? = nil,
        maxOutputPixels: Int = 16_000_000,
        metadataPolicy: MetadataPolicy = .safe,
        jpegBackgroundARGB: UInt32 = 0xFF00_0000
    ) {
        self.format = format
        self.quality = quality
        self.maxWidth = maxWidth
        self.maxHeight = maxHeight
        self.maxOutputPixels = maxOutputPixels
        self.metadataPolicy = metadataPolicy
        self.jpegBackgroundARGB = jpegBackgroundARGB
    }

    static let maxPixelsLimit = 100_000_000
}
