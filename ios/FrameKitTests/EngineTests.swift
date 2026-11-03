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

struct CanvasLayerTests {
    /// 왼쪽 절반 빨강, 오른쪽 절반 파랑인 200×100 이미지.
    private func halves() -> CGImage {
        let context = BitmapContext.make(width: 200, height: 100)!
        context.setFillColor(CGColor(srgbRed: 0, green: 0, blue: 1, alpha: 1))
        context.fill(CGRect(x: 0, y: 0, width: 200, height: 100))
        context.setFillColor(CGColor(srgbRed: 1, green: 0, blue: 0, alpha: 1))
        context.fill(CGRect(x: 0, y: 0, width: 100, height: 100))
        return context.makeImage()!
    }

    @Test func mosaicAveragesBlocksInsideMaskOnly() throws {
        var edit = PhotoEdit(grainSeed: 1)
        // 블록 20px(짧은 변 100 × 0.2), 가운데 걸친 사각형.
        edit.privacyMasks = [PrivacyMask(id: "m", shape: .rectangle(NRect(left: 0.4, top: 0.2, right: 0.6, bottom: 0.8)),
                                         effect: .mosaic(blockRatio: 0.2))]
        let result = try CanvasLayersRenderer.apply(halves(), edit: edit)
        // 90..110 블록은 경계를 지나지 않으므로 80..100 블록(빨강)과 100..120 블록(파랑)으로 나뉜다.
        let inside = pixel(result, x: 95, y: 50)
        #expect(inside.r > 240 && inside.b < 15)
        let outside = pixel(result, x: 10, y: 5)
        #expect(outside.r > 240)
        let otherSide = pixel(result, x: 190, y: 95)
        #expect(otherSide.b > 240)
    }

    @Test func blurMixesAcrossEdge() throws {
        var edit = PhotoEdit(grainSeed: 1)
        edit.privacyMasks = [PrivacyMask(id: "b", shape: .rectangle(NRect(left: 0.3, top: 0, right: 0.7, bottom: 1)),
                                         effect: .blur(radiusRatio: 0.1))]
        let result = try CanvasLayersRenderer.apply(halves(), edit: edit)
        let edge = pixel(result, x: 100, y: 50)
        #expect(edge.r > 60 && edge.b > 60)
        #expect(pixel(result, x: 20, y: 50).b < 10)
    }

    @Test func drawsOverlaysDrawingAndWatermark() throws {
        var edit = PhotoEdit(grainSeed: 1)
        edit.drawing = [DrawingStroke(id: "s", points: [NPoint(x: 0.05, y: 0.1), NPoint(x: 0.3, y: 0.1)], widthRatio: 0.1,
                                      color: 0xFF00_FF00, opacity: 1, brush: .pen)]
        edit.overlays = [
            Overlay(id: "shape", content: .shape(.rectangle, widthRatio: 0.2, heightRatio: 0.4, ShapeStyle(color: 0xFFFF_FF00, filled: true)),
                    transform: OverlayTransform(center: NPoint(x: 0.75, y: 0.5))),
            Overlay(id: "text", content: .text("Hi", TextStyle(fontSizeRatio: 0.3)), transform: OverlayTransform(center: NPoint(x: 0.25, y: 0.6))),
            Overlay(id: "emoji", content: .sticker(assetId: "emoji:😀", widthRatio: 0.1), transform: OverlayTransform(center: NPoint(x: 0.9, y: 0.15))),
        ]
        edit.watermark = WatermarkSpec(text: "FrameKit", pattern: .band, position: .bottomCenter)
        let result = try CanvasLayersRenderer.apply(halves(), edit: edit)
        let stroke = pixel(result, x: 30, y: 10)
        #expect(stroke.g > 200 && stroke.r < 60)
        let shape = pixel(result, x: 150, y: 50)
        #expect(shape.r > 200 && shape.g > 200)
        let canvas = CGSize(width: 200, height: 100)
        #expect(OverlayRenderer.hitTest(edit.overlays, point: CGPoint(x: 150, y: 50), canvas: canvas) == "shape")
        #expect(OverlayRenderer.hitTest(edit.overlays, point: CGPoint(x: 5, y: 95), canvas: canvas) == nil)
        let text = OverlayRenderer.layout(edit.overlays[1], canvas: canvas)
        #expect(text.width > 10 && text.height >= 25)
    }

