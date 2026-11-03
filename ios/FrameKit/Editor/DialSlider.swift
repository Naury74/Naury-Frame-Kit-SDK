import SwiftUI

/// 눈금자를 좌우로 끌어 값을 바꾸는 다이얼. 가운데 선이 현재 값이다.
struct DialSlider: View {
    let label: String
    let value: Double
    let range: ClosedRange<Double>
    var resetValue = 0.0
    /// 눈금 사이 간격(pt).
    var spacing: CGFloat = 4
    /// 눈금 하나의 값.
    var tickEvery = 2.0
    /// 큰 눈금 간격(값).
    var majorEvery = 10.0
    /// 손을 뗐을 때 기준값과 이만큼 이내면 기준값에 붙인다. 0이면 붙이지 않는다.
    var snapThreshold = 1.5
    let format: (Double) -> String
    let onChange: (Double) -> Void
    let onFinish: () -> Void

    @Environment(\.editorTheme) private var theme
    @Environment(\.haptics) private var haptics
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var dragStart: Double?
    @State private var inertia: Task<Void, Never>?
    // 관성 중에는 부모가 다시 그리기 전에 다음 값이 오므로 마지막으로 보낸 값을 따로 둔다.
    @State private var lastSent: Double?

    private var pointsPerUnit: CGFloat { spacing / CGFloat(tickEvery) }
    private var reset: Double { min(range.upperBound, max(range.lowerBound, resetValue)) }
    private var changed: Bool { abs(value - reset) >= 0.5 }

    var body: some View {
        VStack(spacing: 6) {
            Text(format(value))
                .font(.system(size: 17, weight: .semibold).monospacedDigit())
                .foregroundStyle(changed ? theme.accent : theme.foreground)
            ruler
                .frame(height: 36)
                .contentShape(Rectangle())
                .gesture(drag)
                .onTapGesture(count: 2) {
                    inertia?.cancel()
                    lastSent = nil
                    if value != reset { onChange(reset) }
                    haptics.mark()
                    onFinish()
                }
        }
        .accessibilityElement()
        .accessibilityLabel(label)
        .accessibilityValue(format(value))
        .accessibilityAdjustableAction { direction in
            lastSent = nil
            set(direction == .increment ? value + tickEvery : value - tickEvery)
            lastSent = nil
            onFinish()
        }
    }

    private var ruler: some View {
        Canvas { context, size in
            let mid = size.width / 2
            let first = (range.lowerBound / tickEvery).rounded(.up) * tickEvery
            var t = first
            let low = min(reset, value)
            let high = max(reset, value)
            while t <= range.upperBound + 1e-9 {
                let x = mid + CGFloat(t - value) * pointsPerUnit
                if x >= -2 && x <= size.width + 2 {
                    let major = abs((t / majorEvery).rounded() * majorEvery - t) < 1e-6
                    let h: CGFloat = major ? 16 : 9
                    let lit = changed && t >= low - 1e-9 && t <= high + 1e-9
                    var path = Path()
                    path.move(to: CGPoint(x: x, y: size.height / 2 - h / 2))
                    path.addLine(to: CGPoint(x: x, y: size.height / 2 + h / 2))
                    context.stroke(path, with: .color(lit ? theme.accent : theme.foregroundMuted.opacity(major ? 0.9 : 0.55)),
                                   lineWidth: major ? 1.5 : 1)
                }
                t += tickEvery
            }
            var center = Path()
            center.move(to: CGPoint(x: mid, y: 2))
            center.addLine(to: CGPoint(x: mid, y: size.height - 2))
            context.stroke(center, with: .color(theme.foreground), style: StrokeStyle(lineWidth: 2, lineCap: .round))
        }
        .mask(LinearGradient(stops: [.init(color: .clear, location: 0), .init(color: .black, location: 0.15),
                                     .init(color: .black, location: 0.85), .init(color: .clear, location: 1)],
                             startPoint: .leading, endPoint: .trailing))
    }

    private var drag: some Gesture {
        DragGesture(minimumDistance: 2)
            .onChanged { g in
                if dragStart == nil {
                    inertia?.cancel()
                    dragStart = value
                    lastSent = value
                }
                let direction: CGFloat = layoutIsRTL ? -1 : 1
                set((dragStart ?? value) - Double(g.translation.width * direction / pointsPerUnit))
            }
            .onEnded { g in
                dragStart = nil
                let direction: CGFloat = layoutIsRTL ? -1 : 1
                let velocity = -Double(g.velocity.width * direction / pointsPerUnit)
                if reduceMotion || abs(velocity) < tickEvery * 4 {
                    settle()
                } else {
                    fling(velocity)
                }
            }
    }

    @Environment(\.layoutDirection) private var layoutDirection
    private var layoutIsRTL: Bool { layoutDirection == .rightToLeft }

    private func fling(_ start: Double) {
        inertia?.cancel()
        inertia = Task { @MainActor in
            var velocity = start
            var current = lastSent ?? value
            while abs(velocity) > tickEvery * 2, !Task.isCancelled {
                try? await Task.sleep(for: .milliseconds(16))
                current += velocity * 0.016
                velocity *= 0.9
                if current <= range.lowerBound || current >= range.upperBound {
                    set(current)
                    break
                }
                set(current)
            }
            if !Task.isCancelled { settle() }
        }
    }

    private func settle() {
        let current = lastSent ?? value
        if snapThreshold > 0, abs(current - reset) <= snapThreshold, current != reset {
            set(reset)
        }
        lastSent = nil
        onFinish()
    }

    private func set(_ raw: Double) {
        let previous = lastSent ?? value
        let next = min(range.upperBound, max(range.lowerBound, raw))
        guard next != previous else { return }
        lastSent = next
        let before = (previous / tickEvery).rounded(.down)
        let after = (next / tickEvery).rounded(.down)
        if before != after {
            let crossedMajor = (previous / majorEvery).rounded(.down) != (next / majorEvery).rounded(.down)
            if next == reset || next == range.lowerBound || next == range.upperBound || crossedMajor {
                haptics.mark()
            } else {
                haptics.tick()
            }
        }
        onChange(next)
    }
}
