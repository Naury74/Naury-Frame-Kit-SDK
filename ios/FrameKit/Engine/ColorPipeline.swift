import CoreImage
import Foundation
internal import FrameKitCore

/// 색 보정을 GPU(Core Image + Metal)로 적용한다. 단계마다 8비트로 양자화해 Android와 같은 값을 낸다.
final class ColorPipeline: @unchecked Sendable {
    static let shared: ColorPipeline? = try? ColorPipeline()

    private let context: CIContext
    private let colorKernel: CIColorKernel
    private let blurKernel: CIKernel
    private let finishKernel: CIKernel

    private init() throws {
        guard let url = Bundle(for: ColorPipeline.self).url(forResource: "default", withExtension: "metallib") else {
            throw EditorError(code: .unsupportedOperation)
        }
        let data = try Data(contentsOf: url)
        colorKernel = try CIColorKernel(functionName: "fkColor", fromMetalLibraryData: data)
        blurKernel = try CIKernel(functionName: "fkBlur", fromMetalLibraryData: data)
        finishKernel = try CIKernel(functionName: "fkFinish", fromMetalLibraryData: data)
        // 색 관리를 끄고 sRGB 값 그대로 계산한다. 중간 결과는 8비트.
        context = CIContext(options: [
            .workingColorSpace: NSNull(),
            .outputColorSpace: NSNull(),
            .workingFormat: CIFormat.RGBA8.rawValue,
            .cacheIntermediates: false,
        ])
    }

    /// [image]에 [spec]을 적용한 새 이미지. 바꿀 것이 없으면 [image]를 그대로 돌려준다.
    /// - Parameter includeCanvasEffects: `false`면 비네트·그레인을 뺀다(자르기 전 미리 보기).
    func apply(_ image: CGImage, spec: ColorEffectSpec, includeCanvasEffects: Bool) throws -> CGImage {
        let vignette = includeCanvasEffects ? spec.vignetteStrength : 0
        let grain = includeCanvasEffects ? spec.grainAmount : 0
        let hasPoint = !spec.isIdentity
        let hasFinish = spec.sharpenAmount != 0 || spec.fadeLift != 0 || vignette != 0 || grain != 0
        guard hasPoint else { return image }

        let width = CGFloat(image.width)
        let height = CGFloat(image.height)
        let extent = CGRect(x: 0, y: 0, width: width, height: height)
        let input = CIImage(cgImage: image, options: [.colorSpace: NSNull()])

        let wb = spec.whiteBalance
        let grade = spec.grade
        let colored = colorKernel.apply(extent: extent, arguments: [
            input,
            CIVector(x: CGFloat(wb.red), y: CGFloat(wb.green), z: CGFloat(wb.blue)),
            CIVector(x: CGFloat(spec.exposureGain), y: CGFloat(spec.shadows), z: CGFloat(spec.highlights), w: CGFloat(spec.brightnessOffset)),
            CIVector(x: CGFloat(spec.contrastFactor), y: CGFloat(spec.saturationFactor), z: CGFloat(spec.filterIntensity), w: grade == nil ? 0 : 1),
            CIVector(x: CGFloat(grade?.gains.red ?? 1), y: CGFloat(grade?.gains.green ?? 1), z: CGFloat(grade?.gains.blue ?? 1)),
            CIVector(x: CGFloat(grade?.brightnessOffset ?? 0), y: CGFloat(grade?.contrastFactor ?? 1),
                     z: CGFloat(grade?.saturationFactor ?? 1), w: CGFloat(grade?.fadeLift ?? 0)),
            CIVector(x: CGFloat(grade?.shadowTint.red ?? 0), y: CGFloat(grade?.shadowTint.green ?? 0), z: CGFloat(grade?.shadowTint.blue ?? 0)),
            CIVector(x: CGFloat(grade?.highlightTint.red ?? 0), y: CGFloat(grade?.highlightTint.green ?? 0), z: CGFloat(grade?.highlightTint.blue ?? 0)),
        ])
        guard var output = colored?.insertingIntermediate(cache: false) else { throw EditorError(code: .unknown) }

        if hasFinish {
            let shortEdge = Int32(min(image.width, image.height))
            var blurred = output
            let sharpen = spec.sharpenAmount != 0
            if sharpen {
                let sigma = CGFloat(spec.sharpenSigma(shortEdgePx: shortEdge))
                let radius = CGFloat(min(Int(ColorEffectSpec.companion.MAX_BLUR_RADIUS), Int(ceil(3 * sigma))))
                blurred = try blur(output, extent: extent, direction: CIVector(x: 1, y: 0), sigma: sigma, radius: radius)
                blurred = try blur(blurred, extent: extent, direction: CIVector(x: 0, y: 1), sigma: sigma, radius: radius)
            }
            let seed = UInt32(bitPattern: spec.grainSeed)
            let cells = CGFloat(ColorEffectSpec.companion.GRAIN_CELLS_SHORT_EDGE) / CGFloat(shortEdge)
            let finished = finishKernel.apply(
                extent: extent,
                roiCallback: { _, rect in rect },
                arguments: [
                    output, blurred,
                    CIVector(x: CGFloat(spec.sharpenAmount), y: CGFloat(spec.fadeLift), z: CGFloat(vignette), w: CGFloat(grain)),
                    CIVector(x: width, y: height, z: cells, w: sharpen ? 1 : 0),
                    CIVector(x: CGFloat(seed & 0xFFFF), y: CGFloat(seed >> 16)),
                ]
            )
            guard let finished else { throw EditorError(code: .unknown) }
            output = finished
        }

        guard let result = context.createCGImage(output, from: extent, format: .RGBA8, colorSpace: CGColorSpace(name: CGColorSpace.sRGB))
        else { throw EditorError(code: .insufficientMemory) }
        return result
    }

