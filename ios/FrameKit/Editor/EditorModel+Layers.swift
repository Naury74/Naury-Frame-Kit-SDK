import CoreGraphics
import Foundation
internal import FrameKitCore

/// 그리기 도구 설정. 굵기는 짧은 변 대비.
struct BrushSettings: Equatable {
    var kind = BrushKind.pen
    var color: UInt32 = 0xFFFF_FFFF
    var widthRatio = 0.012
    var opacity = 1.0
    var shapes = false
    var shape = ShapeKind.rectangle
    var fill = ShapeFill.outline

    static let widthRange = 0.002...0.05

    /// 슬라이더 1...100 ↔ 굵기.
    var widthDisplay: Double { 1 + (widthRatio - Self.widthRange.lowerBound) / (Self.widthRange.upperBound - Self.widthRange.lowerBound) * 99 }

    static func width(display: Double) -> Double {
        widthRange.lowerBound + (min(100, max(1, display)) - 1) / 99 * (widthRange.upperBound - widthRange.lowerBound)
    }
}

enum ShapeFill: String, CaseIterable { case outline, dashed, filled }

/// 가리기 도구 설정.
struct PrivacySettings: Equatable {
    enum Mode: String, CaseIterable { case brush, rectangle, ellipse }
    var mosaic = true
    var mode = Mode.brush
    /// 0...1.
    var strength = 0.4
    /// 짧은 변 대비 붓 지름.
    var brushRatio = 0.06

    var effect: PrivacyMask.Effect {
        mosaic ? .mosaic(blockRatio: 0.01 + strength * 0.07) : .blur(radiusRatio: 0.005 + strength * 0.045)
    }
}

/// 기하·색 보정 결과 캐시. 요소만 바뀔 때 다시 계산하지 않는다.
final class PreviewCache: @unchecked Sendable {
    struct Key: Hashable {
        let geometry: GeometryState
        let adjustments: [AdjustKind: Double]
        let filterId: String
        let filterIntensity: Double
        let grainSeed: Int64
        let width: Int
        let height: Int
        let source: ObjectIdentifier

        init(edit: PhotoEdit, output: PixelSizeValue, source: ObjectIdentifier) {
            self.source = source
            geometry = edit.geometry
            adjustments = edit.adjustments.filter { $0.value != 0 }
            filterId = edit.filterId
            filterIntensity = edit.filterIntensity
            grainSeed = edit.grainSeed
            width = output.width
            height = output.height
        }
    }

    private let lock = NSLock()
    private var key: Key?
    private var image: CGImage?

    func image(for key: Key) -> CGImage? { lock.withLock { self.key == key ? image : nil } }

    func store(_ image: CGImage, for key: Key) { lock.withLock { self.key = key; self.image = image } }
}

extension EditorModel {
    func makeId(_ prefix: String) -> String {
        "\(prefix)-\(UUID().uuidString.prefix(8))"
    }

    var selectedOverlay: Overlay? { displayed.overlays.first { $0.id == selectedOverlayId } }

    /// 요소를 고르고 옮길 수 있는 상태.
    var canManipulateOverlays: Bool { activeTool == nil || activeTool == .sticker || activeTool == .text }

    // MARK: - 글자

    func beginText() {
        if let selected = selectedOverlay, selected.isText { return }
        let id = makeId("text")
        updateWorkingEdit { $0.overlays.append(Overlay(id: id, content: .text("", TextStyle()))) }
        selectedOverlayId = id
    }

    var editingText: (text: String, style: TextStyle)? {
        guard let overlay = selectedOverlay, case .text(let text, let style) = overlay.content else { return nil }
        return (text, style)
    }

    func setText(_ text: String) {
        mutateSelected { overlay in
            if case .text(_, let style) = overlay.content { overlay.content = .text(text, style) }
        }
    }

    func setTextStyle(_ change: (inout TextStyle) -> Void) {
        mutateSelected { overlay in
            if case .text(let text, var style) = overlay.content {
                change(&style)
                overlay.content = .text(text, style)
            }
        }
    }

