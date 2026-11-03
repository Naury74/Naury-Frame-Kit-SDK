import AVFoundation
import CoreImage
import UIKit
internal import FrameKitCore

/// 프레임 하나를 그리는 데 필요한 클립 정보.
final class VideoInstruction: NSObject, AVVideoCompositionInstructionProtocol, @unchecked Sendable {
    let timeRange: CMTimeRange
    let enablePostProcessing = false
    let containsTweening = true
    let requiredSourceTrackIDs: [NSValue]?
    let passthroughTrackID = kCMPersistentTrackID_Invalid
    let trackID: CMPersistentTrackID
    /// 원본 프레임(y 아래 방향) → 결과 화면(y 아래 방향).
    let transform: CGAffineTransform
    let colorSpec: ColorEffectSpec?
    let frame: VideoFrameExtras

    init(timeRange: CMTimeRange, trackID: CMPersistentTrackID, transform: CGAffineTransform, colorSpec: ColorEffectSpec?, frame: VideoFrameExtras) {
        self.timeRange = timeRange
        self.trackID = trackID
        self.requiredSourceTrackIDs = [NSNumber(value: trackID)]
        self.transform = transform
        self.colorSpec = colorSpec
        self.frame = frame
    }
}

/// 모든 프레임에 공통인 정보: 화면 크기, 시간이 정해진 가리기·요소, 워터마크.
final class VideoFrameExtras: @unchecked Sendable {
    let size: CGSize
    let masks: [TimedMask]
    let overlays: [TimedOverlay]
    let watermark: WatermarkSpec?
    /// 켜진 요소 조합별로 그린 겹침 이미지.
    private var overlayCache: [String: CIImage] = [:]
    private var maskCache: [String: CIImage] = [:]
    private let lock = NSLock()

    init(size: CGSize, masks: [TimedMask], overlays: [TimedOverlay], watermark: WatermarkSpec?) {
        self.size = size
        self.masks = masks
        self.overlays = overlays
        self.watermark = watermark?.isValid == true ? watermark : nil
    }

    var isEmpty: Bool { masks.isEmpty && overlays.isEmpty && watermark == nil }

    /// [t]에 켜진 요소와 워터마크를 그린 이미지(CI 좌표).
    func overlayImage(at t: Micros) -> CIImage? {
        let active = overlays.filter { $0.range.contains(t) }
        guard !active.isEmpty || watermark != nil else { return nil }
        let key = active.map(\.overlay.id).joined(separator: "|")
        if let cached = lock.withLock({ overlayCache[key] }) { return cached }
        let w = Int(size.width), h = Int(size.height)
        guard let context = BitmapContext.make(width: w, height: h) else { return nil }
        context.translateBy(x: 0, y: CGFloat(h))
        context.scaleBy(x: 1, y: -1)
        OverlayRenderer.draw(in: context, canvas: size, overlays: active.map(\.overlay), drawing: [])
        if let watermark {
            UIGraphicsPushContext(context)
            WatermarkRenderer.draw(watermark, canvas: size, context: context)
            UIGraphicsPopContext()
        }
        guard let cg = context.makeImage() else { return nil }
        let image = CIImage(cgImage: cg)
        lock.withLock {
            if overlayCache.count > 16 { overlayCache.removeAll() }
            overlayCache[key] = image
        }
        return image
    }

    /// 가리기 모양(흰색이 가릴 부분, CI 좌표).
    func maskShape(_ mask: PrivacyMask) -> CIImage? {
        if let cached = lock.withLock({ maskCache[mask.id] }) { return cached }
        let w = Int(size.width), h = Int(size.height)
        guard let context = CGContext(data: nil, width: w, height: h, bitsPerComponent: 8, bytesPerRow: 0,
                                      space: CGColorSpaceCreateDeviceGray(), bitmapInfo: CGImageAlphaInfo.none.rawValue) else { return nil }
        context.translateBy(x: 0, y: CGFloat(h))
        context.scaleBy(x: 1, y: -1)
        context.setFillColor(gray: 1, alpha: 1)
        switch mask.shape {
        case .rectangle(let r): context.fill(rect(r))
        case .ellipse(let r): context.fillEllipse(in: rect(r))
        case .brush: return nil
        }
        guard let cg = context.makeImage() else { return nil }
        let image = CIImage(cgImage: cg)
        lock.withLock { maskCache[mask.id] = image }
        return image
    }

