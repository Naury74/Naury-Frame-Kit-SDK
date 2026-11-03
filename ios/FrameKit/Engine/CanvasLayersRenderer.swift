import CoreGraphics
import UIKit
internal import FrameKitCore

/// 색 보정이 끝난 이미지에 가리기 → 그리기 → 요소 → 워터마크를 차례로 그린다(Android와 같은 순서).
enum CanvasLayersRenderer {
    static func apply(_ image: CGImage, edit: PhotoEdit) throws(EditorError) -> CGImage {
        guard edit.hasCanvasLayers else { return image }
        let width = image.width, height = image.height
        try MemoryBudget.check(bytes: width * height * 4 * 3)
        guard let context = BitmapContext.make(width: width, height: height) else {
            throw .logged(.insufficientMemory, "layers context")
        }
        context.draw(image, in: CGRect(x: 0, y: 0, width: width, height: height))
        context.translateBy(x: 0, y: CGFloat(height))
        context.scaleBy(x: 1, y: -1)
        let canvas = CGSize(width: width, height: height)
        if !edit.privacyMasks.isEmpty {
            PrivacyRenderer.apply(edit.privacyMasks, base: image, context: context, canvas: canvas)
        }
        OverlayRenderer.draw(in: context, canvas: canvas, overlays: edit.overlays, drawing: edit.drawing)
        if let watermark = edit.watermark, watermark.isValid {
            UIGraphicsPushContext(context)
            WatermarkRenderer.draw(watermark, canvas: canvas, context: context)
            UIGraphicsPopContext()
        }
        guard let result = context.makeImage() else { throw .logged(.insufficientMemory, "layers makeImage") }
        return result
    }
}

/// 가리기. 모든 영역은 가리기 전 이미지를 읽으므로 겹쳐도 두 번 흐려지지 않는다.
enum PrivacyRenderer {
    private static let maxDirectSigma = 8.0

    static func apply(_ masks: [PrivacyMask], base: CGImage, context: CGContext, canvas: CGSize) {
        let shortEdge = min(canvas.width, canvas.height)
        for mask in masks {
            let path = shapePath(mask.shape, canvas: canvas)
            var brushWidth: CGFloat = 0
            if case .brush(_, let ratio) = mask.shape { brushWidth = ratio * shortEdge }
            let bounds = path.boundingBoxOfPath.insetBy(dx: -brushWidth / 2 - 1, dy: -brushWidth / 2 - 1)
            let minX = max(0, floor(bounds.minX)), minY = max(0, floor(bounds.minY))
            let area = CGRect(x: minX, y: minY,
                              width: min(canvas.width, ceil(bounds.maxX)) - minX, height: min(canvas.height, ceil(bounds.maxY)) - minY)
            guard area.width >= 1, area.height >= 1 else { continue }
            let effect: (image: CGImage, origin: CGPoint)?
            switch mask.effect {
            case .mosaic(let ratio): effect = mosaic(base, area: area, block: max(1, Int((ratio * shortEdge).rounded())))
            case .blur(let ratio): effect = blur(base, area: area, sigma: ratio * shortEdge)
            }
            guard let effect else { continue }
            context.saveGState()
            context.addPath(path)
            if brushWidth > 0 {
                context.setLineWidth(brushWidth)
                context.setLineCap(.round)
                context.setLineJoin(.round)
                context.replacePathWithStrokedPath()
            }
            context.clip()
            // y 아래 방향 좌표에 이미지를 바로 세워 그린다.
            let rect = CGRect(origin: effect.origin, size: CGSize(width: effect.image.width, height: effect.image.height))
            context.translateBy(x: 0, y: rect.minY * 2 + rect.height)
            context.scaleBy(x: 1, y: -1)
            context.draw(effect.image, in: rect)
            context.restoreGState()
        }
    }

