import CoreGraphics
import UIKit

/// 글자·스티커·도형·그리기 레이어를 배치하고 그린다. 미리 보기·저장·터치 판정이 같은 계산을 쓴다.
/// 그리는 컨텍스트는 y가 아래로 가는 좌표여야 한다.
enum OverlayRenderer {
    // Android OverlayRenderer와 같은 상수.
    static let highlighterAlpha = 0.4
    static let roundedCorner = 0.2
    static let starPoints = 5
    static let starInner = 0.45
    static let dashOn = 2.4
    static let dashOff = 1.8
    static let headPerStroke = 3.0
    static let headMinRatio = 0.014
    static let headHalfWidth = 0.6
    static let headOverlap = 0.8
    static let headRounding = 0.4

    /// [canvas] 크기 캔버스에서 회전 전 상자 크기(px).
    static func layout(_ overlay: Overlay, canvas: CGSize) -> CGSize {
        let scale = overlay.transform.scale
        switch overlay.content {
        case .text(let text, let style):
            let measured = textBox(text, style: style, scale: scale, canvas: canvas)
            let margin = textMargin(style, scale: scale, canvas: canvas)
            return CGSize(width: measured.size.width + 2 * margin, height: measured.size.height + 2 * margin)
        case .sticker(let assetId, let widthRatio):
            let side = widthRatio * canvas.width * scale
            if let image = StickerAssets.image(for: assetId), image.size.width > 0 {
                return CGSize(width: side, height: side * image.size.height / image.size.width)
            }
            return CGSize(width: side, height: side)
        case .shape(let kind, let widthRatio, let heightRatio, let style):
            let stroke = shapeStroke(style, canvas: canvas)
            if kind.isLine {
                let length = widthRatio * canvas.width * scale
                let thickness = kind == .line ? stroke : max(stroke, arrowHead(stroke, canvas: canvas).half * 2)
                return CGSize(width: length + stroke, height: thickness)
            }
            let margin = style.filled ? 0 : stroke
            return CGSize(width: widthRatio * canvas.width * scale + margin, height: heightRatio * canvas.height * scale + margin)
        }
    }

    /// 회전한 상자의 네 꼭짓점(px). 왼쪽 위부터 시계 방향.
    static func corners(_ overlay: Overlay, canvas: CGSize) -> [CGPoint] {
        let size = layout(overlay, canvas: canvas)
        let cx = overlay.transform.center.x * canvas.width
        let cy = overlay.transform.center.y * canvas.height
        let r = overlay.transform.rotationDegrees * .pi / 180
        let c = cos(r), s = sin(r)
        return [(-1.0, -1.0), (1.0, -1.0), (1.0, 1.0), (-1.0, 1.0)].map { sx, sy in
            let x = sx * size.width / 2, y = sy * size.height / 2
            return CGPoint(x: cx + x * c - y * s, y: cy + x * s + y * c)
        }
    }

    /// ([point]) 아래의 가장 위 요소 id.
    static func hitTest(_ overlays: [Overlay], point: CGPoint, canvas: CGSize, slop: Double = 0) -> String? {
        overlays.last { overlay in
            let size = layout(overlay, canvas: canvas)
            let dx = point.x - overlay.transform.center.x * canvas.width
            let dy = point.y - overlay.transform.center.y * canvas.height
            let r = -overlay.transform.rotationDegrees * .pi / 180
            let lx = dx * cos(r) - dy * sin(r)
            let ly = dx * sin(r) + dy * cos(r)
            return abs(lx) <= size.width / 2 + slop && abs(ly) <= size.height / 2 + slop
        }?.id
    }

    /// 그리기 레이어, 요소를 차례로 그린다.
    static func draw(in context: CGContext, canvas: CGSize, overlays: [Overlay], drawing: [DrawingStroke]) {
        context.saveGState()
        context.clip(to: CGRect(origin: .zero, size: canvas))
        UIGraphicsPushContext(context)
        if !drawing.isEmpty { drawStrokes(context, canvas: canvas, strokes: drawing) }
        for overlay in overlays { drawOverlay(context, canvas: canvas, overlay: overlay) }
        UIGraphicsPopContext()
        context.restoreGState()
    }

    private static func drawOverlay(_ context: CGContext, canvas: CGSize, overlay: Overlay) {
        let box = layout(overlay, canvas: canvas)
        let t = overlay.transform
        context.saveGState()
        context.setAlpha(min(1, max(0, t.opacity)))
        context.beginTransparencyLayer(auxiliaryInfo: nil)
        context.translateBy(x: t.center.x * canvas.width, y: t.center.y * canvas.height)
        context.rotate(by: t.rotationDegrees * .pi / 180)
        context.translateBy(x: -box.width / 2, y: -box.height / 2)
        switch overlay.content {
        case .text(let text, let style): drawText(context, text: text, style: style, scale: t.scale, canvas: canvas, box: box)
        case .sticker(let assetId, _): drawSticker(context, assetId: assetId, box: box)
        case .shape(let kind, _, _, let style): drawShape(context, kind: kind, style: style, canvas: canvas, box: box)
        }
        context.endTransparencyLayer()
        context.restoreGState()
    }

