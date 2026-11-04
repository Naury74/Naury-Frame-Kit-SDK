import CoreImage
import CoreVideo
import Foundation
import Vision

/// 배경 제거 마스크(업라이트 원본에 맞춘 회색조, 흰색이 남는 부분). 같은 객체끼리만 같다.
final class CutoutMask: Hashable, @unchecked Sendable {
    let image: CGImage

    init(image: CGImage) { self.image = image }

    static func == (a: CutoutMask, b: CutoutMask) -> Bool { a === b }
    func hash(into hasher: inout Hasher) { hasher.combine(ObjectIdentifier(self)) }
}

/// 기기 안에서 피사체를 찾아 배경 마스크를 만든다(Vision).
enum SubjectCutout {
    /// 이 기기에서 피사체 분리를 할 수 있는지. iOS 17 이상에서 작은 이미지로 한 번 시험해 보고 기억한다.
    static let isSupported: Bool = {
        guard #available(iOS 17, *) else { return false }
        guard let context = BitmapContext.make(width: 64, height: 64) else { return false }
        context.setFillColor(CGColor(gray: 0.5, alpha: 1))
        context.fill(CGRect(x: 0, y: 0, width: 64, height: 64))
        guard let image = context.makeImage() else { return false }
        do {
            try VNImageRequestHandler(cgImage: image, options: [:]).perform([VNGenerateForegroundInstanceMaskRequest()])
            return true
        } catch {
            frameKitLog.info("subject cutout unavailable: \(error.localizedDescription, privacy: .public)")
            return false
        }
    }()

    /// - Throws: 기기가 지원하지 않으면 `unsupportedOperation`, 피사체가 없으면 `invalidSource`.
    static func mask(for image: CGImage) throws(EditorError) -> CutoutMask {
        guard #available(iOS 17, *) else { throw .logged(.unsupportedOperation, "cutout needs iOS 17") }
        let request = VNGenerateForegroundInstanceMaskRequest()
        let handler = VNImageRequestHandler(cgImage: image, options: [:])
        do {
            try handler.perform([request])
        } catch {
            throw .logged(.unsupportedOperation, "cutout: \(error.localizedDescription)")
        }
        guard let observation = request.results?.first, !observation.allInstances.isEmpty else {
            throw .logged(.invalidSource, "no subject")
        }
        let buffer: CVPixelBuffer
        do {
            buffer = try observation.generateScaledMaskForImage(forInstances: observation.allInstances, from: handler)
        } catch {
            throw .logged(.unsupportedOperation, "cutout mask: \(error.localizedDescription)")
        }
        let context = CIContext()
        guard let cg = context.createCGImage(CIImage(cvPixelBuffer: buffer), from: CGRect(x: 0, y: 0, width: image.width, height: image.height),
                                             format: .L8, colorSpace: CGColorSpaceCreateDeviceGray()) else {
            throw .logged(.insufficientMemory, "cutout mask image")
        }
        return CutoutMask(image: cg)
    }
}
