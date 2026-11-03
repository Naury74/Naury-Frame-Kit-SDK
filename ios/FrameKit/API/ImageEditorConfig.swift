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

    /// 기본 도구 순서.
    public static let defaults: [ImageTool] = [.crop, .rotate, .adjust, .filter]

    /// 적용·취소가 있는 도구. 저장 전에 적용해야 한다.
    var isDraft: Bool { self == .crop || self == .rotate }
}

/// 사진 편집기 동작 설정.
public struct ImageEditorConfig: Sendable, Hashable {
    /// 보여 줄 도구와 순서. 비우면 미리 보기와 저장만 된다. 중복은 무시한다.
    public var enabledTools: [ImageTool]
    /// 실행 취소 단추를 보인다.
    public var allowUndo: Bool
    /// 다시 실행 단추를 보인다. `allowUndo`가 `true`여야 한다.
    public var allowRedo: Bool

    public init(enabledTools: [ImageTool] = ImageTool.defaults, allowUndo: Bool = true, allowRedo: Bool = true) {
        self.enabledTools = enabledTools
        self.allowUndo = allowUndo
        self.allowRedo = allowRedo
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