    // MARK: - 글자

    static func font(_ fontId: String, size: CGFloat) -> UIFont {
        if let custom = FontAssets.font(for: fontId, size: size) { return custom }
        func design(_ d: UIFontDescriptor.SystemDesign, _ weight: UIFont.Weight) -> UIFont {
            let base = UIFont.systemFont(ofSize: size, weight: weight)
            return base.fontDescriptor.withDesign(d).map { UIFont(descriptor: $0, size: size) } ?? base
        }
        return switch fontId {
        case "sans-bold": UIFont.systemFont(ofSize: size, weight: .bold)
        case "serif": design(.serif, .regular)
        case "serif-bold": design(.serif, .bold)
        case "mono": design(.monospaced, .regular)
        case "handwriting": UIFont(name: "SnellRoundhand", size: size) ?? UIFont.italicSystemFont(ofSize: size)
        default: UIFont.systemFont(ofSize: size)
        }
    }

    private static func attributed(_ text: String, style: TextStyle, fontSize: CGFloat) -> NSAttributedString {
        let paragraph = NSMutableParagraphStyle()
        paragraph.alignment = switch style.alignment {
        case .start: .natural
        case .center: .center
        case .end: .right
        }
        paragraph.lineHeightMultiple = style.lineSpacing
        paragraph.lineBreakMode = .byWordWrapping
        return NSAttributedString(string: text.isEmpty ? " " : text, attributes: [
            .font: font(style.fontId, size: fontSize),
            .kern: style.letterSpacingEm * fontSize,
            .paragraphStyle: paragraph,
            .foregroundColor: UIColor(cgColor: CGColor.argb(style.color)),
        ])
    }

    /// 줄바꿈한 글자 영역. 가장 긴 줄 폭에 맞춘다.
    private static func textBox(_ text: String, style: TextStyle, scale: Double, canvas: CGSize) -> (string: NSAttributedString, size: CGSize) {
        let fontSize = max(1, style.fontSizeRatio * canvas.height * scale)
        let string = attributed(text, style: style, fontSize: fontSize)
        let maxWidth = max(1, ceil(style.maxWidthRatio * canvas.width * scale))
        let rect = string.boundingRect(with: CGSize(width: maxWidth, height: .greatestFiniteMagnitude),
                                       options: [.usesLineFragmentOrigin, .usesFontLeading], context: nil)
        return (string, CGSize(width: ceil(rect.width), height: ceil(rect.height)))
    }

    private static func textMargin(_ style: TextStyle, scale: Double, canvas: CGSize) -> CGFloat {
        let unit = canvas.height * scale
        let stroke = style.stroke.map { $0.widthRatio * unit / 2 } ?? 0
        let shadow = style.shadow.map { max(abs($0.offsetXRatio), abs($0.offsetYRatio)) * unit + $0.blurRatio * unit } ?? 0
        let background = style.background.map { $0.paddingRatio * unit } ?? 0
        return max(stroke + shadow, background)
    }

    private static func drawText(_ context: CGContext, text: String, style: TextStyle, scale: Double, canvas: CGSize, box: CGSize) {
        let unit = canvas.height * scale
        if let background = style.background {
            let corner = background.cornerRatio * unit
            context.setFillColor(CGColor.argb(background.color))
            context.addPath(CGPath(roundedRect: CGRect(origin: .zero, size: box), cornerWidth: corner, cornerHeight: corner, transform: nil))
            context.fillPath()
        }
        let measured = textBox(text, style: style, scale: scale, canvas: canvas)
        let margin = textMargin(style, scale: scale, canvas: canvas)
        let rect = CGRect(x: margin, y: margin, width: measured.size.width, height: measured.size.height)
        let fontSize = max(1, style.fontSizeRatio * canvas.height * scale)
        if let stroke = style.stroke {
            let outlined = NSMutableAttributedString(attributedString: measured.string)
            let range = NSRange(location: 0, length: outlined.length)
            // 글자 크기 대비 백분율. 양수면 테두리만 그린다.
            outlined.addAttributes([.strokeColor: UIColor(cgColor: CGColor.argb(stroke.color)),
                                    .strokeWidth: stroke.widthRatio * unit / fontSize * 100], range: range)
            context.setLineJoin(.round)
            outlined.draw(with: rect, options: [.usesLineFragmentOrigin, .usesFontLeading], context: nil)
        }
        let filled = NSMutableAttributedString(attributedString: measured.string)
        if let shadow = style.shadow {
            let s = NSShadow()
            s.shadowColor = UIColor(cgColor: CGColor.argb(shadow.color))
            s.shadowOffset = CGSize(width: shadow.offsetXRatio * unit, height: shadow.offsetYRatio * unit)
            s.shadowBlurRadius = max(0.01, shadow.blurRatio * unit)
            filled.addAttribute(.shadow, value: s, range: NSRange(location: 0, length: filled.length))
        }
        filled.draw(with: rect, options: [.usesLineFragmentOrigin, .usesFontLeading], context: nil)
    }

