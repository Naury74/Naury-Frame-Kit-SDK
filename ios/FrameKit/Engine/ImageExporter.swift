import CoreGraphics
import Foundation
import ImageIO
import UniformTypeIdentifiers

/// 저장 단계.
enum ExportStage: Sendable {
    case preparing, rendering, encoding, finalizing
}

/// 편집 결과를 파일로 저장한다.
enum ImageExporter {
    /// - Parameter progress: 단계가 바뀔 때마다 부른다(작업 스레드).
    /// - Throws: `EditorError`, 작업이 취소되면 `CancellationError`.
    static func export(
        source: SourceImage,
        edit: PhotoEdit,
        config: ImageExportConfig,
        progress: @Sendable (ExportStage) -> Void
    ) throws -> EditedMedia {
        progress(.preparing)
        guard edit.isValid(uprightSize: source.uprightSize) else { throw EditorError.logged(.invalidProject, "edit out of range") }
        let math = GeometryMath(source: source.uprightSize, geometry: edit.geometry)
        let output = try math.outputSize(crop: edit.geometry.crop, maxPixels: config.maxOutputPixels,
                                     maxWidth: config.maxWidth, maxHeight: config.maxHeight)
        let bytesPerPixel: Int64 = config.format == .png ? 4 : 1
        try OutputStore.checkFreeSpace(bytes: Int64(output.width) * Int64(output.height) * bytesPerPixel + 5_000_000)
        guard let destinationType = destinationType(for: config.format) else {
            throw EditorError.logged(.unsupportedFormat, "encoder \(config.format.rawValue) unavailable")
        }
        try Task.checkCancellation()

        progress(.rendering)
        let background: UInt32? = config.format.supportsAlpha ? nil : config.jpegBackgroundARGB
        let rendered = try render(source: source, edit: edit, output: output, background: background)
        try Task.checkCancellation()

        progress(.encoding)
        let (slot, partial) = try OutputStore.reserve(extension: config.format.fileExtension)
        let final: URL
        do {
            guard let destination = CGImageDestinationCreateWithURL(partial as CFURL, destinationType.identifier as CFString, 1, nil) else {
                throw EditorError.logged(.encodeFailed, "destination")
            }
            var properties = metadata(source: source, policy: config.format == .png ? .none : config.metadataPolicy, output: output)
            if config.format != .png {
                properties[kCGImageDestinationLossyCompressionQuality] = Double(config.quality) / 100
            }
            CGImageDestinationAddImage(destination, rendered, properties as CFDictionary)
            guard CGImageDestinationFinalize(destination) else { throw EditorError.logged(.encodeFailed, "finalize") }
            try Task.checkCancellation()

            progress(.finalizing)
            try verify(partial, output: output, type: destinationType)
            final = try OutputStore.publish(partial: partial, to: slot)
        } catch {
            try? FileManager.default.removeItem(at: partial)
            throw error
        }
        let size = (try? final.resourceValues(forKeys: [.fileSizeKey]).fileSize).map { Int64($0) } ?? 0
        var warnings: [ExportWarning] = []
        if !source.isSRGB { warnings.append(.colorSpaceConvertedToSRGB) }
        if source.hasGainMap { warnings.append(.hdrGainMapDropped) }
        return EditedMedia(url: final, mediaType: .image, width: output.width, height: output.height, mimeType: config.format.mimeType,
                           fileSize: size, warnings: warnings, pageCount: nil, recognizedText: nil, isOriginal: false)
    }

    /// 결과 크기에 필요한 만큼만 디코딩해 그린다.
    static func render(source: SourceImage, edit: PhotoEdit, output: PixelSizeValue, background: UInt32?) throws -> CGImage {
        let math = GeometryMath(source: source.uprightSize, geometry: edit.geometry)
        let crop = math.cropPixelSize(edit.geometry.crop)
        let scale = min(1, Double(output.width) / crop.width, Double(output.height) / crop.height)
        let longEdge = Int((Double(max(source.uprightSize.width, source.uprightSize.height)) * scale).rounded(.up)) + 2
        let decoded = try source.decode(maxLongEdge: longEdge)
        try Task.checkCancellation()
        return try PhotoRenderer.render(image: decoded, uprightSize: source.uprightSize, edit: edit, crop: edit.geometry.crop,
                                        output: output, background: background, includeCanvasEffects: true)
    }

