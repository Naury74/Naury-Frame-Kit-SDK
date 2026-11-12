import SwiftUI
internal import FrameKitCore

/// 사진 영역. 자르기·회전 도구에서는 자르기 전 이미지 위에 자르기 틀을 그린다.
struct EditorCanvas: View {
    @ObservedObject var model: EditorModel
    /// 위에 되돌리기 버튼이 떠 있어 그 자리를 비운다.
    var reservesHistory = false
    @Environment(\.editorTheme) private var theme
    @Environment(\.strings) private var strings
    @Environment(\.displayScale) private var displayScale
    @GestureState private var pressing = false
    @State private var dragHandle: CropHandle?
    @State private var zoom: CGFloat = 1
    @State private var pan: CGSize = .zero
    @State private var zoomStart: (zoom: CGFloat, pan: CGSize)?
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    static let maxZoom: CGFloat = 8

    private var showsBounds: Bool { model.activeTool == .crop || model.activeTool == .rotate }

    var body: some View {
        GeometryReader { geo in
            let padding: CGFloat = showsBounds ? 28 : 16
            let area = previewArea(geo.size, padding: padding, reservesHistory: reservesHistory && !showsBounds)
            ZStack {
                theme.canvas
                if showsBounds {
                    boundsView(area: area)
                        .onAppear { zoom = 1; pan = .zero }
                } else {
                    photoView(area: area)
                }
            }
            .onAppear { model.setViewport(CGSize(width: area.width * displayScale, height: area.height * displayScale)) }
            .onValueChange(of: geo.size) { _, _ in
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
        let size = image.map { CGSize(width: $0.width, height: $0.height) } ?? model.renderedCanvas
        let rect = fit(size, in: area)
        ZStack(alignment: .topLeading) {
            ZStack {
                if let image {
                    Image(decorative: image, scale: 1)
                        .resizable()
                        .interpolation(zoom > 1.5 ? .none : .high)
                        .frame(width: rect.width, height: rect.height)
                        .position(x: rect.midX, y: rect.midY)
                        .accessibilityHidden(true)
                }
                if !original {
                    CanvasInteraction(model: model, rect: rect, zoom: zoom)
                }
            }
            .coordinateSpace(name: "canvasContent")
            .scaleEffect(zoom, anchor: .center)
            .offset(pan)
            if original {
                Text(strings("original_badge"))
                    .scaledFont(13, weight: .semibold)
                    .foregroundStyle(theme.foreground)
                    .padding(.horizontal, 10).padding(.vertical, 5)
                    .background(theme.raised.opacity(0.94), in: Capsule())
                    .position(x: area.minX + 44, y: area.minY + 18)
            }
            if zoom > 1.01 {
                Button { setZoom(1, pan: .zero) } label: {
                    Text(strings("zoom_fit", Int((zoom * 100).rounded())))
                        .scaledFont(13, weight: .semibold).foregroundStyle(theme.foreground)
                        .padding(.horizontal, 12).frame(minHeight: 36)
                        .editorGlass(Capsule(), theme: theme)
                        .frame(minHeight: 48)
                }
                .buttonStyle(PressFadeStyle())
                .position(x: area.minX + 64, y: area.maxY - 24)
            }
        }
        .contentShape(Rectangle())
        .simultaneousGesture(canvasZoom(area: area))
        .simultaneousGesture(
            LongPressGesture(minimumDuration: 0.35)
                .sequenced(before: DragGesture(minimumDistance: 0))
                .updating($pressing) { value, state, _ in
                    guard model.activeTool == nil, model.selectedOverlayId == nil else { return }
                    if case .second(true, _) = value { state = true }
                }
        )
        .onTapGesture(count: 2) {
            guard model.activeTool != .draw, model.activeTool != .privacy else { return }
            setZoom(zoom > 1.01 ? 1 : 2.5, pan: .zero)
        }
        .accessibilityElement(children: .contain)
        .accessibilityLabel(strings("show_original"))
    }

    private func setZoom(_ value: CGFloat, pan: CGSize) {
        withAnimation(reduceMotion ? nil : .easeOut(duration: 0.25)) {
            zoom = value
            self.pan = pan
        }
    }

    /// 요소를 고르지 않았을 때 두 손가락으로 사진을 확대·이동한다.
    private func canvasZoom(area: CGRect) -> some Gesture {
        MagnificationGesture()
            .onChanged { value in
                guard model.selectedOverlayId == nil || !model.canManipulateOverlays else { return }
                if zoomStart == nil { zoomStart = (zoom, pan) }
                guard let zoomStart else { return }
                zoom = min(Self.maxZoom, max(1, zoomStart.zoom * value))
                let limitX = area.width * (zoom - 1) / 2, limitY = area.height * (zoom - 1) / 2
                pan = CGSize(width: min(limitX, max(-limitX, zoomStart.pan.width)), height: min(limitY, max(-limitY, zoomStart.pan.height)))
            }
            .onEnded { _ in
                zoomStart = nil
                if zoom < 1.05 { setZoom(1, pan: .zero) }
            }
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

/// 사진·영상을 놓을 영역. [reservesHistory]면 위에 떠 있는 되돌리기 버튼(위 여백 12 + 높이 48) 아래부터 쓴다.
func previewArea(_ size: CGSize, padding: CGFloat, reservesHistory: Bool) -> CGRect {
    let top = reservesHistory ? max(padding, historyPillBottom + 4) : padding
    return CGRect(x: padding, y: top, width: max(1, size.width - padding * 2), height: max(1, size.height - top - padding))
}

/// 되돌리기 버튼 아래 끝(사진 영역 위에서부터).
let historyPillBottom: CGFloat = 60

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