    /// 영상 프레임용 한 번 계산(선명도 없음). Android 영상 색 보정과 같다.
    func videoApply(_ image: CIImage, spec: ColorEffectSpec, width: CGFloat, height: CGFloat) -> CIImage {
        guard !spec.isIdentity else { return image }
        let extent = CGRect(x: 0, y: 0, width: width, height: height)
        let wb = spec.whiteBalance
        let grade = spec.grade
        guard let colored = colorKernel.apply(extent: extent, arguments: [
            image,
            CIVector(x: CGFloat(wb.red), y: CGFloat(wb.green), z: CGFloat(wb.blue)),
            CIVector(x: CGFloat(spec.exposureGain), y: CGFloat(spec.shadows), z: CGFloat(spec.highlights), w: CGFloat(spec.brightnessOffset)),
            CIVector(x: CGFloat(spec.contrastFactor), y: CGFloat(spec.saturationFactor), z: CGFloat(spec.filterIntensity), w: grade == nil ? 0 : 1),
            CIVector(x: CGFloat(grade?.gains.red ?? 1), y: CGFloat(grade?.gains.green ?? 1), z: CGFloat(grade?.gains.blue ?? 1)),
            CIVector(x: CGFloat(grade?.brightnessOffset ?? 0), y: CGFloat(grade?.contrastFactor ?? 1),
                     z: CGFloat(grade?.saturationFactor ?? 1), w: CGFloat(grade?.fadeLift ?? 0)),
            CIVector(x: CGFloat(grade?.shadowTint.red ?? 0), y: CGFloat(grade?.shadowTint.green ?? 0), z: CGFloat(grade?.shadowTint.blue ?? 0)),
            CIVector(x: CGFloat(grade?.highlightTint.red ?? 0), y: CGFloat(grade?.highlightTint.green ?? 0), z: CGFloat(grade?.highlightTint.blue ?? 0)),
        ]) else { return image }
        guard spec.fadeLift != 0 || spec.vignetteStrength != 0 || spec.grainAmount != 0 else { return colored }
        let seed = UInt32(bitPattern: spec.grainSeed)
        let cells = CGFloat(ColorEffectSpec.companion.GRAIN_CELLS_SHORT_EDGE) / min(width, height)
        return finishKernel.apply(extent: extent, roiCallback: { _, rect in rect }, arguments: [
            colored, colored,
            CIVector(x: 0, y: CGFloat(spec.fadeLift), z: CGFloat(spec.vignetteStrength), w: CGFloat(spec.grainAmount)),
            CIVector(x: width, y: height, z: cells, w: 0),
            CIVector(x: CGFloat(seed & 0xFFFF), y: CGFloat(seed >> 16)),
        ]) ?? colored
    }