    /// 여러 장을 장마다 파일로 저장한다. 하나라도 실패하면 만든 파일을 모두 지운다.
    static func exportAll(pages: [(SourceImage, PhotoEdit)], config: ImageExportConfig,
                          progress: @Sendable (ExportStage, Double) -> Void) throws -> [EditedMedia] {
        var outputs: [EditedMedia] = []
        do {
            for (index, page) in pages.enumerated() {
                let media = try export(source: page.0, edit: page.1, config: config) { stage in
                    progress(stage, (Double(index) + (stage == .finalizing ? 1 : 0.5)) / Double(pages.count))
                }
                outputs.append(media)
            }
            return outputs
        } catch {
            outputs.forEach { FrameKitOutputs.delete($0.url) }
            throw error
        }
    }

    private static func destinationType(for format: ImageFormat) -> UTType? {
        let type: UTType = switch format {
        case .jpeg: .jpeg
        case .png: .png
        case .heic: .heic
        case .pdf: .pdf
        }
        guard format != .pdf else { return nil }
        let available = CGImageDestinationCopyTypeIdentifiers() as? [String] ?? []
        return available.contains(type.identifier) ? type : nil
    }

    private static func verify(_ url: URL, output: PixelSizeValue, type: UTType) throws(EditorError) {
        guard let check = CGImageSourceCreateWithURL(url as CFURL, nil),
              CGImageSourceGetType(check) as String? == type.identifier,
              let props = CGImageSourceCopyPropertiesAtIndex(check, 0, nil) as? [CFString: Any],
              props[kCGImagePropertyPixelWidth] as? Int == output.width,
              props[kCGImagePropertyPixelHeight] as? Int == output.height else {
            throw .logged(.encodeFailed, "verify failed")
        }
    }

    // 원본에서 옮길 항목. 방향은 항상 1, 크기는 결과 크기로 쓴다.
    nonisolated(unsafe) private static let safeExif: [CFString] = [
        kCGImagePropertyExifDateTimeOriginal, kCGImagePropertyExifDateTimeDigitized, kCGImagePropertyExifOffsetTimeOriginal,
        kCGImagePropertyExifOffsetTimeDigitized, kCGImagePropertyExifSubsecTimeOriginal, kCGImagePropertyExifExposureTime,
        kCGImagePropertyExifFNumber, kCGImagePropertyExifISOSpeedRatings, kCGImagePropertyExifFocalLength,
        kCGImagePropertyExifFocalLenIn35mmFilm, kCGImagePropertyExifFlash, kCGImagePropertyExifWhiteBalance,
        kCGImagePropertyExifExposureBiasValue,
    ]
    nonisolated(unsafe) private static let safeTiff: [CFString] = [kCGImagePropertyTIFFMake, kCGImagePropertyTIFFModel]
    nonisolated(unsafe) private static let allExif: [CFString] = [
        kCGImagePropertyExifOffsetTime, kCGImagePropertyExifUserComment, kCGImagePropertyExifCameraOwnerName,
        kCGImagePropertyExifBodySerialNumber, kCGImagePropertyExifLensMake, kCGImagePropertyExifLensModel,
        kCGImagePropertyExifLensSerialNumber, kCGImagePropertyExifLensSpecification, kCGImagePropertyExifMeteringMode,
        kCGImagePropertyExifExposureProgram, kCGImagePropertyExifSceneCaptureType,
    ]
    nonisolated(unsafe) private static let allTiff: [CFString] = [
        kCGImagePropertyTIFFDateTime, kCGImagePropertyTIFFArtist, kCGImagePropertyTIFFCopyright,
        kCGImagePropertyTIFFImageDescription, kCGImagePropertyTIFFSoftware,
    ]

    private static func metadata(source: SourceImage, policy: MetadataPolicy, output: PixelSizeValue) -> [CFString: Any] {
        var exif: [CFString: Any] = [:]
        var tiff: [CFString: Any] = [:]
        var result: [CFString: Any] = [kCGImagePropertyOrientation: 1]
        if policy != .none {
            let sourceExif = source.properties[kCGImagePropertyExifDictionary] as? [CFString: Any] ?? [:]
            let sourceTiff = source.properties[kCGImagePropertyTIFFDictionary] as? [CFString: Any] ?? [:]
            let exifKeys = policy == .all ? safeExif + allExif : safeExif
            let tiffKeys = policy == .all ? safeTiff + allTiff : safeTiff
            for key in exifKeys { if let value = sourceExif[key] { exif[key] = value } }
            for key in tiffKeys { if let value = sourceTiff[key] { tiff[key] = value } }
            if policy == .all, let gps = source.properties[kCGImagePropertyGPSDictionary] { result[kCGImagePropertyGPSDictionary] = gps }
        }
        exif[kCGImagePropertyExifPixelXDimension] = output.width
        exif[kCGImagePropertyExifPixelYDimension] = output.height
        tiff[kCGImagePropertyTIFFOrientation] = 1
        result[kCGImagePropertyExifDictionary] = exif
        result[kCGImagePropertyTIFFDictionary] = tiff
        return result
    }
}
