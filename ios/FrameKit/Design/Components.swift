import SwiftUI

extension View {
    /// 반투명 유리 느낌의 버튼 바탕. 사진 위에 떠도 읽히도록 테두리와 그림자를 둔다.
    func editorGlass<S: InsettableShape>(_ shape: S, theme: EditorTheme, tint: Color? = nil) -> some View {
        background {
            ZStack {
                if let tint {
                    shape.fill(tint)
                } else {
                    shape.fill(Color.white.opacity(theme.isDark ? 0.12 : 0.72))
                        .background(.ultraThinMaterial, in: shape)
                }
                shape.fill(LinearGradient(colors: [Color.white.opacity(theme.isDark ? 0.10 : 0.35), .clear],
                                          startPoint: .top, endPoint: .bottom))
                shape.strokeBorder(LinearGradient(
                    colors: theme.isDark ? [Color.white.opacity(0.32), Color.white.opacity(0.06)]
                        : [Color.white.opacity(0.95), Color.black.opacity(0.08)],
                    startPoint: .top, endPoint: .bottom), lineWidth: 0.75)
            }
            .shadow(color: .black.opacity(0.25), radius: 3, y: 2)
        }
    }

    /// 손대지 않으면 서서히 사라지는 조작부. 자리는 그대로 두어 사진이 움직이지 않는다.
    func editorChrome(hidden: Bool, reduceMotion: Bool, onReveal: @escaping () -> Void) -> some View {
        opacity(hidden ? 0 : 1)
            .animation(reduceMotion ? nil : .easeOut(duration: 0.2), value: hidden)
            .allowsHitTesting(!hidden)
            .overlay {
                if hidden { Color.clear.contentShape(Rectangle()).onTapGesture(perform: onReveal) }
            }
    }
}

/// 누르면 흐려지는 단추 모양(물결 없음).
struct PressFadeStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label.opacity(configuration.isPressed ? 0.4 : 1)
    }
}

/// 48pt 아이콘 단추.
struct IconButton: View {
    let icon: Image
    let label: String
    var size: CGFloat = 24
    var enabled = true
    let action: () -> Void
    @Environment(\.editorTheme) private var theme

    var body: some View {
        Button(action: action) {
            icon.resizable().scaledToFit().frame(width: size, height: size)
                .foregroundStyle(theme.foreground.opacity(enabled ? 1 : 0.35))
                .frame(minWidth: 48, minHeight: 48)
                .contentShape(Rectangle())
        }
        .buttonStyle(PressFadeStyle())
        .disabled(!enabled)
        .accessibilityLabel(label)
    }
}

/// 강조색 단추와 회색 단추.
struct EditorButton: View {
    enum Kind { case filled, tonal }
    let title: String
    var systemImage: String?
    var kind: Kind = .filled
    let action: () -> Void
    @Environment(\.editorTheme) private var theme

    var body: some View {
        Button(action: action) {
            HStack(spacing: 6) {
                if let systemImage { Image(systemName: systemImage).font(.system(size: 15, weight: .semibold)) }
                Text(title).scaledFont(16, weight: .semibold).lineLimit(1)
            }
            .padding(.horizontal, 18)
            .frame(minHeight: 48)
            .foregroundStyle(kind == .filled ? theme.onAccent : theme.foreground)
            .background(kind == .filled ? theme.accent : theme.raised, in: Capsule())
            .contentShape(Capsule())
        }
        .buttonStyle(PressFadeStyle())
    }
}

/// 하나만 고르는 칩.
struct ChoiceChip: View {
    let title: String
    let selected: Bool
    let action: () -> Void
    @Environment(\.editorTheme) private var theme

    var body: some View {
        Button(action: action) {
            Text(title).scaledFont(14, weight: .medium).lineLimit(1)
                .padding(.horizontal, 14)
                .frame(minHeight: 36)
                .foregroundStyle(selected ? theme.onAccent : theme.foreground)
                .background(selected ? theme.accent : theme.raised, in: Capsule())
                .frame(minHeight: 48)
                .contentShape(Rectangle())
        }
        .buttonStyle(PressFadeStyle())
        .accessibilityAddTraits(selected ? [.isSelected] : [])
    }
}

/// 진행 원. [progress]가 `nil`이면 계속 돈다.
struct ProgressRing: View {
    let progress: Double?
    @Environment(\.editorTheme) private var theme
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var spin = false

    var body: some View {
        ZStack {
            Circle().stroke(theme.raised, lineWidth: 6)
            if let progress {
                Circle().trim(from: 0, to: max(0.02, min(1, progress)))
                    .stroke(theme.accent, style: StrokeStyle(lineWidth: 6, lineCap: .round))
                    .rotationEffect(.degrees(-90))
                Text("\(Int((progress * 100).rounded()))%").scaledFont(17, weight: .semibold).foregroundStyle(theme.foreground)
            } else {
                Circle().trim(from: 0, to: 0.28)
                    .stroke(theme.accent, style: StrokeStyle(lineWidth: 6, lineCap: .round))
                    .rotationEffect(.degrees(spin ? 360 : 0))
                    .onAppear {
                        guard !reduceMotion else { return }
                        withAnimation(.linear(duration: 1).repeatForever(autoreverses: false)) { spin = true }
                    }
            }
        }
        .frame(width: 84, height: 84)
    }
}

/// 시스템 글자 크기 설정을 따르는 글꼴. [size]는 기본 크기(pt)다.
struct ScaledFont: ViewModifier {
    let size: CGFloat
    var weight: Font.Weight = .regular
    var monospacedDigit = false
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @Environment(\.editorFontId) private var fontId

    func body(content: Content) -> some View {
        let traits = UITraitCollection(preferredContentSizeCategory: UIContentSizeCategory(dynamicTypeSize))
        let scaled = UIFontMetrics(forTextStyle: .body).scaledValue(for: size, compatibleWith: traits)
        let custom = fontId.flatMap { FontAssets.font(for: $0, size: scaled) }
        let font = custom.map { Font($0).weight(weight) } ?? Font.system(size: scaled, weight: weight)
        return content.font(monospacedDigit ? font.monospacedDigit() : font)
    }
}

extension View {
    func scaledFont(_ size: CGFloat, weight: Font.Weight = .regular, monospacedDigit: Bool = false) -> some View {
        modifier(ScaledFont(size: size, weight: weight, monospacedDigit: monospacedDigit))
    }
}