    private func blur(_ image: CIImage, extent: CGRect, direction: CIVector, sigma: CGFloat, radius: CGFloat) throws -> CIImage {
        let padX = radius * abs(direction.x) + 1
        let padY = radius * abs(direction.y) + 1
        let result = blurKernel.apply(
            extent: extent,
            roiCallback: { _, rect in rect.insetBy(dx: -padX, dy: -padY) },
            arguments: [image.clampedToExtent(), direction, sigma, radius]
        )
        guard let result else { throw EditorError(code: .unknown) }
        return result.cropped(to: extent).insertingIntermediate(cache: false)
    }
}

/// GPU를 쓸 수 없을 때 core CPU 구현으로 같은 결과를 만든다. 느리므로 대체 경로로만 쓴다.
enum CpuColorPipeline {
    static func apply(_ image: CGImage, spec: ColorEffectSpec, includeCanvasEffects: Bool) throws(EditorError) -> CGImage {
        let width = image.width
        let height = image.height
        try MemoryBudget.check(bytes: width * height * 12)
        guard let context = BitmapContext.make(width: width, height: height), let data = context.data else {
            throw .logged(.insufficientMemory, "cpu context")
        }
        context.draw(image, in: CGRect(x: 0, y: 0, width: width, height: height))
        let rowBytes = context.bytesPerRow
        let bytes = data.bindMemory(to: UInt8.self, capacity: rowBytes * height)
        let pixels = KotlinIntArray(size: Int32(width * height))
        // RGBA premultiplied → un-premultiplied 0xAARRGGBB
        for y in 0..<height {
            for x in 0..<width {
                let o = y * rowBytes + x * 4
                let a = UInt32(bytes[o + 3])
                func un(_ v: UInt8) -> UInt32 { a == 0 ? 0 : min(255, (UInt32(v) * 255 + a / 2) / a) }
                let argb = (a << 24) | (un(bytes[o]) << 16) | (un(bytes[o + 1]) << 8) | un(bytes[o + 2])
                pixels.set(index: Int32(y * width + x), value: Int32(bitPattern: argb))
            }
        }
        do {
            try ColorEffectProcessor.shared.process(pixels: pixels, width: Int32(width), height: Int32(height), spec: spec,
                                                    includeCanvasEffects: includeCanvasEffects)
        } catch {
            throw .logged(.unknown, "cpu color: \(error)")
        }
        for y in 0..<height {
            for x in 0..<width {
                let argb = UInt32(bitPattern: pixels.get(index: Int32(y * width + x)))
                let a = argb >> 24
                func pre(_ v: UInt32) -> UInt8 { UInt8((v * a + 127) / 255) }
                let o = y * rowBytes + x * 4
                bytes[o] = pre((argb >> 16) & 0xFF)
                bytes[o + 1] = pre((argb >> 8) & 0xFF)
                bytes[o + 2] = pre(argb & 0xFF)
                bytes[o + 3] = UInt8(a)
            }
        }
        guard let result = context.makeImage() else { throw .logged(.insufficientMemory, "cpu makeImage") }
        return result
    }
}
