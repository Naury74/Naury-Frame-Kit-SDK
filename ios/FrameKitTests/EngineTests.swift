import CoreGraphics
import Foundation
import ImageIO
import Testing
import UniformTypeIdentifiers
@testable import FrameKit

/// 시험용 이미지 파일. 왼쪽 위 사분면은 빨강, 나머지는 파랑.
private func makeSource(width: Int, height: Int, gps: Bool = false) throws -> URL {
    let context = BitmapContext.make(width: width, height: height)!
    context.setFillColor(CGColor(srgbRed: 0, green: 0, blue: 1, alpha: 1))
    context.fill(CGRect(x: 0, y: 0, width: width, height: height))
    context.setFillColor(CGColor(srgbRed: 1, green: 0, blue: 0, alpha: 1))
    // CG 좌표는 아래가 원점이므로 위쪽 왼쪽 사분면은 y = height/2...height.
    context.fill(CGRect(x: 0, y: height / 2, width: width / 2, height: height / 2))
    let url = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + ".jpg")
    let destination = CGImageDestinationCreateWithURL(url as CFURL, UTType.jpeg.identifier as CFString, 1, nil)!
    var props: [CFString: Any] = [
        kCGImagePropertyExifDictionary: [kCGImagePropertyExifDateTimeOriginal: "2026:01:02 03:04:05"],
        kCGImagePropertyTIFFDictionary: [kCGImagePropertyTIFFMake: "FrameKitCam"],
        kCGImageDestinationLossyCompressionQuality: 1.0,
    ]
    if gps { props[kCGImagePropertyGPSDictionary] = [kCGImagePropertyGPSLatitude: 37.5, kCGImagePropertyGPSLatitudeRef: "N"] }
    CGImageDestinationAddImage(destination, context.makeImage()!, props as CFDictionary)
    #expect(CGImageDestinationFinalize(destination))
    return url
}

private func pixel(_ image: CGImage, x: Int, y: Int) -> (r: Int, g: Int, b: Int) {
    let context = BitmapContext.make(width: image.width, height: image.height)!
    context.draw(image, in: CGRect(x: 0, y: 0, width: image.width, height: image.height))
    let bytes = context.data!.bindMemory(to: UInt8.self, capacity: context.bytesPerRow * image.height)
    let o = y * context.bytesPerRow + x * 4
    return (Int(bytes[o]), Int(bytes[o + 1]), Int(bytes[o + 2]))
}

struct RequestValidationTests {
    @Test func defaultsAreValid() {
        #expect(ImageEditorRequest(input: .pick).validate().isEmpty)
    }

    @Test func rejectsOutOfRangeValues() {
        let request = ImageEditorRequest(
            input: .data(Data()),
            config: ImageEditorConfig(allowUndo: false, allowRedo: true),
            export: ImageExportConfig(quality: 101, maxWidth: 0, maxOutputPixels: 0),
            ui: EditorUIConfig(cornerRadius: 40)
        )
        let paths = Set(request.validate().map(\.path))
        #expect(paths == ["input", "config.allowRedo", "export.quality", "export.maxWidth", "export.maxOutputPixels", "ui.cornerRadius"])
    }
}

struct HistoryTests {
    @Test func undoToStartIsClean() {
        var history = EditHistory(PhotoEdit(grainSeed: 1))
        var edit = history.current
        edit.adjustments[.brightness] = 0.5
        history.commit(edit)
        #expect(history.isDirty)
        history.undo()
        #expect(!history.isDirty)
        #expect(history.canRedo)
    }

    @Test func zeroAdjustmentIsNotAChange() {
        var history = EditHistory(PhotoEdit(grainSeed: 1))
        var edit = history.current
        edit.adjustments[.contrast] = 0
        history.commit(edit)
        #expect(!history.canUndo)
    }

    @Test func capacityIsBounded() {
        var history = EditHistory(PhotoEdit(grainSeed: 1))
        for i in 1...60 {
            var edit = history.current
            edit.adjustments[.brightness] = Double(i) / 100
            history.commit(edit)
        }
        var undos = 0
        while history.canUndo { history.undo(); undos += 1 }
        #expect(undos == EditHistory.capacity)
    }
}

