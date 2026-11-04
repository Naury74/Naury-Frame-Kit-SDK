import SwiftUI
import UIKit

/// 편집기 색과 모양. Android FrameKitColors와 같은 값이다.
struct EditorTheme {
    let accent: Color
    let onAccent: Color
    let background: Color
    let surface: Color
    let raised: Color
    let foreground: Color
    let foregroundMuted: Color
    let canvas: Color
    let error: Color
    let isDark: Bool
    let radius: CGFloat

    static let defaultAccent: UInt32 = 0xFF63_5BFF

    static func make(_ config: EditorUIConfig, systemDark: Bool) -> EditorTheme {
        let dark = switch config.themeMode {
        case .dark: true
        case .light: false
        case .system: systemDark
        }
        let palette = config.palette ?? EditorPalette()
        let accent = (config.primaryARGB ?? defaultAccent) | 0xFF00_0000
        func pick(_ value: UInt32?, _ darkValue: UInt32, _ lightValue: UInt32) -> Color {
            Color(argb: value ?? (dark ? darkValue : lightValue))
        }
        return EditorTheme(
            accent: Color(argb: accent),
            onAccent: Color(argb: palette.onPrimaryARGB ?? readableOn(accent)),
            background: pick(palette.backgroundARGB, 0xFF0D_0D0E, 0xFFF6_F6F8),
            surface: pick(palette.surfaceARGB, 0xFF17_1719, 0xFFFF_FFFF),
            raised: pick(palette.raisedARGB, 0xFF24_2428, 0xFFED_EDF1),
            foreground: pick(palette.foregroundARGB, 0xFFFF_FFFF, 0xFF11_1114),
            foregroundMuted: pick(palette.foregroundMutedARGB, 0xFFA1_A1AA, 0xFF5F_5F6B),
            canvas: pick(palette.canvasARGB, 0xFF00_0000, 0xFFE4_E4E9),
            error: Color(argb: dark ? 0xFFFF_6B6B : 0xFFD9_2D20),
            isDark: dark,
            radius: CGFloat(min(32, max(0, config.cornerRadius)))
        )
    }

    /// 강조색 위 글자색. 흰색 명암비가 3:1 이상이면 흰색, 아니면 흰색·검은색 중 명암비가 높은 쪽. Android와 같다.
    static func readableOn(_ argb: UInt32) -> UInt32 {
        let white = contrast(0xFFFF_FFFF, argb)
        return white >= 3 || white >= contrast(0xFF00_0000, argb) ? 0xFFFF_FFFF : 0xFF00_0000
    }

    /// WCAG 명암비, 1...21. 알파는 무시한다.
    static func contrast(_ a: UInt32, _ b: UInt32) -> Double {
        let la = luminance(a), lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    static func luminance(_ argb: UInt32) -> Double {
        func channel(_ shift: UInt32) -> Double {
            let c = Double((argb >> shift) & 0xFF) / 255
            return c <= 0.03928 ? c / 12.92 : pow((c + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
    }
}

extension Color {
    init(argb: UInt32) {
        self.init(.sRGB,
                  red: Double((argb >> 16) & 0xFF) / 255,
                  green: Double((argb >> 8) & 0xFF) / 255,
                  blue: Double(argb & 0xFF) / 255,
                  opacity: Double((argb >> 24) & 0xFF) / 255)
    }
}

private struct ThemeKey: EnvironmentKey {
    static let defaultValue = EditorTheme.make(EditorUIConfig(), systemDark: true)
}

private struct StringsKey: EnvironmentKey {
    static let defaultValue = Strings(localeIdentifier: nil)
}

private struct HapticsKey: EnvironmentKey {
    static let defaultValue = Haptics(enabled: true)
}

private struct EmbeddedKey: EnvironmentKey {
    static let defaultValue = false
}

private struct FontIdKey: EnvironmentKey {
    static let defaultValue: String? = nil
}

private struct RecorderSheetKey: EnvironmentKey {
    static let defaultValue: (@MainActor @Sendable (CGFloat) -> Void)? = nil
}

extension EnvironmentValues {
    /// 혼합 편집 화면 안에 들어 있다. 편집기 자체의 닫기·저장을 숨긴다.
    var editorEmbedded: Bool {
        get { self[EmbeddedKey.self] }
        set { self[EmbeddedKey.self] = newValue }
    }

    /// 녹음 시트로 떠 있으면 녹음 화면의 맞는 높이를 받는다. `nil`이면 전체 화면이다.
    var recorderSheetHeight: (@MainActor @Sendable (CGFloat) -> Void)? {
        get { self[RecorderSheetKey.self] }
        set { self[RecorderSheetKey.self] = newValue }
    }

    /// 편집기 글자에 쓸 사용자 글꼴 id.
    var editorFontId: String? {
        get { self[FontIdKey.self] }
        set { self[FontIdKey.self] = newValue }
    }

    var editorTheme: EditorTheme {
        get { self[ThemeKey.self] }
        set { self[ThemeKey.self] = newValue }
    }

    var strings: Strings {
        get { self[StringsKey.self] }
        set { self[StringsKey.self] = newValue }
    }

    var haptics: Haptics {
        get { self[HapticsKey.self] }
        set { self[HapticsKey.self] = newValue }
    }
}