    // MARK: - 스티커

    private static func drawSticker(_ context: CGContext, assetId: String, box: CGSize) {
        if let image = StickerAssets.image(for: assetId) {
            context.interpolationQuality = .high
            image.draw(in: CGRect(origin: .zero, size: box))
            return
        }
        guard let emoji = EmojiCatalog.emoji(of: assetId) else { return }
        let reference: CGFloat = 100
        let measured = max(1, (emoji as NSString).size(withAttributes: [.font: UIFont.systemFont(ofSize: reference)]).width)
        let font = UIFont.systemFont(ofSize: reference * box.width / measured)
        let size = (emoji as NSString).size(withAttributes: [.font: font])
        (emoji as NSString).draw(at: CGPoint(x: (box.width - size.width) / 2, y: (box.height - size.height) / 2), withAttributes: [.font: font])
    }

    // MARK: - 도형

    static func shapeStroke(_ style: ShapeStyle, canvas: CGSize) -> CGFloat {
        max(1, style.strokeRatio * min(canvas.width, canvas.height))
    }

    private static func arrowHead(_ stroke: CGFloat, canvas: CGSize) -> (length: CGFloat, half: CGFloat) {
        let length = stroke * headPerStroke + min(canvas.width, canvas.height) * headMinRatio
        return (length, length * headHalfWidth)
    }

    private static func drawShape(_ context: CGContext, kind: ShapeKind, style: ShapeStyle, canvas: CGSize, box: CGSize) {
        let stroke = shapeStroke(style, canvas: canvas)
        let filled = style.filled && !kind.isLine
        let color = CGColor.argb(style.color)
        context.setStrokeColor(color)
        context.setFillColor(color)
        context.setLineWidth(stroke)
        context.setLineCap(.round)
        context.setLineJoin(kind == .rectangle ? .miter : .round)
        if style.dashed && !filled { context.setLineDash(phase: 0, lengths: [stroke * dashOn, stroke * dashOff]) }
        if kind.isLine {
            drawLineShape(context, kind: kind, box: box, stroke: stroke, canvas: canvas)
            return
        }
        let inset = filled ? 0 : stroke / 2
        let rect = CGRect(x: inset, y: inset, width: box.width - 2 * inset, height: box.height - 2 * inset)
        let path = CGMutablePath()
        switch kind {
        case .rectangle: path.addRect(rect)
        case .roundedRectangle:
            let corner = min(rect.width, rect.height) * roundedCorner
            path.addRoundedRect(in: rect, cornerWidth: corner, cornerHeight: corner)
        case .ellipse: path.addEllipse(in: rect)
        case .triangle:
            path.move(to: CGPoint(x: rect.midX, y: rect.minY))
            path.addLine(to: CGPoint(x: rect.maxX, y: rect.maxY))
            path.addLine(to: CGPoint(x: rect.minX, y: rect.maxY))
            path.closeSubpath()
        case .star:
            for i in 0..<(starPoints * 2) {
                let angle = (-90.0 + Double(i) * 180.0 / Double(starPoints)) * .pi / 180
                let k = i % 2 == 0 ? 1 : starInner
                let p = CGPoint(x: rect.midX + cos(angle) * rect.width / 2 * k, y: rect.midY + sin(angle) * rect.height / 2 * k)
                if i == 0 { path.move(to: p) } else { path.addLine(to: p) }
            }
            path.closeSubpath()
        case .line, .arrow, .doubleArrow: break
        }
        context.addPath(path)
        if filled { context.fillPath() } else { context.strokePath() }
    }

