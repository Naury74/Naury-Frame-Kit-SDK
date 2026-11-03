import SwiftUI
internal import FrameKitCore

/// 사진 영역. 자르기·회전 도구에서는 자르기 전 이미지 위에 자르기 틀을 그린다.
struct EditorCanvas: View {
    let model: EditorModel
    @Environment(\.editorTheme) private var theme
    @Environment(\.strings) private var strings
    @Environment(\.displayScale) private var displayScale
    @GestureState private var pressing = false
    @State private var dragHandle: CropHandle?

    private var showsBounds: Bool { model.activeTool == .crop || model.activeTool == .rotate }

    var body: some View {
        GeometryReader { geo in
            let padding: CGFloat = showsBounds ? 28 : 16
            let area = CGRect(origin: .zero, size: geo.size).insetBy(dx: padding, dy: padding)
            ZStack {
                theme.canvas
                if showsBounds {
                    boundsView(area: area)
                } else {
                    photoView(area: area)
                }
            }
            .onAppear { model.setViewport(CGSize(width: area.width * displayScale, height: area.height * displayScale)) }
            .onChange(of: geo.size) { _, _ in
                model.setViewport(CGSize(width: area.width * displayScale, height: area.height * displayScale))
            }
        }
        .clipped()
    }

    // MARK: - 편집 결과

    @ViewBuilder
    private func photoView(area: CGRect) -> some View {
        let original = model.showingOriginal || pressing
        let image = original ? model.preview?.cgImage : model.rendered
        ZStack(alignment: .topLeading) {
            if let image {
                let rect = fit(CGSize(width: image.width, height: image.height), in: area)
                Image(decorative: image, scale: 1)
                    .resizable()
                    .interpolation(.high)
                    .frame(width: rect.width, height: rect.height)
                    .position(x: rect.midX, y: rect.midY)
                    .accessibilityHidden(true)
                if original {
                    Text(strings("original_badge"))
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundStyle(theme.foreground)
                        .padding(.horizontal, 10).padding(.vertical, 5)
                        .background(theme.raised.opacity(0.94), in: Capsule())
                        .position(x: area.minX + 44, y: area.minY + 18)
                }
            }
        }
        .contentShape(Rectangle())
        .gesture(
            LongPressGesture(minimumDuration: 0.35)
                .sequenced(before: DragGesture(minimumDistance: 0))
                .updating($pressing) { value, state, _ in
                    if case .second(true, _) = value { state = true }
                }
                .exclusively(before: TapGesture().onEnded { model.toggleChrome() })
        )
        .accessibilityElement()
        .accessibilityLabel(strings("show_original"))
        .accessibilityAddTraits(.isImage)
    }

    // MARK: - 자르기 틀

    @ViewBuilder
    private func boundsView(area: CGRect) -> some View {
        let geometry = model.displayed.geometry
        let math = GeometryMath(source: model.uprightSize, geometry: geometry)
        let rect = fit(math.bounds, in: area)
        let crop = geometry.crop
        let cropRect = CGRect(x: rect.minX + crop.left * rect.width, y: rect.minY + crop.top * rect.height,
                              width: crop.width * rect.width, height: crop.height * rect.height)
        ZStack {
            if let image = model.boundsImage {
                Image(decorative: image, scale: 1)
                    .resizable()
                    .interpolation(.high)
                    .frame(width: rect.width, height: rect.height)
                    .position(x: rect.midX, y: rect.midY)
                    .accessibilityHidden(true)
            }
            CropFrameShape(outer: CGRect(x: -2000, y: -2000, width: 6000, height: 6000), inner: cropRect)
                .fill(Color.black.opacity(0.55), style: FillStyle(eoFill: true))
                .allowsHitTesting(false)
            cropFrame(cropRect, grid: model.draggingCrop || model.activeTool == .rotate, handles: model.activeTool == .crop)
        }
        .contentShape(Rectangle())
        .gesture(model.activeTool == .crop ? cropDrag(rect: rect, cropRect: cropRect) : nil)
        .accessibilityElement()
        .accessibilityLabel(strings("crop_area"))
    }

