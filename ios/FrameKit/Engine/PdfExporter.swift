import CoreGraphics
import Foundation
import ImageIO
import UniformTypeIdentifiers
import Vision
internal import FrameKitCore

/// 편집한 사진들을 PDF로 저장한다. 쪽 배치는 core `PdfLayout`, 파일 쓰기는 core `PdfWriter`를 쓴다.
enum PdfExporter {
    static let maxRecognizedText = 100_000

    static func export(pages: [(SourceImage, PhotoEdit)], config: ImageExportConfig,
                       progress: @Sendable (ExportStage, Double) -> Void) throws -> [EditedMedia] {
        guard !pages.isEmpty else { throw EditorError.logged(.invalidConfiguration, "no pages") }
        let groups: [[(SourceImage, PhotoEdit)]] = config.pdf.combinePages ? [pages] : pages.map { [$0] }
        var outputs: [EditedMedia] = []
        var done = 0
        do {
            for group in groups {
                outputs.append(try writePdf(group, config: config) { stage, fraction in
                    progress(stage, (Double(done) + fraction) / Double(pages.count))
                })
                done += group.count
            }
            return outputs
        } catch {
            outputs.forEach { FrameKitOutputs.delete($0.url) }
            throw error
        }
    }

    private static func writePdf(_ pages: [(SourceImage, PhotoEdit)], config: ImageExportConfig,
                                 progress: (ExportStage, Double) -> Void) throws -> EditedMedia {
        progress(.preparing, 0)
        let (slot, partial) = try OutputStore.reserve(extension: "pdf")
        var firstPage: CGSize?
        var texts: [String] = []
        var warnings = Set<ExportWarning>()
        let writer: PdfWriter
        let final: URL
        do {
            writer = PdfWriter(output: try PdfFileOutput(path: partial.path), producer: "FrameKit")
        } catch {
            throw EditorError.logged(.outputWriteFailed, "pdf open: \(error)")
        }
        do {
            for (index, page) in pages.enumerated() {
                let (source, edit) = page
                guard edit.isValid(uprightSize: source.uprightSize) else { throw EditorError.logged(.invalidProject, "page \(index)") }
                let math = GeometryMath(source: source.uprightSize, geometry: edit.geometry)
                let crop = math.cropPixelSize(edit.geometry.crop)
                let imageSize = PixelSize(width: Int32(max(1, crop.width.rounded())), height: Int32(max(1, crop.height.rounded())))
                let layout: PdfPageLayout
                do {
                    layout = try PdfLayout.shared.layout(imageSize: imageSize, pageSize: config.pdf.pageSize.kotlin,
                                                         orientation: config.pdf.orientation.kotlin, marginMm: config.pdf.marginMm,
                                                         dpi: Int32(config.pdf.dpi), maxPixels: Int64(config.maxOutputPixels))
                } catch {
                    throw EditorError.logged(.invalidConfiguration, "pdf layout: \(error)")
                }
                let output = PixelSizeValue(width: Int(layout.pixels.width), height: Int(layout.pixels.height))
                try OutputStore.checkFreeSpace(bytes: Int64(output.width * output.height) + 5_000_000)
                progress(.rendering, (Double(index) + 0.3) / Double(pages.count))
                let rendered = try ImageExporter.render(source: source, edit: edit, output: output, background: config.pdf.backgroundARGB)
                try Task.checkCancellation()
                progress(.encoding, (Double(index) + 0.6) / Double(pages.count))
                guard let jpeg = jpegData(rendered, quality: Double(config.quality) / 100) else {
                    throw EditorError.logged(.encodeFailed, "pdf jpeg")
                }
                var lines: [PdfTextLine] = []
                if config.pdf.recognizeText {
                    if let recognized = TextRecognizer.recognize(rendered) {
                        lines = recognized
                        texts.append(recognized.map(\.text).joined(separator: "\n"))
                    } else {
                        warnings.insert(.textRecognitionSkipped)
                    }
                }
                if !source.isSRGB { warnings.insert(.colorSpaceConvertedToSRGB) }
                if source.hasGainMap { warnings.insert(.hdrGainMapDropped) }
                do {
                    try writer.addPage(jpeg: PdfBytesKt.pdfBytes(data: jpeg), pixelWidth: Int32(rendered.width), pixelHeight: Int32(rendered.height),
                                       layout: layout, grayscale: false, text: lines)
                } catch {
                    throw EditorError.logged(.outputWriteFailed, "pdf page: \(error)")
                }
                if firstPage == nil { firstPage = CGSize(width: layout.pageWidthPt, height: layout.pageHeightPt) }
                try Task.checkCancellation()
            }
            progress(.finalizing, 1)
            do {
                try writer.finish()
            } catch {
                throw EditorError.logged(.outputWriteFailed, "pdf finish: \(error)")
            }
            final = try OutputStore.publish(partial: partial, to: slot)
        } catch {
            try? writer.finish()
            try? FileManager.default.removeItem(at: partial)
            throw error
        }
        let size = (try? final.resourceValues(forKeys: [.fileSizeKey]).fileSize).map { Int64($0) } ?? 0
        let text = texts.joined(separator: "\n\n")
        return EditedMedia(url: final, mediaType: .document, width: Int((firstPage?.width ?? 0).rounded()),
                           height: Int((firstPage?.height ?? 0).rounded()), mimeType: "application/pdf", fileSize: size,
                           warnings: Array(warnings).sorted { $0.rawValue < $1.rawValue }, pageCount: pages.count,
                           recognizedText: config.pdf.recognizeText ? String(text.prefix(maxRecognizedText)) : nil, isOriginal: false)
    }