    @Test func eraserOnlyClearsDrawingLayer() throws {
        var edit = PhotoEdit(grainSeed: 1)
        edit.drawing = [
            DrawingStroke(id: "pen", points: [NPoint(x: 0.1, y: 0.5), NPoint(x: 0.9, y: 0.5)], widthRatio: 0.2, color: 0xFF00_FF00, opacity: 1, brush: .pen),
            DrawingStroke(id: "erase", points: [NPoint(x: 0.25, y: 0.2), NPoint(x: 0.25, y: 0.8)], widthRatio: 0.2, color: 0, opacity: 1, brush: .eraser),
        ]
        let result = try CanvasLayersRenderer.apply(halves(), edit: edit)
        // 지운 자리에는 사진(빨강)이 보인다.
        let erased = pixel(result, x: 50, y: 50)
        #expect(erased.r > 240 && erased.g < 20)
        #expect(pixel(result, x: 150, y: 50).g > 200)
    }
}

import PDFKit
import UIKit

struct PdfExportTests {
    /// 글자가 큰 흰 바탕 이미지 파일.
    private func textSource(_ text: String) throws -> URL {
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        let image = UIGraphicsImageRenderer(size: CGSize(width: 1200, height: 800), format: format).image { _ in
            UIColor.white.setFill()
            UIRectFill(CGRect(x: 0, y: 0, width: 1200, height: 800))
            (text as NSString).draw(at: CGPoint(x: 80, y: 300), withAttributes: [.font: UIFont.boldSystemFont(ofSize: 120), .foregroundColor: UIColor.black])
        }
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + ".jpg")
        try image.jpegData(compressionQuality: 0.95)!.write(to: url)
        return url
    }

    @Test func combinesPagesWithTextLayer() throws {
        let a = try SourceImage(url: try textSource("Hello PDF"), removeOnClose: true)
        let b = try SourceImage(url: try textSource("Second page"), removeOnClose: true)
        defer { a.close(); b.close() }
        let config = ImageExportConfig(format: .pdf)
        let outputs = try PdfExporter.export(pages: [(a, PhotoEdit(grainSeed: 1)), (b, PhotoEdit(grainSeed: 2))], config: config) { _, _ in }
        defer { outputs.forEach { FrameKitOutputs.delete($0.url) } }
        #expect(outputs.count == 1)
        let media = outputs[0]
        #expect(media.mediaType == .document && media.pageCount == 2 && media.mimeType == "application/pdf")
        // A4 세로 쪽(가로 사진이라 가로 방향): 842×595pt.
        #expect(media.width == 842 && media.height == 595)
        let document = try #require(PDFDocument(url: media.url))
        #expect(document.pageCount == 2)
        if !media.warnings.contains(.textRecognitionSkipped) {
            #expect(media.recognizedText?.contains("Hello") == true)
            #expect(!document.findString("Second", withOptions: .caseInsensitive).isEmpty)
        }
    }

    @Test func separatePdfsAndImages() throws {
        let a = try SourceImage(url: try textSource("A"), removeOnClose: true)
        let b = try SourceImage(url: try textSource("B"), removeOnClose: true)
        defer { a.close(); b.close() }
        let pages = [(a, PhotoEdit(grainSeed: 1)), (b, PhotoEdit(grainSeed: 2))]
        let pdfs = try PdfExporter.export(pages: pages, config: ImageExportConfig(format: .pdf, pdf: PdfOptions(combinePages: false, recognizeText: false))) { _, _ in }
        defer { pdfs.forEach { FrameKitOutputs.delete($0.url) } }
        #expect(pdfs.count == 2 && pdfs.allSatisfy { $0.pageCount == 1 && $0.recognizedText == nil })
        let images = try ImageExporter.exportAll(pages: pages, config: ImageExportConfig(format: .png)) { _, _ in }
        defer { images.forEach { FrameKitOutputs.delete($0.url) } }
        #expect(images.count == 2 && Set(images.map(\.url)).count == 2)
    }
}