    private func cropFrame(_ r: CGRect, grid: Bool, handles: Bool) -> some View {
        Canvas { context, _ in
            context.stroke(Path(r), with: .color(.white), lineWidth: 1)
            if grid {
                var lines = Path()
                for i in 1...2 {
                    let x = r.minX + r.width * CGFloat(i) / 3
                    let y = r.minY + r.height * CGFloat(i) / 3
                    lines.move(to: CGPoint(x: x, y: r.minY)); lines.addLine(to: CGPoint(x: x, y: r.maxY))
                    lines.move(to: CGPoint(x: r.minX, y: y)); lines.addLine(to: CGPoint(x: r.maxX, y: y))
                }
                context.stroke(lines, with: .color(.white.opacity(0.45)), lineWidth: 0.75)
            }
            guard handles else { return }
            let l: CGFloat = 18
            var corners = Path()
            for (p, sx, sy) in [(CGPoint(x: r.minX, y: r.minY), 1.0, 1.0), (CGPoint(x: r.maxX, y: r.minY), -1.0, 1.0),
                                (CGPoint(x: r.maxX, y: r.maxY), -1.0, -1.0), (CGPoint(x: r.minX, y: r.maxY), 1.0, -1.0)] {
                corners.move(to: CGPoint(x: p.x + sx * l, y: p.y))
                corners.addLine(to: p)
                corners.addLine(to: CGPoint(x: p.x, y: p.y + sy * l))
            }
            context.stroke(corners, with: .color(.white), style: StrokeStyle(lineWidth: 3, lineCap: .square))
            var edges = Path()
            let e: CGFloat = 7
            edges.move(to: CGPoint(x: r.midX - e, y: r.minY)); edges.addLine(to: CGPoint(x: r.midX + e, y: r.minY))
            edges.move(to: CGPoint(x: r.midX - e, y: r.maxY)); edges.addLine(to: CGPoint(x: r.midX + e, y: r.maxY))
            edges.move(to: CGPoint(x: r.minX, y: r.midY - e)); edges.addLine(to: CGPoint(x: r.minX, y: r.midY + e))
            edges.move(to: CGPoint(x: r.maxX, y: r.midY - e)); edges.addLine(to: CGPoint(x: r.maxX, y: r.midY + e))
            context.stroke(edges, with: .color(theme.accent), style: StrokeStyle(lineWidth: 3, lineCap: .round))
        }
        .allowsHitTesting(false)
    }

    private func cropDrag(rect: CGRect, cropRect: CGRect) -> some Gesture {
        DragGesture(minimumDistance: 0)
            .onChanged { g in
                if dragHandle == nil {
                    guard let handle = hitTest(g.startLocation, in: cropRect) else { return }
                    dragHandle = handle
                    model.beginCropDrag(handle)
                }
                model.dragCrop(dx: g.translation.width / rect.width, dy: g.translation.height / rect.height)
            }
            .onEnded { _ in
                if dragHandle != nil { model.endCropDrag() }
                dragHandle = nil
            }
    }

    /// 손잡이 판정. 모서리 → 변 → 안쪽 순서, 반경 24pt.
    private func hitTest(_ p: CGPoint, in r: CGRect) -> CropHandle? {
        let reach: CGFloat = 24
        func near(_ a: CGFloat, _ b: CGFloat) -> Bool { abs(a - b) <= reach }
        let left = near(p.x, r.minX), right = near(p.x, r.maxX), top = near(p.y, r.minY), bottom = near(p.y, r.maxY)
        let inX = p.x > r.minX - reach && p.x < r.maxX + reach
        let inY = p.y > r.minY - reach && p.y < r.maxY + reach
        if top && left { return .topLeft }
        if top && right { return .topRight }
        if bottom && left { return .bottomLeft }
        if bottom && right { return .bottomRight }
        if top && inX { return .top }
        if bottom && inX { return .bottom }
        if left && inY { return .left }
        if right && inY { return .right }
        if r.contains(p) { return .move }
        return nil
    }
}

/// [size]를 비율을 지켜 [area] 가운데에 넣은 사각형.
func fit(_ size: CGSize, in area: CGRect) -> CGRect {
    guard size.width > 0, size.height > 0, area.width > 0, area.height > 0 else { return area }
    let scale = min(area.width / size.width, area.height / size.height)
    let w = size.width * scale
    let h = size.height * scale
    return CGRect(x: area.midX - w / 2, y: area.midY - h / 2, width: w, height: h)
}

/// 바깥 사각형에서 안쪽 사각형을 뺀 모양(자르기 바깥 어둡게).
struct CropFrameShape: Shape {
    let outer: CGRect
    let inner: CGRect

    func path(in _: CGRect) -> Path {
        var path = Path(outer)
        path.addRect(inner)
        return path
    }
}