    private static func drawLineShape(_ context: CGContext, kind: ShapeKind, box: CGSize, stroke: CGFloat, canvas: CGSize) {
        let y = box.height / 2
        let start = stroke / 2
        let end = box.width - stroke / 2
        let head = arrowHead(stroke, canvas: canvas)
        let tail = kind == .doubleArrow ? start + head.length * headOverlap : start
        let tip = kind == .line ? end : end - head.length * headOverlap
        if tip > tail {
            context.move(to: CGPoint(x: tail, y: y))
            context.addLine(to: CGPoint(x: tip, y: y))
            context.strokePath()
        }
        guard kind != .line else { return }
        context.setLineDash(phase: 0, lengths: [])
        context.setLineWidth(stroke * headRounding)
        context.setLineJoin(.round)
        func arrow(at tipX: CGFloat, direction: CGFloat) {
            let base = tipX - direction * head.length
            context.move(to: CGPoint(x: tipX, y: y))
            context.addLine(to: CGPoint(x: base, y: y - head.half))
            context.addLine(to: CGPoint(x: base, y: y + head.half))
            context.closePath()
            context.drawPath(using: .fillStroke)
        }
        arrow(at: end, direction: 1)
        if kind == .doubleArrow { arrow(at: start, direction: -1) }
    }

    // MARK: - 그리기

    /// 획을 지나는 부드러운 경로(px).
    static func strokePath(_ stroke: DrawingStroke, canvas: CGSize) -> CGPath {
        let path = CGMutablePath()
        let points = stroke.points.map { CGPoint(x: $0.x * canvas.width, y: $0.y * canvas.height) }
        guard let first = points.first else { return path }
        path.move(to: first)
        if points.count == 1 {
            path.addLine(to: CGPoint(x: first.x + 0.01, y: first.y))
            return path
        }
        if points.count > 2 {
            for i in 1..<(points.count - 1) {
                let mid = CGPoint(x: (points[i].x + points[i + 1].x) / 2, y: (points[i].y + points[i + 1].y) / 2)
                path.addQuadCurve(to: mid, control: points[i])
            }
        }
        path.addLine(to: points[points.count - 1])
        return path
    }

    // 지우개는 이 레이어만 지운다.
    private static func drawStrokes(_ context: CGContext, canvas: CGSize, strokes: [DrawingStroke]) {
        context.saveGState()
        context.beginTransparencyLayer(auxiliaryInfo: nil)
        let shortEdge = min(canvas.width, canvas.height)
        for stroke in strokes {
            context.saveGState()
            context.setLineWidth(stroke.widthRatio * shortEdge)
            context.setLineJoin(.round)
            context.setLineCap(stroke.brush == .marker ? .square : .round)
            if stroke.brush == .eraser {
                context.setBlendMode(.destinationOut)
                context.setStrokeColor(CGColor(gray: 0, alpha: 1))
            } else {
                let alpha = min(1, max(0, stroke.opacity)) * (stroke.brush == .highlighter ? highlighterAlpha : 1)
                context.setStrokeColor(CGColor.argb(stroke.color | 0xFF00_0000).copy(alpha: alpha * CGFloat((stroke.color >> 24) & 0xFF) / 255)!)
            }
            context.addPath(strokePath(stroke, canvas: canvas))
            context.strokePath()
            context.restoreGState()
        }
        context.endTransparencyLayer()
        context.restoreGState()
    }
}

/// 이모지 스티커 id(`emoji:<글자>`).
enum EmojiCatalog {
    static let prefix = "emoji:"

    static func emoji(of assetId: String) -> String? {
        guard assetId.hasPrefix(prefix) else { return nil }
        let value = String(assetId.dropFirst(prefix.count))
        return value.isEmpty ? nil : value
    }
}

/// 호스트가 등록한 스티커 이미지. id는 `sticker:<호스트 id>`.
enum StickerAssets {
    nonisolated(unsafe) private static var images: [String: UIImage] = [:]
    nonisolated(unsafe) private static var labels: [String: String] = [:]
    private static let lock = NSLock()

    static func install(_ entries: [String: UIImage], labels: [String: String] = [:]) {
        lock.withLock {
            images = entries
            self.labels = labels
        }
    }

    static func label(for assetId: String) -> String? { lock.withLock { labels[assetId] } }

    static func image(for assetId: String) -> UIImage? {
        lock.withLock { images[assetId] }
    }

    static var ids: [String] { lock.withLock { Array(images.keys).sorted() } }
}

/// 호스트가 등록한 글꼴(PostScript 이름).
enum FontAssets {
    nonisolated(unsafe) private static var names: [String: String] = [:]
    nonisolated(unsafe) private static var labels: [String: String] = [:]
    private static let lock = NSLock()

    static func install(_ entries: [String: String], labels: [String: String] = [:]) {
        lock.withLock {
            names = entries
            self.labels = labels
        }
    }

    static func label(for id: String) -> String? { lock.withLock { labels[id] } }

    static var ids: [String] { lock.withLock { Array(names.keys).sorted() } }

    static func font(for id: String, size: CGFloat) -> UIFont? {
        guard let name = lock.withLock({ names[id] }) else { return nil }
        return UIFont(name: name, size: size)
    }
}