    private static func jpegData(_ image: CGImage, quality: Double) -> Data? {
        let data = NSMutableData()
        guard let destination = CGImageDestinationCreateWithData(data, UTType.jpeg.identifier as CFString, 1, nil) else { return nil }
        CGImageDestinationAddImage(destination, image, [kCGImageDestinationLossyCompressionQuality: quality] as CFDictionary)
        return CGImageDestinationFinalize(destination) ? data as Data : nil
    }
}

/// 기기 안 글자 인식(Vision). 네트워크를 쓰지 않는다.
enum TextRecognizer {
    private static let preferred = ["ko-KR", "en-US", "ja-JP", "zh-Hans", "zh-Hant"]

    /// 인식한 줄(픽셀, 왼쪽 위 원점). 인식기를 쓸 수 없으면 `nil`.
    static func recognize(_ image: CGImage) -> [PdfTextLine]? {
        let request = VNRecognizeTextRequest()
        request.recognitionLevel = .accurate
        request.usesLanguageCorrection = true
        if let supported = try? request.supportedRecognitionLanguages() {
            let languages = preferred.filter(supported.contains)
            if !languages.isEmpty { request.recognitionLanguages = languages }
        }
        do {
            try VNImageRequestHandler(cgImage: image, options: [:]).perform([request])
        } catch {
            frameKitLog.error("text recognition failed: \(error.localizedDescription, privacy: .public)")
            return nil
        }
        let w = Double(image.width), h = Double(image.height)
        return (request.results ?? []).compactMap { observation in
            guard let text = observation.topCandidates(1).first?.string, !text.isEmpty else { return nil }
            let box = observation.boundingBox
            return PdfTextLine(text: text, left: Float(box.minX * w), top: Float((1 - box.maxY) * h),
                               width: Float(box.width * w), height: Float(box.height * h))
        }
    }
}

extension PdfPageSizeOption {
    var kotlin: PdfPageSize {
        switch self {
        case .fitImage: .fitImage
        case .a4: .a4
        case .a5: .a5
        case .letter: .letter
        case .legal: .legal
        }
    }
}

extension PdfOrientationOption {
    var kotlin: PdfOrientation {
        switch self {
        case .auto: .auto_
        case .portrait: .portrait
        case .landscape: .landscape
        }
    }
}
