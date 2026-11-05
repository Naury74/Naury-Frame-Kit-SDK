import SwiftUI

extension View {
    /// 반투명 유리 버튼 바탕. iOS 26 이상은 시스템 Liquid Glass, 그 아래는 사진 위에서도 읽히도록 테두리와 그림자를 둔 재질.
    @ViewBuilder
    func editorGlass<S: InsettableShape>(_ shape: S, theme: EditorTheme, tint: Color? = nil) -> some View {
        if #available(iOS 26.0, *) {
            glassEffect(tint.map { Glass.regular.tint($0) } ?? .regular, in: shape)
        } else {
            legacyGlass(shape, theme: theme, tint: tint)
        }
    }

    private func legacyGlass<S: InsettableShape>(_ shape: S, theme: EditorTheme, tint: Color?) -> some View {
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

/// 진행 원. [progress]가 `nil`이면 계속 돈다. 진행된 원호는 값이 바뀌는 동안 바깥쪽으로 물결치고, 1.5초 넘게 그대로이면 잦아든다.
/// 동작 줄이기 설정에서는 물결 없이 그린다.
struct ProgressRing: View {
    let progress: Double?
    @Environment(\.editorTheme) private var theme
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var changedAt = Date()

    var body: some View {
        ZStack {
            TimelineView(.animation(paused: reduceMotion)) { timeline in
                Canvas { context, size in
                    draw(&context, size: size, time: timeline.date)
                }
            }
            if let progress {
                Text("\(Int((min(1, max(0, progress)) * 100).rounded()))%").scaledFont(17, weight: .semibold).foregroundStyle(theme.foreground)
            }
        }
        .frame(width: 84, height: 84)
        .onValueChange(of: progress) { _, _ in changedAt = Date() }
        .accessibilityElement(children: .ignore)
        .accessibilityValue(progress.map { "\(Int((min(1, max(0, $0)) * 100).rounded()))%" } ?? "")
    }

    private func draw(_ context: inout GraphicsContext, size: CGSize, time: Date) {
        let line: CGFloat = 6
        let wave: CGFloat = 1.6
        let center = CGPoint(x: size.width / 2, y: size.height / 2)
        let radius = min(size.width, size.height) / 2 - line / 2 - wave
        let t = time.timeIntervalSinceReferenceDate
        let start: Double
        let sweep: Double
        var amplitude: CGFloat
        if let progress {
            let level = min(1, max(0, progress))
            start = -.pi / 2
            sweep = max(0.02, level) * 2 * .pi
            // 값이 멈추면 물결을 잦아들게 하고, 0과 1 가까이에서는 낮춘다.
            let idle = time.timeIntervalSince(changedAt) - Self.settleDelay
            amplitude = wave * CGFloat(min(1, max(0, 1 - idle / Self.settleDuration))) * CGFloat(min(1, min(level, 1 - level) * 10))
        } else {
            start = (t.truncatingRemainder(dividingBy: Self.spinPeriod) / Self.spinPeriod) * 2 * .pi - .pi / 2
            sweep = 0.28 * 2 * .pi
            amplitude = wave
        }
        if reduceMotion { amplitude = 0 }
        let style = StrokeStyle(lineWidth: line, lineCap: .round, lineJoin: .round)
        // 남은 트랙은 진행된 원호와 틈을 두고 매끈하게 그린다.
        let gap = Double((line + 4) / radius)
        if sweep + 2 * gap < 2 * .pi {
            var track = Path()
            track.addArc(center: center, radius: radius, startAngle: .radians(start + sweep + gap), endAngle: .radians(start + 2 * .pi - gap), clockwise: false)
            context.stroke(track, with: .color(theme.raised), style: style)
        }
        var arc = Path()
        let waves = max(1, (2 * .pi * Double(radius) / Self.wavelength).rounded())
        let phase = t * Self.cyclesPerSecond * 2 * .pi
        let steps = max(8, Int(sweep / (2 * .pi) * 180))
        for i in 0...steps {
            let angle = start + sweep * Double(i) / Double(steps)
            let r = radius + amplitude * CGFloat(sin(angle * waves - phase))
            let point = CGPoint(x: center.x + r * CGFloat(cos(angle)), y: center.y + r * CGFloat(sin(angle)))
            if i == 0 { arc.move(to: point) } else { arc.addLine(to: point) }
        }
        context.stroke(arc, with: .color(theme.accent), style: style)
    }

    private static let settleDelay: TimeInterval = 1.5
    private static let settleDuration: TimeInterval = 0.5
    private static let spinPeriod: TimeInterval = 1
    private static let wavelength: Double = 15
    private static let cyclesPerSecond: Double = 1
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

/// 짧은 알림 띠.
struct NoticeToast: View {
    let text: String
    let theme: EditorTheme

    var body: some View {
        Text(text).scaledFont(14).foregroundStyle(theme.foreground).multilineTextAlignment(.center)
            .padding(.horizontal, 16).padding(.vertical, 10)
            .background(theme.raised.opacity(0.94), in: RoundedRectangle(cornerRadius: theme.radius))
            .padding(12)
            .accessibilityAddTraits(.updatesFrequently)
    }
}

extension View {
    /// 값이 바뀌면 앞 값과 새 값으로 [action]을 부른다(iOS 17의 두 값 `onChange`와 같다).
    func onValueChange<V: Equatable>(of value: V, _ action: @escaping (_ old: V, _ new: V) -> Void) -> some View {
        modifier(ValueChangeModifier(value: value, action: action))
    }
}

private struct ValueChangeModifier<V: Equatable>: ViewModifier {
    let value: V
    let action: (V, V) -> Void

    func body(content: Content) -> some View {
        if #available(iOS 17, *) {
            content.onChange(of: value) { old, new in action(old, new) }
        } else {
            // 붙잡은 값은 바뀌기 전 화면의 값이다.
            content.onChange(of: value) { [value] new in action(value, new) }
        }
    }
}

extension View {
    /// 누른 위치(이 뷰 기준)와 함께 [action]을 부른다. iOS 15에서는 거의 움직이지 않은 끌기를 누르기로 본다.
    @ViewBuilder
    func onLocatedTap(perform action: @escaping (CGPoint) -> Void) -> some View {
        if #available(iOS 16, *) {
            onTapGesture(count: 1, coordinateSpace: .local, perform: action)
        } else {
            simultaneousGesture(DragGesture(minimumDistance: 0).onEnded { value in
                if hypot(value.translation.width, value.translation.height) < 10 { action(value.location) }
            })
        }
    }
}