    private static func shapePath(_ shape: PrivacyMask.Shape, canvas: CGSize) -> CGPath {
        func rect(_ r: NRect) -> CGRect {
            CGRect(x: min(r.left, r.right) * canvas.width, y: min(r.top, r.bottom) * canvas.height,
                   width: abs(r.width) * canvas.width, height: abs(r.height) * canvas.height)
        }
        let path = CGMutablePath()
        switch shape {
        case .rectangle(let r): path.addRect(rect(r))
        case .ellipse(let r): path.addEllipse(in: rect(r))
        case .brush(let points, _):
            guard let first = points.first else { return path }
            let p0 = CGPoint(x: first.x * canvas.width, y: first.y * canvas.height)
            path.move(to: p0)
            if points.count == 1 { path.addLine(to: CGPoint(x: p0.x + 0.01, y: p0.y)) }
            for p in points.dropFirst() { path.addLine(to: CGPoint(x: p.x * canvas.width, y: p.y * canvas.height)) }
        }
        return path
    }

    /// 영역 픽셀(un-premultiplied ARGB). 원점은 왼쪽 위.
    private static func pixels(_ image: CGImage, _ rect: CGRect) -> (data: [UInt32], width: Int, height: Int)? {
        let w = Int(rect.width), h = Int(rect.height)
        guard w > 0, h > 0, let context = BitmapContext.make(width: w, height: h), let raw = context.data else { return nil }
        context.draw(image, in: CGRect(x: -rect.minX, y: -(CGFloat(image.height) - rect.maxY), width: CGFloat(image.width), height: CGFloat(image.height)))
        let row = context.bytesPerRow
        let bytes = raw.bindMemory(to: UInt8.self, capacity: row * h)
        var out = [UInt32](repeating: 0, count: w * h)
        for y in 0..<h {
            for x in 0..<w {
                let o = y * row + x * 4
                let a = UInt32(bytes[o + 3])
                func un(_ v: UInt8) -> UInt32 { a == 0 ? 0 : min(255, (UInt32(v) * 255 + a / 2) / a) }
                out[y * w + x] = (a << 24) | (un(bytes[o]) << 16) | (un(bytes[o + 1]) << 8) | un(bytes[o + 2])
            }
        }
        return (out, w, h)
    }

    private static func image(_ data: [UInt32], width: Int, height: Int) -> CGImage? {
        guard let context = BitmapContext.make(width: width, height: height), let raw = context.data else { return nil }
        let row = context.bytesPerRow
        let bytes = raw.bindMemory(to: UInt8.self, capacity: row * height)
        for y in 0..<height {
            for x in 0..<width {
                let p = data[y * width + x]
                let a = p >> 24
                func pre(_ v: UInt32) -> UInt8 { UInt8((v * a + 127) / 255) }
                let o = y * row + x * 4
                bytes[o] = pre((p >> 16) & 0xFF); bytes[o + 1] = pre((p >> 8) & 0xFF); bytes[o + 2] = pre(p & 0xFF); bytes[o + 3] = UInt8(a)
            }
        }
        return context.makeImage()
    }

    // 격자를 캔버스 원점에 맞춰 넓힌 뒤 블록마다 평균색으로 채운다.
    private static func mosaic(_ base: CGImage, area: CGRect, block: Int) -> (image: CGImage, origin: CGPoint)? {
        let left = Int(area.minX) / block * block
        let top = Int(area.minY) / block * block
        let right = min(base.width, Int(ceil(area.maxX / Double(block))) * block)
        let bottom = min(base.height, Int(ceil(area.maxY / Double(block))) * block)
        guard var region = pixels(base, CGRect(x: left, y: top, width: right - left, height: bottom - top)) else { return nil }
        let w = region.width, h = region.height
        var by = 0
        while by < h {
            let bh = min(block, h - by)
            var bx = 0
            while bx < w {
                let bw = min(block, w - bx)
                var a: UInt64 = 0, r: UInt64 = 0, g: UInt64 = 0, b: UInt64 = 0
                for y in by..<(by + bh) {
                    for x in bx..<(bx + bw) {
                        let p = region.data[y * w + x]
                        a += UInt64(p >> 24); r += UInt64((p >> 16) & 0xFF); g += UInt64((p >> 8) & 0xFF); b += UInt64(p & 0xFF)
                    }
                }
                let n = UInt64(bw * bh)
                let average = UInt32(a / n) << 24 | UInt32(r / n) << 16 | UInt32(g / n) << 8 | UInt32(b / n)
                for y in by..<(by + bh) { for x in bx..<(bx + bw) { region.data[y * w + x] = average } }
                bx += block
            }
            by += block
        }
        guard let result = image(region.data, width: w, height: h) else { return nil }
        return (result, CGPoint(x: left, y: top))
    }