struct DocumentTests {
    /// 어두운 책상 위 기울어진 흰 종이(글자 줄 포함).
    static func paperPhoto() throws -> URL {
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        let image = UIGraphicsImageRenderer(size: CGSize(width: 1600, height: 1200), format: format).image { context in
            UIColor(red: 0.35, green: 0.22, blue: 0.12, alpha: 1).setFill()
            UIRectFill(CGRect(x: 0, y: 0, width: 1600, height: 1200))
            let paper = UIBezierPath()
            paper.move(to: CGPoint(x: 420, y: 160)); paper.addLine(to: CGPoint(x: 1240, y: 220))
            paper.addLine(to: CGPoint(x: 1180, y: 1080)); paper.addLine(to: CGPoint(x: 360, y: 1010)); paper.close()
            UIColor(white: 0.93, alpha: 1).setFill()
            paper.fill()
            context.cgContext.saveGState()
            paper.addClip()
            UIColor(white: 0.15, alpha: 1).setFill()
            for i in 0..<10 { UIRectFill(CGRect(x: 480, y: 300 + i * 70, width: 600, height: 18)) }
            context.cgContext.restoreGState()
        }
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + ".jpg")
        try image.jpegData(compressionQuality: 0.95)!.write(to: url)
        return url
    }

    @Test func detectsAndRectifiesPaper() throws {
        let source = try SourceImage(url: try Self.paperPhoto(), removeOnClose: true)
        defer { source.close() }
        let preview = try source.decode(maxLongEdge: 1600)
        let detection = try #require(DocumentEngine.detect(preview.cgImage))
        let tl = detection.quad.corners[0]
        #expect(abs(tl.x - 420.0 / 1600) < 0.04 && abs(tl.y - 160.0 / 1200) < 0.04)
        let url = try DocumentEngine.rectify(source: source, quad: detection.quad, detection: detection, style: .blackWhite)
        let result = try SourceImage(url: url, removeOnClose: true)
        defer { result.close() }
        // 종이 비율(약 820×860)에 가깝고 세로가 조금 더 길다.
        let ratio = Double(result.uprightSize.width) / Double(result.uprightSize.height)
        #expect(ratio > 0.85 && ratio < 1.05)
        let image = try result.decode(maxLongEdge: 2000).cgImage
        // 흑백 스캔: 종이는 흰색, 글자 줄은 검정.
        let paper = pixel(image, x: image.width / 2, y: image.height - 20)
        #expect(paper.r > 240 && paper.g > 240)
    }

    @Test func rejectsBrokenQuad() throws {
        let source = try SourceImage(url: try Self.paperPhoto(), removeOnClose: true)
        defer { source.close() }
        let bow = DocQuad(corners: [NPoint(x: 0.1, y: 0.1), NPoint(x: 0.9, y: 0.9), NPoint(x: 0.9, y: 0.1), NPoint(x: 0.1, y: 0.9)])
        #expect(throws: EditorError.self) { try DocumentEngine.rectify(source: source, quad: bow, detection: nil, style: .color) }
    }
}

struct CatalogTests {
    @Test func validatesIdsAndRanges() {
        var catalog = EditorCatalog(filters: [CustomFilter(id: "bright", label: "x"), CustomFilter(id: "my.warm", label: " ", temperature: 2)],
                                    stickers: [CustomSticker(id: "A", label: "a", file: URL(fileURLWithPath: "/x.png"))])
        let paths = Set(catalog.issues().map(\.path))
        #expect(paths.contains("catalog.filters[0].id"))      // 기본 id 재사용
        #expect(paths.contains("catalog.filters[1].label"))   // 빈 이름
        #expect(paths.contains("catalog.filters[1]"))         // 범위 밖 값
        #expect(paths.contains("catalog.stickers[0].id"))     // 대문자
        catalog = EditorCatalog(filters: [CustomFilter(id: "my.warm", label: "Warm+", temperature: 0.6)])
        #expect(catalog.issues().isEmpty)
    }

    @Test func customFilterChangesColor() throws {
        try CatalogInstaller.install(EditorCatalog(filters: [CustomFilter(id: "test.red", label: "Red", temperature: 1, saturation: 0.5)]))
        defer { try? CatalogInstaller.install(EditorCatalog()) }
        let context = BitmapContext.make(width: 32, height: 32)!
        context.setFillColor(CGColor(srgbRed: 0.5, green: 0.5, blue: 0.5, alpha: 1))
        context.fill(CGRect(x: 0, y: 0, width: 32, height: 32))
        var edit = PhotoEdit(grainSeed: 1)
        edit.filterId = "test.red"
        edit.filterIntensity = 1
        let result = try CpuColorPipeline.apply(context.makeImage()!, spec: edit.colorSpec, includeCanvasEffects: true)
        let p = pixel(result, x: 16, y: 16)
        #expect(p.r > p.b + 10)
        #expect(FilterLabels.label(for: "test.red") == "Red")
    }
}
