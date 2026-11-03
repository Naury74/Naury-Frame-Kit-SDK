import CoreGraphics
import Foundation
internal import FrameKitCore

/// 기하와 색 보정을 적용해 결과 이미지를 만든다.
enum PhotoRenderer {
    /// - Parameters:
    ///   - image: 업라이트 이미지. 원본보다 작을 수 있다.
    ///   - uprightSize: 원본 업라이트 크기(px). 기하 계산은 이 크기 기준이다.
    ///   - crop: 쓸 자르기. 자르기 도구 미리 보기는 `.full`을 넘긴다.
    ///   - output: 결과 크기(px).
    ///   - background: 투명 영역을 채울 색(0xAARRGGBB). `nil`이면 투명으로 둔다.
    ///   - includeCanvasEffects: `false`면 비네트·그레인을 뺀다.
    static func render(
        image: UprightImage,
        uprightSize: PixelSizeValue,
        edit: PhotoEdit,
        crop: NRect,
        output: PixelSizeValue,
        background: UInt32?,
        includeCanvasEffects: Bool,
        applyColor: Bool = true
    ) throws(EditorError) -> CGImage {
        try MemoryBudget.check(bytes: output.width * output.height * 4 * 3)
        guard let context = BitmapContext.make(width: output.width, height: output.height) else {
            throw .logged(.insufficientMemory, "output \(output.width)x\(output.height)")
        }
        if let background {
            context.setFillColor(CGColor.argb(background | 0xFF00_0000))
            context.fill(CGRect(x: 0, y: 0, width: output.width, height: output.height))
        }
        context.interpolationQuality = .high
        // core 행렬은 y가 아래로 가는 좌표다. 위아래를 뒤집어 맞춘다.
        context.translateBy(x: 0, y: CGFloat(output.height))
        context.scaleBy(x: 1, y: -1)
        let math = GeometryMath(source: uprightSize, geometry: edit.geometry)
        context.concatenate(math.sourceToOutput(crop: crop, output: output))
        let cg = image.cgImage
        context.scaleBy(x: CGFloat(uprightSize.width) / CGFloat(cg.width), y: CGFloat(uprightSize.height) / CGFloat(cg.height))
        context.translateBy(x: 0, y: CGFloat(cg.height))
        context.scaleBy(x: 1, y: -1)
        context.draw(cg, in: CGRect(x: 0, y: 0, width: cg.width, height: cg.height))
        guard let geometry = context.makeImage() else { throw .logged(.insufficientMemory, "makeImage") }

        guard applyColor else { return geometry }
        let spec = edit.colorSpec
        if spec.isIdentity { return geometry }
        guard let pipeline = ColorPipeline.shared else {
            return try CpuColorPipeline.apply(geometry, spec: spec, includeCanvasEffects: includeCanvasEffects)
        }
        do {
            return try pipeline.apply(geometry, spec: spec, includeCanvasEffects: includeCanvasEffects)
        } catch let error as EditorError {
            throw error
        } catch {
            throw .logged(.unknown, "color: \(error)")
        }
    }
}

extension CGColor {
    static func argb(_ value: UInt32) -> CGColor {
        CGColor(
            srgbRed: CGFloat((value >> 16) & 0xFF) / 255,
            green: CGFloat((value >> 8) & 0xFF) / 255,
            blue: CGFloat(value & 0xFF) / 255,
            alpha: CGFloat((value >> 24) & 0xFF) / 255
        )
    }
}