    // 경계 밖 3σ까지 흐린다. 반경이 크면 줄여서 흐리고 다시 키운다.
    private static func blur(_ base: CGImage, area: CGRect, sigma: Double) -> (image: CGImage, origin: CGPoint)? {
        let pad = Int(ceil(3 * sigma))
        let left = max(0, Int(area.minX) - pad), top = max(0, Int(area.minY) - pad)
        let right = min(base.width, Int(area.maxX) + pad), bottom = min(base.height, Int(area.maxY) + pad)
        let w = right - left, h = bottom - top
        guard w > 0, h > 0, let crop = base.cropping(to: CGRect(x: left, y: top, width: w, height: h)) else { return nil }
        let factor = max(1, Int(ceil(sigma / maxDirectSigma)))
        let sw = max(1, w / factor), sh = max(1, h / factor)
        var small = crop
        if factor > 1 {
            guard let context = BitmapContext.make(width: sw, height: sh) else { return nil }
            context.interpolationQuality = .medium
            context.draw(crop, in: CGRect(x: 0, y: 0, width: sw, height: sh))
            guard let scaled = context.makeImage() else { return nil }
            small = scaled
        }
        guard let region = pixels(small, CGRect(x: 0, y: 0, width: sw, height: sh)) else { return nil }
        let array = KotlinIntArray(size: Int32(sw * sh))
        for i in 0..<(sw * sh) { array.set(index: Int32(i), value: Int32(bitPattern: region.data[i])) }
        let weights = ColorEffectProcessor.shared.gaussianWeights(sigma: Float(sigma / Double(factor)), maxRadius: ColorEffectSpec.companion.MAX_BLUR_RADIUS)
        let blurred = ColorEffectProcessor.shared.blur(pixels: array, width: Int32(sw), height: Int32(sh), weights: weights)
        var out = [UInt32](repeating: 0, count: sw * sh)
        for i in 0..<(sw * sh) { out[i] = UInt32(bitPattern: blurred.get(index: Int32(i))) }
        guard var result = image(out, width: sw, height: sh) else { return nil }
        if factor > 1 {
            guard let context = BitmapContext.make(width: w, height: h) else { return nil }
            context.interpolationQuality = .medium
            context.draw(result, in: CGRect(x: 0, y: 0, width: w, height: h))
            guard let scaled = context.makeImage() else { return nil }
            result = scaled
        }
        return (result, CGPoint(x: left, y: top))
    }
}

/// 워터마크. Android WatermarkRenderer와 같은 배치다.
enum WatermarkRenderer {
    private static let margin = 0.035
    private static let boxPadding = 0.35
    private static let boxAlpha = 110.0
    private static let shadowAlpha = 140.0
    private static let tileGapX = 2.5
    private static let tileGapY = 2.6
    private static let diagonalFill = 0.75
    private static let diagonalMaxHeight = 0.25
    private static let bandHeight = 1.9

