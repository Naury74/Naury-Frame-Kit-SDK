import CoreGraphics
import Foundation
import UIKit
internal import FrameKitCore

/// 한 시각의 결과 그림을 그린다. 미리 보기와 저장이 같은 계산을 쓴다. 순서: 원본 → 색 → 텍스트·스티커.
enum MotionRenderer {
    static let kenBurnsZoom = 0.15
    static let kenBurnsPan = 0.12

    /// - Parameters:
    ///   - timeMs: 결과 시각(밀리초).
    ///   - animateOverlays: `false`면 텍스트·스티커를 움직임 없이 놓인 자리에 그린다.
    ///   - colorSpec: 미리 계산한 색 설정. `nil`이면 색을 바꾸지 않는다.
    static func render(_ project: MotionProject, media: MotionMedia, timeMs: Int64, size: CGSize, colorSpec: ColorEffectSpec?,
                       animateOverlays: Bool = true) -> CGImage? {
        let width = Int(size.width), height = Int(size.height)
        guard width > 0, height > 0, let context = BitmapContext.make(width: width, height: height) else { return nil }
        // 위에서 아래로 가는 좌표.
        context.translateBy(x: 0, y: size.height)
        context.scaleBy(x: 1, y: -1)
        context.interpolationQuality = .high
        context.setFillColor(project.fit == .fit ? CGColor.argb(project.backgroundArgb) : CGColor(gray: 0, alpha: 1))
        context.fill(CGRect(origin: .zero, size: size))
        switch project.kind {
        case .animation:
            if let animation = media.animation, !animation.frames.isEmpty {
                let frame = animation.frames[MotionTimeline.animationFrame(project, media, timeMs)]
                drawFitted(context, frame, fit: project.fit, size: size, scale: 1, offsetX: 0, alpha: 1)
            }
        case .slideshow:
            drawSlides(context, project, media, timeMs, size)
        }
        guard var image = context.makeImage() else { return nil }
        if let colorSpec, let colored = try? (ColorPipeline.shared.map { try $0.apply(image, spec: colorSpec, includeCanvasEffects: true) }
            ?? CpuColorPipeline.apply(image, spec: colorSpec, includeCanvasEffects: true)) {
            image = colored
        }
        guard !project.overlays.isEmpty else { return image }
        guard let layer = BitmapContext.make(width: width, height: height) else { return image }
        layer.draw(image, in: CGRect(origin: .zero, size: size))
        layer.translateBy(x: 0, y: size.height)
        layer.scaleBy(x: 1, y: -1)
        let overlays = project.overlays.map { animateOverlays ? animate($0, timeMs: timeMs) : $0.overlay }
        OverlayRenderer.draw(in: layer, canvas: size, overlays: overlays, drawing: [])
        return layer.makeImage() ?? image
    }

    private static func drawSlides(_ context: CGContext, _ project: MotionProject, _ media: MotionMedia, _ timeMs: Int64, _ size: CGSize) {
        guard !project.slides.isEmpty else { return }
        let position = MotionTimeline.slideAt(project, media, timeMs)
        let current = position.index
        guard let p = position.transition else {
            drawSlide(context, project, media, current, position.localMs, size, alpha: 1, extraScale: 1, offsetX: 0)
            return
        }
        let e = p * p * (3 - 2 * p)
        // 앞 사진은 움직임이 끝난 모습으로 멈춰 있고 새 사진은 처음부터 움직인다.
        let previousLocal = project.slideDurationMs
        switch project.transition {
        case .fade:
            drawSlide(context, project, media, current - 1, previousLocal, size, alpha: 1, extraScale: 1, offsetX: 0)
            drawSlide(context, project, media, current, position.localMs, size, alpha: e, extraScale: 1, offsetX: 0)
        case .slide:
            drawSlide(context, project, media, current - 1, previousLocal, size, alpha: 1, extraScale: 1, offsetX: -e * size.width)
            drawSlide(context, project, media, current, position.localMs, size, alpha: 1, extraScale: 1, offsetX: (1 - e) * size.width)
        case .zoom:
            drawSlide(context, project, media, current, position.localMs, size, alpha: 1, extraScale: 1, offsetX: 0)
            drawSlide(context, project, media, current - 1, previousLocal, size, alpha: 1 - e, extraScale: 1 + 0.3 * e, offsetX: 0)
        case .none:
            drawSlide(context, project, media, current, position.localMs, size, alpha: 1, extraScale: 1, offsetX: 0)
        }
    }