struct RenderTests {
    @Test func rotateClockwiseMovesTopLeftToTopRight() throws {
        let url = try makeSource(width: 400, height: 200)
        let source = try SourceImage(url: url, removeOnClose: true)
        defer { source.close() }
        var edit = PhotoEdit(grainSeed: 1)
        edit.geometry.quarterTurns = 1
        let image = try source.decode(maxLongEdge: 400)
        let output = PixelSizeValue(width: 200, height: 400)
        let result = try PhotoRenderer.render(image: image, uprightSize: source.uprightSize, edit: edit, crop: .full,
                                              output: output, background: 0xFF00_0000, includeCanvasEffects: true)
        #expect(result.width == 200 && result.height == 400)
        // 시계 방향 90°: 원본 왼쪽 위(빨강)는 오른쪽 위로 간다.
        #expect(pixel(result, x: 180, y: 20).r > 200)
        #expect(pixel(result, x: 20, y: 20).b > 200)
    }

    @Test func gpuMatchesCpu() throws {
        let gpu = try #require(ColorPipeline.shared)
        let url = try makeSource(width: 160, height: 120)
        let source = try SourceImage(url: url, removeOnClose: true)
        defer { source.close() }
        let image = try source.decode(maxLongEdge: 160).cgImage
        var edit = PhotoEdit(grainSeed: 42)
        edit.adjustments = [.exposure: 0.4, .contrast: 0.3, .shadows: 0.2, .saturation: -0.3, .temperature: 0.4,
                            .sharpness: 0.6, .vignette: 0.5, .grain: 0.4, .fade: 0.2]
        edit.filterId = "film02"
        edit.filterIntensity = 0.7
        let a = try gpu.apply(image, spec: edit.colorSpec, includeCanvasEffects: true)
        let b = try CpuColorPipeline.apply(image, spec: edit.colorSpec, includeCanvasEffects: true)
        // 보정이 실제로 적용됐는지 본다.
        let before = pixel(image, x: 100, y: 90)
        let after = pixel(a, x: 100, y: 90)
        #expect(before.r != after.r || before.g != after.g || before.b != after.b)
        var total = 0
        var worst = 0
        for y in stride(from: 0, to: 120, by: 7) {
            for x in stride(from: 0, to: 160, by: 7) {
                let p = pixel(a, x: x, y: y)
                let q = pixel(b, x: x, y: y)
                let d = [abs(p.r - q.r), abs(p.g - q.g), abs(p.b - q.b)]
                total += d.reduce(0, +)
                worst = max(worst, d.max()!)
            }
        }
        print("gpu-cpu worst=\(worst) mean=\(Double(total) / Double(23 * 18 * 3)) before=\(before) after=\(after)")
        #expect(worst <= 8)
        #expect(Double(total) / Double(23 * 18 * 3) <= 1.0)
    }
}

struct ExportTests {
    @Test func exportsJpegWithSafeMetadata() throws {
        let url = try makeSource(width: 300, height: 200, gps: true)
        let source = try SourceImage(url: url, removeOnClose: true)
        defer { source.close() }
        var edit = PhotoEdit(grainSeed: 1)
        edit.geometry.quarterTurns = 1
        let media = try ImageExporter.export(source: source, edit: edit, config: ImageExportConfig()) { _ in }
        defer { FrameKitOutputs.delete(media.url) }
        #expect(media.width == 200 && media.height == 300)
        #expect(media.mimeType == "image/jpeg")
        let props = CGImageSourceCopyPropertiesAtIndex(CGImageSourceCreateWithURL(media.url as CFURL, nil)!, 0, nil) as! [CFString: Any]
        let exif = props[kCGImagePropertyExifDictionary] as? [CFString: Any] ?? [:]
        let tiff = props[kCGImagePropertyTIFFDictionary] as? [CFString: Any] ?? [:]
        #expect(exif[kCGImagePropertyExifDateTimeOriginal] as? String == "2026:01:02 03:04:05")
        #expect(tiff[kCGImagePropertyTIFFMake] as? String == "FrameKitCam")
        #expect(props[kCGImagePropertyGPSDictionary] == nil)
        #expect((props[kCGImagePropertyOrientation] as? Int ?? 1) == 1)
    }