    static func draw(_ watermark: WatermarkSpec, canvas: CGSize, context: CGContext) {
        guard watermark.isValid, canvas.width > 0, canvas.height > 0 else { return }
        let w = canvas.width, h = canvas.height
        let unit = min(w, h)
        let label = watermark.text.trimmingCharacters(in: .whitespaces)
        let light = luminance(watermark.color) > 0.5
        let boxColor = CGColor(gray: light ? 0 : 1, alpha: min(255, boxAlpha * watermark.opacity) / 255)
        var size = unit * watermark.sizeRatio
        func attrs() -> [NSAttributedString.Key: Any] {
            let alpha = Double((watermark.color >> 24) & 0xFF) / 255 * watermark.opacity
            var result: [NSAttributedString.Key: Any] = [
                .font: OverlayRenderer.font(watermark.fontId, size: size),
                .foregroundColor: UIColor(cgColor: CGColor.argb(watermark.color | 0xFF00_0000)).withAlphaComponent(alpha),
            ]
            if watermark.shadow {
                let s = NSShadow()
                s.shadowColor = UIColor(white: light ? 0 : 1, alpha: min(255, shadowAlpha * watermark.opacity) / 255)
                s.shadowBlurRadius = size * 0.12
                s.shadowOffset = CGSize(width: 0, height: size * 0.04)
                result[.shadow] = s
            }
            return result
        }
        func measure() -> CGSize {
            let font = OverlayRenderer.font(watermark.fontId, size: size)
            return CGSize(width: (label as NSString).size(withAttributes: [.font: font]).width, height: font.ascender - font.descender)
        }
        func drawLabel(_ left: CGFloat, _ top: CGFloat, box: Bool) {
            let m = measure()
            if box {
                let pad = size * boxPadding
                let rect = CGRect(x: left - pad * 1.4, y: top - pad, width: m.width + pad * 2.8, height: m.height + pad * 2)
                context.setFillColor(boxColor)
                context.addPath(CGPath(roundedRect: rect, cornerWidth: rect.height / 2, cornerHeight: rect.height / 2, transform: nil))
                context.fillPath()
            }
            (label as NSString).draw(at: CGPoint(x: left, y: top), withAttributes: attrs())
        }
        func horizontal(_ width: CGFloat, margin: CGFloat) -> CGFloat {
            switch watermark.position.horizontal {
            case -1: margin
            case 1: w - margin - width
            default: (w - width) / 2
            }
        }

        switch watermark.pattern {
        case .single:
            let m = measure()
            let inset = watermark.background ? size * boxPadding * 1.4 : 0
            let edge = unit * margin + inset
            let top: CGFloat = switch watermark.position.vertical {
            case -1: edge
            case 1: h - edge - m.height
            default: (h - m.height) / 2
            }
            drawLabel(horizontal(m.width, margin: edge), top, box: watermark.background)
        case .tile:
            let m = measure()
            let stepX = m.width + size * tileGapX / watermark.density
            let stepY = m.height + size * tileGapY / watermark.density
            let reach = hypot(w, h) / 2 + stepX
            let rows = Int(ceil(reach / stepY)), cols = Int(ceil(reach / stepX)) + 1
            context.saveGState()
            context.translateBy(x: w / 2, y: h / 2)
            context.rotate(by: watermark.rotationDegrees * .pi / 180)
            context.translateBy(x: -w / 2, y: -h / 2)
            for row in -rows...rows {
                let shift = row % 2 == 0 ? 0 : stepX / 2
                for col in -cols...cols {
                    let cx = w / 2 + CGFloat(col) * stepX + shift
                    let cy = h / 2 + CGFloat(row) * stepY
                    drawLabel(cx - m.width / 2, cy - m.height / 2, box: watermark.background)
                }
            }
            context.restoreGState()
        case .diagonal:
            let reference: CGFloat = 100
            size = reference
            let measured = max(1, measure().width)
            size = min(reference * hypot(w, h) * diagonalFill / measured, min(w, h) * diagonalMaxHeight)
            let m = measure()
            context.saveGState()
            context.translateBy(x: w / 2, y: h / 2)
            context.rotate(by: -atan2(h, w))
            context.translateBy(x: -w / 2, y: -h / 2)
            drawLabel((w - m.width) / 2, (h - m.height) / 2, box: false)
            context.restoreGState()
        case .band:
            let m = measure()
            let band = m.height * bandHeight
            let top = watermark.position.vertical < 0 ? 0 : h - band
            context.setFillColor(boxColor)
            context.fill(CGRect(x: 0, y: top, width: w, height: band))
            drawLabel(horizontal(m.width, margin: unit * margin), top + (band - m.height) / 2, box: false)
        }
    }

    private static func luminance(_ argb: UInt32) -> Double {
        (0.299 * Double((argb >> 16) & 0xFF) + 0.587 * Double((argb >> 8) & 0xFF) + 0.114 * Double(argb & 0xFF)) / 255
    }
}