    // MARK: - 요소

    func addSticker(_ assetId: String) {
        let id = makeId("sticker")
        var edit = history.current
        edit.overlays.append(Overlay(id: id, content: .sticker(assetId: assetId, widthRatio: 0.25)))
        history.commit(edit)
        selectedOverlayId = id
        requestRender()
    }

    func mutateSelected(_ change: (inout Overlay) -> Void) {
        guard let id = selectedOverlayId else { return }
        updateWorkingEdit { edit in
            guard let index = edit.overlays.firstIndex(where: { $0.id == id }) else { return }
            change(&edit.overlays[index])
            edit.overlays[index].transform.scale = min(OverlayTransform.scaleRange.upperBound,
                                                       max(OverlayTransform.scaleRange.lowerBound, edit.overlays[index].transform.scale))
        }
    }

    /// 끌기 시작 시점 값에서 바꾼다. 끝나면 `commitGesture()`.
    func transformSelected(from start: OverlayTransform, move: CGSize, scale: Double, rotation: Double) {
        let canvas = renderedCanvas
        mutateSelected { overlay in
            var t = start
            t.center.x = min(1, max(0, start.center.x + move.width / canvas.width))
            t.center.y = min(1, max(0, start.center.y + move.height / canvas.height))
            t.scale = start.scale * scale
            var degrees = start.rotationDegrees + rotation
            // 0·90·180·270° 근처에서 붙인다.
            let snapped = (degrees / 90).rounded() * 90
            if abs(degrees - snapped) < 3 { degrees = snapped }
            t.rotationDegrees = degrees.truncatingRemainder(dividingBy: 360)
            overlay.transform = t
        }
    }

    func deleteSelected() {
        guard let id = selectedOverlayId else { return }
        updateWorkingEdit { $0.overlays.removeAll { $0.id == id } }
        selectedOverlayId = nil
        commitGesture()
    }

    func duplicateSelected() {
        guard let overlay = selectedOverlay else { return }
        var copy = overlay
        copy.id = makeId("copy")
        copy.transform.center.x = min(1, copy.transform.center.x + 0.04)
        copy.transform.center.y = min(1, copy.transform.center.y + 0.04)
        updateWorkingEdit { $0.overlays.append(copy) }
        selectedOverlayId = copy.id
        commitGesture()
    }

    /// 캔버스 점(0...1) 아래의 요소를 고른다. 없으면 선택을 푼다.
    func selectOverlay(at point: CGPoint) {
        let canvas = renderedCanvas
        let px = CGPoint(x: point.x * canvas.width, y: point.y * canvas.height)
        selectedOverlayId = OverlayRenderer.hitTest(displayed.overlays, point: px, canvas: canvas, slop: 8)
    }

    // MARK: - 그리기

    func strokeBegan(_ point: NPoint) { liveStroke = [point] }

    func strokeMoved(_ point: NPoint) {
        guard var stroke = liveStroke, stroke.count < DrawingStroke.maxPoints else { return }
        if let last = stroke.last, hypot(last.x - point.x, last.y - point.y) < 0.002 { return }
        stroke.append(point)
        liveStroke = stroke
    }

    func strokeEnded() {
        guard let points = liveStroke, !points.isEmpty else { liveStroke = nil; return }
        liveStroke = nil
        var edit = history.current
        switch activeTool {
        case .draw:
            if brush.shapes {
                guard let shape = shapeOverlay(from: points.first!, to: points.last!) else { return }
                edit.overlays.append(shape)
            } else {
                edit.drawing.append(DrawingStroke(id: makeId("stroke"), points: points, widthRatio: brush.widthRatio,
                                                  color: brush.color, opacity: brush.opacity, brush: brush.kind))
            }
        case .privacy:
            let shape: PrivacyMask.Shape
            switch privacy.mode {
            case .brush: shape = .brush(points, widthRatio: privacy.brushRatio)
            case .rectangle, .ellipse:
                let a = points.first!, b = points.last!
                let rect = NRect(left: min(a.x, b.x), top: min(a.y, b.y), right: max(a.x, b.x), bottom: max(a.y, b.y))
                guard rect.width > 0.005, rect.height > 0.005 else { return }
                shape = privacy.mode == .rectangle ? .rectangle(rect) : .ellipse(rect)
            }
            edit.privacyMasks.append(PrivacyMask(id: makeId("mask"), shape: shape, effect: privacy.effect))
        default:
            return
        }
        history.commit(edit)
        requestRender()
    }

