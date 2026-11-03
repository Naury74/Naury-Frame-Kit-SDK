import SwiftUI

/// 편집 결과 위의 조작층. [rect]는 사진이 그려진 영역(뷰 좌표)이다.
struct CanvasInteraction: View {
    let model: EditorModel
    let rect: CGRect
    let zoom: CGFloat
    @Environment(\.editorTheme) private var theme
    @Environment(\.strings) private var strings
    @State private var start: OverlayTransform?
    @State private var dragMode: DragMode?
    @State private var pinchStart: OverlayTransform?

    private enum DragMode { case move, draw, handle(center: CGPoint, startVector: CGVector) }

    private var drawing: Bool { model.activeTool == .draw || model.activeTool == .privacy }
    /// 뷰 pt당 캔버스 px.
    private var pxPerPt: CGFloat { model.renderedCanvas.width / max(1, rect.width) }

    var body: some View {
        ZStack {
            Color.clear.contentShape(Rectangle())
            if let live = model.liveStroke { liveStrokeView(live) }
            if let overlay = model.selectedOverlay, model.canManipulateOverlays, !isEmptyText(overlay) { selection(overlay) }
        }
        .gesture(drag)
        .simultaneousGesture(overlayPinch)
        .onTapGesture { location in tap(location) }
    }