/// 내용에 맞는 높이(가장 컸던 값).
private struct FittedHeightKey: PreferenceKey {
    static let defaultValue: CGFloat = 0
    static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) { value = max(value, nextValue()) }
}

/// 내용을 맞는 높이로 위에 붙이고, 지금까지 가장 컸던 높이를 알린다. 내용이 줄어도 시트가 출렁이지 않게 줄이지 않는다.
private struct FittedHeightReader: ViewModifier {
    let onChange: (CGFloat) -> Void
    @State private var height: CGFloat = 0

    func body(content: Content) -> some View {
        content
            .fixedSize(horizontal: false, vertical: true)
            .background(GeometryReader { proxy in Color.clear.preference(key: FittedHeightKey.self, value: proxy.size.height) })
            .onPreferenceChange(FittedHeightKey.self) { [binding = $height] value in
                if value > binding.wrappedValue + 0.5 { binding.wrappedValue = ceil(value) }
            }
            .onValueChange(of: height) { _, new in onChange(new) }
            .frame(maxHeight: .infinity, alignment: .top)
    }
}

/// 내용 높이에 맞춘 시트. iOS 15에서는 큰 시트로 보인다.
private struct FittedSheetSize: ViewModifier {
    @State private var height: CGFloat = 0

    func body(content: Content) -> some View {
        if #available(iOS 16, *) {
            content
                .modifier(FittedHeightReader { [binding = $height] in binding.wrappedValue = $0 })
                .presentationDetents([.height(height > 0 ? height : 320)])
        } else {
            content
        }
    }
}

extension View {
    /// 내용을 맞는 높이로 위에 붙이고 그 높이(가장 컸던 값)를 [onChange]로 알린다.
    func measureFittedHeight(_ onChange: @escaping (CGFloat) -> Void) -> some View {
        modifier(FittedHeightReader(onChange: onChange))
    }

    /// 시트 높이를 내용에 맞춘다. 내용이 줄어도 줄이지 않는다.
    func fittedSheetSize() -> some View {
        modifier(FittedSheetSize())
    }
}

/// 시트 높이.
enum SheetSize {
    case height(CGFloat), mediumLarge, large
}

extension View {
    /// 시트 높이. iOS 15에서는 큰 시트로 보인다.
    @ViewBuilder
    func sheetSize(_ size: SheetSize) -> some View {
        if #available(iOS 16, *) {
            switch size {
            case .height(let height): presentationDetents([.height(height)])
            case .mediumLarge: presentationDetents([.medium, .large])
            case .large: presentationDetents([.large])
            }
        } else {
            self
        }
    }

    /// 시트 바탕색. iOS 16.4 미만에서는 내용 뒤에 칠한다.
    @ViewBuilder
    func sheetBackground(_ color: Color) -> some View {
        if #available(iOS 16.4, *) {
            presentationBackground(color)
        } else {
            background(color.ignoresSafeArea())
        }
    }

    /// 내용이 넘칠 때만 튕긴다(iOS 16.4 이상).
    @ViewBuilder
    func bounceWhenScrollable() -> some View {
        if #available(iOS 16.4, *) {
            scrollBounceBehavior(.basedOnSize)
        } else {
            self
        }
    }

    /// 켜고 끄는 단추로 읽힌다. iOS 17 미만에서는 단추로 읽힌다.
    @ViewBuilder
    func toggleTrait() -> some View {
        if #available(iOS 17, *) {
            accessibilityAddTraits(.isToggle)
        } else {
            accessibilityAddTraits(.isButton)
        }
    }
}

/// 네 줄까지 늘어나는 입력란. iOS 15에서는 한 줄이다.
struct GrowingTextField: View {
    let title: String
    @Binding var text: String

    var body: some View {
        if #available(iOS 16, *) {
            TextField(title, text: $text, axis: .vertical).lineLimit(1...4)
        } else {
            TextField(title, text: $text)
        }
    }
}