    private func shapeOverlay(from a: NPoint, to b: NPoint) -> Overlay? {
        let canvas = renderedCanvas
        let dx = (b.x - a.x) * canvas.width, dy = (b.y - a.y) * canvas.height
        let style = ShapeStyle(color: brush.color, strokeRatio: max(0.002, min(0.1, brush.widthRatio)),
                               filled: brush.fill == .filled && !brush.shape.isLine, dashed: brush.fill == .dashed)
        let center = NPoint(x: (a.x + b.x) / 2, y: (a.y + b.y) / 2)
        var transform = OverlayTransform(center: center)
        transform.opacity = brush.opacity
        if brush.shape.isLine {
            let length = hypot(dx, dy)
            guard length > 4 else { return nil }
            transform.rotationDegrees = atan2(dy, dx) * 180 / .pi
            return Overlay(id: makeId("shape"), content: .shape(brush.shape, widthRatio: min(4, length / canvas.width), heightRatio: 0, style),
                           transform: transform)
        }
        guard abs(dx) > 4, abs(dy) > 4 else { return nil }
        return Overlay(id: makeId("shape"),
                       content: .shape(brush.shape, widthRatio: min(4, abs(dx) / canvas.width), heightRatio: min(4, abs(dy) / canvas.height), style),
                       transform: transform)
    }

    func undoLastLayer() {
        undo()
    }

    // MARK: - 워터마크

    var watermarkDraft: WatermarkSpec? { displayed.watermark }

    func setWatermark(_ change: (inout WatermarkSpec) -> Void) {
        updateWorkingEdit { edit in
            var spec = edit.watermark ?? WatermarkSpec(text: "")
            change(&spec)
            spec.text = String(spec.text.replacingOccurrences(of: "\n", with: " ").prefix(WatermarkSpec.maxTextLength))
            edit.watermark = spec
        }
    }

    func removeWatermark() {
        updateWorkingEdit { $0.watermark = nil }
    }

    // MARK: - 공통

    func updateWorkingEdit(_ change: (inout PhotoEdit) -> Void) {
        var edit = working ?? history.current
        change(&edit)
        working = edit
        requestRender()
    }

    /// 이모지 묶음(이름 키, 글자들).
    static let emojiGroups: [(key: String, emoji: [String])] = EmojiCatalog_core.groups

    /// 이모지 묶음과 호스트 스티커 id.
    var customStickerIds: [String] { StickerAssets.ids }
}

/// core 이모지 목록.
enum EmojiCatalog_core {
    static let groups: [(key: String, emoji: [String])] = FrameKitCore.EmojiCatalog.shared.groups.map { group in
        ("sticker_\(group.category.name.lowercased())", group.emoji)
    }
}

/// 편집 중인 사진 한 장.
struct EditorPage {
    let source: SourceImage
    let preview: UprightImage
    var history: EditHistory
    var thumbnail: CGImage?
}

extension UprightImage {
    /// 긴 변이 [maxSide] 이하인 작은 그림.
    func thumbnail(maxSide: Int) -> CGImage? {
        let scale = min(1, Double(maxSide) / Double(max(cgImage.width, cgImage.height)))
        let w = max(1, Int(Double(cgImage.width) * scale)), h = max(1, Int(Double(cgImage.height) * scale))
        guard let context = BitmapContext.make(width: w, height: h) else { return nil }
        context.interpolationQuality = .medium
        context.draw(cgImage, in: CGRect(x: 0, y: 0, width: w, height: h))
        return context.makeImage()
    }
}