    private func rect(_ r: NRect) -> CGRect {
        CGRect(x: min(r.left, r.right) * size.width, y: min(r.top, r.bottom) * size.height,
               width: abs(r.width) * size.width, height: abs(r.height) * size.height)
    }
}

/// 프레임마다 방향·기하·화면 비율·색·가리기·요소를 적용하는 합성기.
final class FrameKitCompositor: NSObject, AVVideoCompositing, @unchecked Sendable {
    private let queue = DispatchQueue(label: "com.naurylab.framekit.compositor")
    private let context = CIContext(options: [.cacheIntermediates: false])
    private var cancelled = false

    let sourcePixelBufferAttributes: [String: any Sendable]? = [kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA]
    let requiredPixelBufferAttributesForRenderContext: [String: any Sendable] = [kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA]

    func renderContextChanged(_ newRenderContext: AVVideoCompositionRenderContext) {}

    func cancelAllPendingVideoCompositionRequests() {
        queue.sync { cancelled = true }
        queue.async { self.cancelled = false }
    }

    func startRequest(_ request: AVAsynchronousVideoCompositionRequest) {
        queue.async {
            if self.cancelled { request.finishCancelledRequest(); return }
            guard let instruction = request.videoCompositionInstruction as? VideoInstruction,
                  let output = request.renderContext.newPixelBuffer() else {
                request.finish(with: NSError(domain: "FrameKit", code: 1))
                return
            }
            let frame = instruction.frame
            let size = frame.size
            let canvas = CGRect(origin: .zero, size: size)
            var image = CIImage(color: .black).cropped(to: canvas)
            if let buffer = request.sourceFrame(byTrackID: instruction.trackID) {
                let source = CIImage(cvPixelBuffer: buffer)
                let h = source.extent.height
                // 합성 계산은 y 아래 방향이라 들어올 때·나갈 때 위아래를 뒤집는다.
                let toTopDown = CGAffineTransform(a: 1, b: 0, c: 0, d: -1, tx: 0, ty: h)
                let toCI = CGAffineTransform(a: 1, b: 0, c: 0, d: -1, tx: 0, ty: size.height)
                let placed = source.transformed(by: toTopDown.concatenating(instruction.transform).concatenating(toCI))
                image = placed.cropped(to: canvas).composited(over: image)
            }
            if let spec = instruction.colorSpec, let pipeline = ColorPipeline.shared {
                image = pipeline.videoApply(image, spec: spec, width: size.width, height: size.height)
            }
            let t = request.compositionTime.micros
            let shortEdge = min(size.width, size.height)
            for timed in frame.masks where timed.range.contains(t) {
                guard let shape = frame.maskShape(timed.mask) else { continue }
                let effect: CIImage
                switch timed.mask.effect {
                case .mosaic(let ratio):
                    let block = max(1, ratio * shortEdge)
                    effect = image.applyingFilter("CIPixellate", parameters: [kCIInputScaleKey: block,
                                                                               kCIInputCenterKey: CIVector(x: 0, y: size.height)])
                case .blur(let ratio):
                    effect = image.clampedToExtent().applyingFilter("CIGaussianBlur", parameters: [kCIInputRadiusKey: max(1, ratio * shortEdge)])
                }
                image = effect.cropped(to: canvas).applyingFilter("CIBlendWithMask", parameters: [
                    kCIInputBackgroundImageKey: image, kCIInputMaskImageKey: shape,
                ])
            }
            if let overlay = frame.overlayImage(at: t) {
                image = overlay.composited(over: image)
            }
            self.context.render(image.cropped(to: canvas), to: output, bounds: canvas, colorSpace: BitmapContext.sRGB)
            request.finish(withComposedVideoFrame: output)
        }
    }
}
