import UIKit

/// 화면 밝기 방식.
public enum ThemeMode: String, Sendable {
    case dark
    case light
    /// 기기 설정을 따른다.
    case system
}

/// 편집기 색. `nil`인 항목은 테마 기본값을 쓴다. 값은 0xAARRGGBB.
public struct EditorPalette: Sendable, Hashable {
    public var backgroundARGB: UInt32?
    public var surfaceARGB: UInt32?
    public var raisedARGB: UInt32?
    public var foregroundARGB: UInt32?
    public var foregroundMutedARGB: UInt32?
    /// 사진 뒤 바탕.
    public var canvasARGB: UInt32?
    /// 강조색 위 글자색. `nil`이면 대비가 큰 흰색·검은색 중에서 고른다.
    public var onPrimaryARGB: UInt32?

    public init(
        backgroundARGB: UInt32? = nil,
        surfaceARGB: UInt32? = nil,
        raisedARGB: UInt32? = nil,
        foregroundARGB: UInt32? = nil,
        foregroundMutedARGB: UInt32? = nil,
        canvasARGB: UInt32? = nil,
        onPrimaryARGB: UInt32? = nil
    ) {
        self.backgroundARGB = backgroundARGB
        self.surfaceARGB = surfaceARGB
        self.raisedARGB = raisedARGB
        self.foregroundARGB = foregroundARGB
        self.foregroundMutedARGB = foregroundMutedARGB
        self.canvasARGB = canvasARGB
        self.onPrimaryARGB = onPrimaryARGB
    }
}

/// 편집기 모양 설정.
public struct EditorUIConfig: Sendable, Hashable {
    public var themeMode: ThemeMode
    /// 강조색(0xAARRGGBB). `nil`이면 #635BFF.
    public var primaryARGB: UInt32?
    /// 모서리 반지름(pt), 0...32.
    public var cornerRadius: Int
    /// 저장 중 단계 이름을 보인다. `false`면 '저장하는 중…'만 보인다.
    public var showExportProgress: Bool
    /// 햅틱을 쓴다. 기기 설정은 그대로 따른다.
    public var enableHaptics: Bool
    /// 편집기 언어(BCP 47, 예: `ko`, `zh-Hant`). `nil`이면 앱 언어. 지원하지 않는 언어는 영어.
    public var localeIdentifier: String?
    public var palette: EditorPalette?

    public init(
        themeMode: ThemeMode = .dark,
        primaryARGB: UInt32? = nil,
        cornerRadius: Int = 14,
        showExportProgress: Bool = true,
        enableHaptics: Bool = true,
        localeIdentifier: String? = nil,
        palette: EditorPalette? = nil
    ) {
        self.themeMode = themeMode
        self.primaryARGB = primaryARGB
        self.cornerRadius = cornerRadius
        self.showExportProgress = showExportProgress
        self.enableHaptics = enableHaptics
        self.localeIdentifier = localeIdentifier
        self.palette = palette
    }
}