    private func isEmptyText(_ overlay: Overlay) -> Bool {
        if case .text(let text, _) = overlay.content { return text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
        return false
    }

    private func normalized(_ p: CGPoint) -> NPoint {
        NPoint(x: min(1, max(0, (p.x - rect.minX) / rect.width)), y: min(1, max(0, (p.y - rect.minY) / rect.height)))
    }

    private func view(_ p: CGPoint) -> CGPoint {
        CGPoint(x: rect.minX + p.x / pxPerPt, y: rect.minY + p.y / pxPerPt)
    }

    private func tap(_ location: CGPoint) {
        guard !drawing else { return }
        let point = normalized(location)
        // 선택 상자 손잡이를 누른 경우는 단추가 처리한다.
        let before = model.selectedOverlayId
        guard model.canManipulateOverlays else { model.toggleChrome(); return }
        model.selectOverlay(at: CGPoint(x: point.x, y: point.y))
        if let id = model.selectedOverlayId, id == before, model.selectedOverlay?.isText == true, model.activeTool == nil {
            model.selectTool(.text)
        } else if model.selectedOverlayId == nil && before == nil {
            model.toggleChrome()
        }
    }

    private var drag: some Gesture {
        DragGesture(minimumDistance: 1)
            .onChanged { g in
                if dragMode == nil {
                    if drawing {
                        dragMode = .draw
                        model.strokeBegan(normalized(g.startLocation))
                    } else if model.canManipulateOverlays, let overlay = model.selectedOverlay, hits(overlay, g.startLocation) {
                        dragMode = .move
                        start = overlay.transform
                    } else if model.canManipulateOverlays {
                        // 고르지 않은 요소를 바로 끌면 그 요소를 고르고 옮긴다.
                        let p = normalized(g.startLocation)
                        model.selectOverlay(at: CGPoint(x: p.x, y: p.y))
                        guard let overlay = model.selectedOverlay else { return }
                        dragMode = .move
                        start = overlay.transform
                    } else {
                        return
                    }
                }
                switch dragMode {
                case .draw: model.strokeMoved(normalized(g.location))
                case .move:
                    guard let start else { return }
                    let move = CGSize(width: g.translation.width * pxPerPt, height: g.translation.height * pxPerPt)
                    model.transformSelected(from: start, move: move, scale: 1, rotation: 0)
                default: break
                }
            }
            .onEnded { _ in
                switch dragMode {
                case .draw: model.strokeEnded()
                case .move: model.commitGesture()
                default: break
                }
                dragMode = nil
                start = nil
            }
    }

    private var overlayPinch: some Gesture {
        SimultaneousGesture(MagnifyGesture(), RotateGesture())
            .onChanged { value in
                guard model.canManipulateOverlays, let overlay = model.selectedOverlay else { return }
                if pinchStart == nil { pinchStart = overlay.transform }
                guard let pinchStart else { return }
                model.transformSelected(from: pinchStart, move: .zero, scale: Double(value.first?.magnification ?? 1),
                                        rotation: value.second?.rotation.degrees ?? 0)
            }
            .onEnded { _ in
                if pinchStart != nil { model.commitGesture() }
                pinchStart = nil
            }
    }

    private func hits(_ overlay: Overlay, _ location: CGPoint) -> Bool {
        let p = normalized(location)
        let canvas = model.renderedCanvas
        return OverlayRenderer.hitTest([overlay], point: CGPoint(x: p.x * canvas.width, y: p.y * canvas.height), canvas: canvas,
                                       slop: 16 * pxPerPt) != nil
    }

    // MARK: - 선택 상자

    @ViewBuilder
    private func selection(_ overlay: Overlay) -> some View {
        let corners = OverlayRenderer.corners(overlay, canvas: model.renderedCanvas).map(view)
        let center = CGPoint(x: corners.map(\.x).reduce(0, +) / 4, y: corners.map(\.y).reduce(0, +) / 4)
        let handleSize = 28 / zoom
        // 작은 요소에서도 손잡이가 겹치지 않게 꼭짓점에서 바깥쪽으로 민다.
        let handles = corners.map { c -> CGPoint in
            let dx = c.x - center.x, dy = c.y - center.y
            let length = max(1, hypot(dx, dy))
            let push = 18 / zoom
            return CGPoint(x: c.x + dx / length * push, y: c.y + dy / length * push)
        }
        Path { path in
            path.addLines(corners)
            path.closeSubpath()
        }
        .stroke(theme.accent, style: StrokeStyle(lineWidth: 1.5 / zoom, dash: [6 / zoom, 4 / zoom]))
        .allowsHitTesting(false)
        .accessibilityHidden(true)
        handle("xmark", at: handles[0], size: handleSize, label: strings("overlay_delete")) { model.deleteSelected() }
        handle("plus.square.on.square", at: handles[1], size: handleSize, label: strings("overlay_duplicate")) { model.duplicateSelected() }
        Image(systemName: "arrow.up.left.and.arrow.down.right")
            .font(.system(size: 12 / zoom, weight: .bold))
            .foregroundStyle(theme.onAccent)
            .frame(width: handleSize, height: handleSize)
            .background(theme.accent, in: Circle())
            .frame(width: 44 / zoom, height: 44 / zoom)
            .contentShape(Rectangle())
            .position(handles[2])
            .gesture(
                DragGesture(minimumDistance: 1, coordinateSpace: .named("canvasContent"))
                    .onChanged { g in
                        if start == nil {
                            start = overlay.transform
                            dragMode = .handle(center: center, startVector: CGVector(dx: g.startLocation.x - center.x, dy: g.startLocation.y - center.y))
                        }
                        guard let start, case .handle(let c, let v0) = dragMode else { return }
                        let v1 = CGVector(dx: g.location.x - c.x, dy: g.location.y - c.y)
                        let scale = hypot(v1.dx, v1.dy) / max(1, hypot(v0.dx, v0.dy))
                        let rotation = (atan2(v1.dy, v1.dx) - atan2(v0.dy, v0.dx)) * 180 / .pi
                        model.transformSelected(from: start, move: .zero, scale: scale, rotation: rotation)
                    }
                    .onEnded { _ in
                        model.commitGesture()
                        start = nil
                        dragMode = nil
                    }
            )
            .accessibilityHidden(true)
    }

    private func handle(_ symbol: String, at point: CGPoint, size: CGFloat, label: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: symbol)
                .font(.system(size: 12 / zoom, weight: .bold))
                .foregroundStyle(theme.foreground)
                .frame(width: size, height: size)
                .background(theme.raised, in: Circle())
                .frame(width: 44 / zoom, height: 44 / zoom)
                .contentShape(Rectangle())
        }
        .buttonStyle(PressFadeStyle())
        .position(point)
        .accessibilityLabel(label)
    }

    // MARK: - 그리는 중인 획

    private func liveStrokeView(_ points: [NPoint]) -> some View {
        let canvasShort = min(model.renderedCanvas.width, model.renderedCanvas.height) / pxPerPt
        let viewPoints = points.map { CGPoint(x: rect.minX + $0.x * rect.width, y: rect.minY + $0.y * rect.height) }
        let isShape = (model.activeTool == .draw && model.brush.shapes) || (model.activeTool == .privacy && model.privacy.mode != .brush)
        return Group {
            if isShape, let a = viewPoints.first, let b = viewPoints.last {
                Path { $0.addRect(CGRect(x: min(a.x, b.x), y: min(a.y, b.y), width: abs(b.x - a.x), height: abs(b.y - a.y))) }
                    .stroke(theme.accent, style: StrokeStyle(lineWidth: 1.5, dash: [6, 4]))
            } else {
                let width = model.activeTool == .privacy ? model.privacy.brushRatio * canvasShort : model.brush.widthRatio * canvasShort
                let color: Color = model.activeTool == .privacy ? Color.white.opacity(0.5)
                    : (model.brush.kind == .eraser ? Color.white.opacity(0.35)
                        : Color(argb: model.brush.color).opacity(model.brush.opacity * (model.brush.kind == .highlighter ? 0.4 : 1)))
                Path { $0.addLines(viewPoints) }
                    .stroke(color, style: StrokeStyle(lineWidth: width, lineCap: model.brush.kind == .marker ? .square : .round, lineJoin: .round))
            }
        }
        .allowsHitTesting(false)
    }
}
