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

    /// WCAG 기준(본문 4.5:1, 보조 글자·버튼 3:1)보다 명암비가 낮은 조합. 비어 있으면 문제없다.
    /// 값이 없는 항목은 [themeMode]의 기본 색으로 계산한다. `.system`은 어두운 테마로 본다.
    /// - Returns: `"foreground/background"`처럼 문제가 있는 두 항목 이름.
    public func contrastWarnings(themeMode: ThemeMode = .dark, primaryARGB: UInt32? = nil) -> [String] {
        let dark = themeMode != .light
        let primary = (primaryARGB ?? EditorTheme.defaultAccent) | 0xFF00_0000
        let background = backgroundARGB ?? (dark ? 0xFF0D_0D0E : 0xFFF6_F6F8)
        let surface = surfaceARGB ?? (dark ? 0xFF17_1719 : 0xFFFF_FFFF)
        let foreground = foregroundARGB ?? (dark ? 0xFFFF_FFFF : 0xFF11_1114)
        let muted = foregroundMutedARGB ?? (dark ? 0xFFA1_A1AA : 0xFF5F_5F6B)
        let onPrimary = onPrimaryARGB ?? EditorTheme.readableOn(primary)
        var warnings: [String] = []
        if EditorTheme.contrast(foreground, background) < 4.5 { warnings.append("foreground/background") }
        if EditorTheme.contrast(foreground, surface) < 4.5 { warnings.append("foreground/surface") }
        if EditorTheme.contrast(muted, background) < 3 { warnings.append("foregroundMuted/background") }
        if EditorTheme.contrast(onPrimary, primary) < 3 { warnings.append("onPrimary/primary") }
        if EditorTheme.contrast(primary, surface) < 1.5 { warnings.append("primary/surface") }
        return warnings
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
    /// 편집기 글자에 쓸 `EditorCatalog.fonts`의 글꼴 id. `nil`이면 시스템 글꼴. 목록에 없으면 설정 오류.
    public var uiFontId: String?

    public init(
        themeMode: ThemeMode = .dark,
        primaryARGB: UInt32? = nil,
        cornerRadius: Int = 14,
        showExportProgress: Bool = true,
        enableHaptics: Bool = true,
        localeIdentifier: String? = nil,
        palette: EditorPalette? = nil,
        uiFontId: String? = nil
    ) {
        self.themeMode = themeMode
        self.primaryARGB = primaryARGB
        self.cornerRadius = cornerRadius
        self.showExportProgress = showExportProgress
        self.enableHaptics = enableHaptics
        self.localeIdentifier = localeIdentifier
        self.palette = palette
        self.uiFontId = uiFontId
    }

    /// 모양 설정 오류. [catalog]가 있으면 `uiFontId`가 그 목록에 있는지도 본다.
    func issues(catalog: EditorCatalog?) -> [ConfigurationIssue] {
        var issues: [ConfigurationIssue] = []
        if !(0...32).contains(cornerRadius) { issues.append(.init(path: "ui.cornerRadius", reason: .outOfRange)) }
        if let id = localeIdentifier, id.trimmingCharacters(in: .whitespaces).isEmpty {
            issues.append(.init(path: "ui.localeIdentifier", reason: .invalidValue))
        }
        if let font = uiFontId, let catalog, !catalog.fonts.contains(where: { $0.id == font }) {
            issues.append(.init(path: "ui.uiFontId", reason: .invalidValue))
        }
        return issues
    }
}