    @Test func respectsMaxPixels() throws {
        let url = try makeSource(width: 1000, height: 500)
        let source = try SourceImage(url: url, removeOnClose: true)
        defer { source.close() }
        let media = try ImageExporter.export(source: source, edit: PhotoEdit(grainSeed: 1),
                                             config: ImageExportConfig(format: .png, maxOutputPixels: 20_000)) { _ in }
        defer { FrameKitOutputs.delete(media.url) }
        #expect(media.width * media.height <= 20_000)
        #expect(media.mimeType == "image/png")
    }

    @Test func rejectsInvalidEdit() throws {
        let url = try makeSource(width: 100, height: 100)
        let source = try SourceImage(url: url, removeOnClose: true)
        defer { source.close() }
        var edit = PhotoEdit(grainSeed: 1)
        edit.adjustments[.brightness] = 5
        #expect(throws: EditorError.self) { try ImageExporter.export(source: source, edit: edit, config: ImageExportConfig()) { _ in } }
    }

    @Test func refusesToDeleteOutsideOutputFolder() throws {
        let url = try makeSource(width: 10, height: 10)
        #expect(!FrameKitOutputs.delete(url))
        #expect(FileManager.default.fileExists(atPath: url.path))
    }

    @Test func rejectsUnsupportedSource() throws {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + ".txt")
        try Data("not an image".utf8).write(to: url)
        #expect(throws: EditorError.self) { try SourceImage(url: url, removeOnClose: true) }
    }
}

struct StringsTests {
    @Test func forcedLocale() {
        #expect(Strings(localeIdentifier: "ko")("action_save") == "저장")
        #expect(Strings(localeIdentifier: "xx")("action_save") == "Save")
        #expect(Strings(localeIdentifier: "ko")("draft_title", "자르기") == "자르기 편집 내용을 적용할까요?")
    }

    @Test func everyErrorHasMessage() {
        let strings = Strings(localeIdentifier: "en")
        for code in EditorErrorCode.allCases {
            #expect(strings(errorKey(code)) != errorKey(code), "\(code)")
        }
    }
}

struct GradientParityTests {
    /// 모든 밝기·색을 지나는 그라데이션에서 GPU와 CPU 결과를 비교한다(Android 기준: 평균 1/255, 최대 8 이하).
    @Test(arguments: ["original", "bright", "dramatic", "mono", "vintage", "document-bw"])
    func gpuMatchesCpuOnGradient(filter: String) throws {
        let gpu = try #require(ColorPipeline.shared)
        let w = 256, h = 96
        let context = BitmapContext.make(width: w, height: h)!
        let colors = [CGColor(srgbRed: 0, green: 0, blue: 0, alpha: 1), CGColor(srgbRed: 0.9, green: 0.2, blue: 0.1, alpha: 1),
                      CGColor(srgbRed: 0.1, green: 0.8, blue: 0.4, alpha: 1), CGColor(srgbRed: 1, green: 1, blue: 1, alpha: 1)] as CFArray
        let gradient = CGGradient(colorsSpace: BitmapContext.sRGB, colors: colors, locations: [0, 0.35, 0.7, 1])!
        context.drawLinearGradient(gradient, start: .zero, end: CGPoint(x: w, y: h), options: [])
        let image = context.makeImage()!
        var edit = PhotoEdit(grainSeed: 7)
        edit.adjustments = [.exposure: -0.6, .highlights: -0.4, .shadows: 0.5, .contrast: 0.2, .tint: 0.3,
                            .sharpness: 1, .vignette: 0.8, .grain: 1]
        edit.filterId = filter
        edit.filterIntensity = filter == "original" ? 0 : 0.85
        let a = try gpu.apply(image, spec: edit.colorSpec, includeCanvasEffects: true)
        let b = try CpuColorPipeline.apply(image, spec: edit.colorSpec, includeCanvasEffects: true)
        var total = 0, worst = 0, count = 0
        for y in stride(from: 0, to: h, by: 3) {
            for x in stride(from: 0, to: w, by: 3) {
                let p = pixel(a, x: x, y: y), q = pixel(b, x: x, y: y)
                let d = [abs(p.r - q.r), abs(p.g - q.g), abs(p.b - q.b)]
                total += d.reduce(0, +)
                worst = max(worst, d.max()!)
                count += 3
            }
        }
        print("gradient \(filter): worst=\(worst) mean=\(Double(total) / Double(count))")
        #expect(worst <= 8)
        #expect(Double(total) / Double(count) <= 1.0)
    }
}