    private static func drawSlide(_ context: CGContext, _ project: MotionProject, _ media: MotionMedia, _ index: Int, _ localMs: Int64,
                                  _ size: CGSize, alpha: Double, extraScale: Double, offsetX: Double) {
        let position = min(max(0, index), project.slides.count - 1)
        guard let image = media.slide(project.slides[position]) else { return }
        let q = min(max(0, Double(localMs) / Double(project.slideDurationMs)), 1)
        var scale = 1.0, panX = 0.0
        switch MotionTimeline.cameraMotion(project, index) {
        case .zoomIn: scale = 1 + kenBurnsZoom * q
        case .zoomOut: scale = 1 + kenBurnsZoom * (1 - q)
        case .panLeft, .panRight:
            scale = 1 + kenBurnsPan
            let margin = size.width * kenBurnsPan / 2
            let direction: Double = MotionTimeline.cameraMotion(project, index) == .panLeft ? 1 : -1
            panX = direction * margin * (1 - 2 * q)
        case .none, .auto: break
        }
        drawFitted(context, image, fit: project.fit, size: size, scale: scale * extraScale, offsetX: offsetX + panX, alpha: alpha)
    }

    private static func drawFitted(_ context: CGContext, _ image: CGImage, fit: MotionFit, size: CGSize, scale: Double, offsetX: Double, alpha: Double) {
        guard alpha > 0 else { return }
        let w = Double(image.width), h = Double(image.height)
        let sx = size.width / w, sy = size.height / h
        let s = (fit == .fill ? max(sx, sy) : min(sx, sy)) * scale
        let rect = CGRect(x: size.width / 2 + offsetX - w * s / 2, y: size.height / 2 - h * s / 2, width: w * s, height: h * s)
        context.saveGState()
        context.setAlpha(alpha)
        // 위아래가 뒤집힌 좌표라 그림만 다시 뒤집어 그린다.
        context.translateBy(x: 0, y: rect.maxY + rect.minY)
        context.scaleBy(x: 1, y: -1)
        context.draw(image, in: rect)
        context.restoreGState()
    }

    /// [timeMs](결과 시각)의 움직임을 반영한 텍스트·스티커.
    static func animate(_ item: MotionOverlay, timeMs: Int64) -> Overlay {
        var overlay = item.overlay
        var t = overlay.transform
        let ms = Double(timeMs)
        func clamp(_ v: Double) -> Double { min(max(v, 0), 1) }
        switch item.animation {
        case .none: return overlay
        case .fadeIn: t.opacity *= clamp(ms / 500)
        case .pop:
            t.scale *= max(0.05, backOut(clamp(ms / 450)))
            t.opacity *= clamp(ms / 150)
        case .slideUp:
            let e = 1 - pow(1 - clamp(ms / 500), 3)
            t.center.y += 0.12 * (1 - e)
            t.opacity *= clamp(ms / 500)
        case .bounce: t.center.y -= 0.025 * abs(sin(.pi * ms / 500))
        case .wiggle: t.rotationDegrees += 7 * sin(2 * .pi * ms / 700)
        case .pulse: t.scale *= 1 + 0.07 * sin(2 * .pi * ms / 900)
        }
        overlay.transform = t
        return overlay
    }

    private static func backOut(_ t: Double) -> Double {
        let c = 1.70158, x = t - 1
        return 1 + (c + 1) * x * x * x + c * x * x
    }
}
