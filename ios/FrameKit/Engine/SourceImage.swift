import CoreGraphics
import Foundation
import ImageIO
import UniformTypeIdentifiers
import os

let frameKitLog = Logger(subsystem: "com.naurylab.framekit", category: "FrameKit")

extension EditorError {
    /// 진단 ID와 함께 로그를 남기고 오류를 만든다.
    static func logged(_ code: EditorErrorCode, _ message: String) -> EditorError {
        let error = EditorError(code: code)
        frameKitLog.error("[\(error.diagnosticId, privacy: .public)] \(code.rawValue, privacy: .public): \(message, privacy: .public)")
        return error
    }
}

/// 업라이트(EXIF 방향 적용) sRGB 8비트 이미지. 원본보다 작게 디코딩했을 수 있다.
struct UprightImage: @unchecked Sendable {
    let cgImage: CGImage
}

/// 연 원본 사진. 파일은 열린 동안 유지된다.
final class SourceImage: @unchecked Sendable {
    /// 2억 5천만 픽셀 초과는 열지 않는다.
    static let maxSourcePixels = 250_000_000

    static let supportedTypes: [UTType] = [.jpeg, .png, .heic, .heif, .webP]

    let url: URL
    let type: UTType
    /// EXIF 방향을 적용한 크기.
    let uprightSize: PixelSizeValue
    /// 원본 ImageIO 속성(메타데이터 복사용).
    let properties: [CFString: Any]
    let isSRGB: Bool
    let hasGainMap: Bool
    private let source: CGImageSource
    private let index: Int
    private let removeOnClose: Bool

    /// 파일을 열고 형식·크기를 확인한다.
    /// - Parameter removeOnClose: `true`면 `close()`에서 파일을 지운다(SDK가 만든 임시 파일).
    init(url: URL, removeOnClose: Bool) throws(EditorError) {
        guard let source = CGImageSourceCreateWithURL(url as CFURL, [kCGImageSourceShouldCache: false] as CFDictionary) else {
            throw .logged(.sourceUnavailable, "cannot open source")
        }
        guard let typeId = CGImageSourceGetType(source) as String?, let type = UTType(typeId),
              Self.supportedTypes.contains(where: { type.conforms(to: $0) }) else {
            throw .logged(.unsupportedFormat, "type \(String(describing: CGImageSourceGetType(source)))")
        }
        let count = CGImageSourceGetCount(source)
        guard count > 0 else { throw .logged(.decodeFailed, "no image") }
        // 움직이는 WebP·PNG는 사진 편집기에서 열지 않는다. HEIC는 여러 장이 들어 있어도 대표 이미지를 쓴다.
        if count > 1 && !(type.conforms(to: .heic) || type.conforms(to: .heif)) {
            throw .logged(.unsupportedFormat, "animated image")
        }
        let index = (type.conforms(to: .heic) || type.conforms(to: .heif)) ? CGImageSourceGetPrimaryImageIndex(source) : 0
        guard let props = CGImageSourceCopyPropertiesAtIndex(source, index, nil) as? [CFString: Any],
              let width = props[kCGImagePropertyPixelWidth] as? Int,
              let height = props[kCGImagePropertyPixelHeight] as? Int, width > 0, height > 0 else {
            throw .logged(.decodeFailed, "missing size")
        }
        guard width.multipliedReportingOverflow(by: height).partialValue <= Self.maxSourcePixels else {
            throw .logged(.sourceTooLarge, "\(width)x\(height)")
        }
        let orientation = props[kCGImagePropertyOrientation] as? Int ?? 1
        uprightSize = orientation >= 5 ? PixelSizeValue(width: height, height: width) : PixelSizeValue(width: width, height: height)
        let profile = props[kCGImagePropertyProfileName] as? String
        isSRGB = profile == nil || profile!.localizedCaseInsensitiveContains("sRGB")
        var gainMap = CGImageSourceCopyAuxiliaryDataInfoAtIndex(source, index, kCGImageAuxiliaryDataTypeHDRGainMap) != nil
        if #available(iOS 18, *), !gainMap {
            gainMap = CGImageSourceCopyAuxiliaryDataInfoAtIndex(source, index, kCGImageAuxiliaryDataTypeISOGainMap) != nil
        }
        hasGainMap = gainMap
        self.url = url
        self.type = type
        self.properties = props
        self.source = source
        self.index = index
        self.removeOnClose = removeOnClose
    }

    /// 긴 변이 [maxLongEdge] 이하가 되게 줄여 디코딩한다. 원본보다 키우지 않는다.
    func decode(maxLongEdge: Int) throws(EditorError) -> UprightImage {
        let longEdge = max(1, min(maxLongEdge, max(uprightSize.width, uprightSize.height)))
        let scale = Double(longEdge) / Double(max(uprightSize.width, uprightSize.height))
        let target = PixelSizeValue(width: max(1, Int((Double(uprightSize.width) * scale).rounded())),
                                    height: max(1, Int((Double(uprightSize.height) * scale).rounded())))
        try MemoryBudget.check(bytes: target.width * target.height * 4 * 2)
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceThumbnailMaxPixelSize: longEdge,
            kCGImageSourceShouldCacheImmediately: true,
        ]
        guard let decoded = CGImageSourceCreateThumbnailAtIndex(source, index, options as CFDictionary) else {
            throw .logged(.decodeFailed, "thumbnail failed")
        }
        return UprightImage(cgImage: try Self.normalized(decoded))
    }

    /// sRGB, RGBA 8비트(premultiplied)로 바꾼다. 넓은 색 영역은 sRGB로 변환된다.
    static func normalized(_ image: CGImage) throws(EditorError) -> CGImage {
        guard let context = BitmapContext.make(width: image.width, height: image.height) else {
            throw .logged(.insufficientMemory, "context \(image.width)x\(image.height)")
        }
        context.draw(image, in: CGRect(x: 0, y: 0, width: image.width, height: image.height))
        guard let result = context.makeImage() else { throw .logged(.insufficientMemory, "makeImage") }
        return result
    }

    func close() {
        if removeOnClose { try? FileManager.default.removeItem(at: url) }
    }
}

enum BitmapContext {
    static let sRGB = CGColorSpace(name: CGColorSpace.sRGB)!

    static func make(width: Int, height: Int) -> CGContext? {
        CGContext(data: nil, width: width, height: height, bitsPerComponent: 8, bytesPerRow: 0, space: sRGB,
                  bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)
    }
}

enum MemoryBudget {
    /// 남은 메모리의 60%를 넘는 작업은 시작하지 않는다. 남은 양을 알 수 없으면(시뮬레이터 등) 1.5GB로 본다.
    static func check(bytes: Int) throws(EditorError) {
        let available = Int(os_proc_available_memory())
        let budget = available > 0 ? available * 6 / 10 : 1_500_000_000
        if bytes > budget { throw .logged(.insufficientMemory, "need \(bytes) budget \(budget)") }
    }
}
