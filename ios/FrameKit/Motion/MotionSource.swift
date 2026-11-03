import CoreGraphics
import Foundation
import ImageIO
import UniformTypeIdentifiers

/// 움직이는 이미지 편집기가 연 원본 파일 하나.
final class MotionSource: @unchecked Sendable {
    /// 움직이는 원본 파일 크기 상한(100MiB).
    static let maxAnimationFileBytes = 100 * 1024 * 1024
    /// 프레임을 모두 풀었을 때 쓸 메모리 상한(256MiB).
    static let maxAnimationBytes = 256 * 1024 * 1024
    static let animatedTypes: [UTType] = [.gif, .webP, .png]
    static let stillTypes: [UTType] = [.jpeg, .png, .heic, .heif, .webP, .gif, .tiff, .bmp]

    let url: URL
    let isAnimated: Bool
    /// 정방향 너비/높이.
    let aspect: Double
    private let source: CGImageSource
    private let index: Int
    private let longSide: Int
    private let removeOnClose: Bool

    init(url: URL, removeOnClose: Bool) throws(EditorError) {
        guard let source = CGImageSourceCreateWithURL(url as CFURL, [kCGImageSourceShouldCache: false] as CFDictionary) else {
            throw .logged(.sourceUnavailable, "motion open")
        }
        guard let typeId = CGImageSourceGetType(source) as String?, let type = UTType(typeId),
              Self.stillTypes.contains(where: { type.conforms(to: $0) }) else {
            throw .logged(.unsupportedFormat, "motion type \(String(describing: CGImageSourceGetType(source)))")
        }
        let count = CGImageSourceGetCount(source)
        guard count > 0 else { throw .logged(.decodeFailed, "motion empty") }
        let animated = count > 1 && Self.animatedTypes.contains { type.conforms(to: $0) }
        let index = (type.conforms(to: .heic) || type.conforms(to: .heif)) ? CGImageSourceGetPrimaryImageIndex(source) : 0
        guard let props = CGImageSourceCopyPropertiesAtIndex(source, index, nil) as? [CFString: Any],
              let width = props[kCGImagePropertyPixelWidth] as? Int, let height = props[kCGImagePropertyPixelHeight] as? Int,
              width > 0, height > 0 else {
            throw .logged(.decodeFailed, "motion size")
        }
        guard width.multipliedReportingOverflow(by: height).partialValue <= SourceImage.maxSourcePixels else {
            throw .logged(.sourceTooLarge, "motion \(width)x\(height)")
        }
        if animated {
            let bytes = (try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0
            if bytes > Self.maxAnimationFileBytes { throw .logged(.sourceTooLarge, "animation \(bytes) bytes") }
        }
        let orientation = animated ? 1 : props[kCGImagePropertyOrientation] as? Int ?? 1
        let upright = orientation >= 5 ? (height, width) : (width, height)
        self.url = url
        self.source = source
        self.index = index
        self.isAnimated = animated
        self.aspect = Double(upright.0) / Double(upright.1)
        self.longSide = max(width, height)
        self.removeOnClose = removeOnClose
    }

    /// 긴 변이 [maxLongEdge] 이하인 정방향 sRGB 그림. 첫 프레임만 쓴다.
    func decodeStill(maxLongEdge: Int) -> CGImage? {
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceThumbnailMaxPixelSize: max(1, min(maxLongEdge, longSide)),
            kCGImageSourceShouldCacheImmediately: true,
        ]
        guard let image = CGImageSourceCreateThumbnailAtIndex(source, index, options as CFDictionary) else { return nil }
        return try? SourceImage.normalized(image)
    }

    /// 모든 프레임을 긴 변 [maxLongEdge] 이하로 푼다. [maxFrames]를 넘는 프레임은 버린다.
    /// - Parameter maxBytes: 모든 프레임이 쓸 메모리 상한. 넘으면 프레임을 더 줄인다.
    /// - Returns: 프레임과 잘렸는지 여부.
    func decodeAnimation(maxLongEdge: Int, maxFrames: Int, maxBytes: Int) throws(EditorError) -> (AnimatedFrames, trimmed: Bool) {
        let total = CGImageSourceGetCount(source)
        let count = min(total, max(1, maxFrames))
        guard let first = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any],
              let width = first[kCGImagePropertyPixelWidth] as? Int, let height = first[kCGImagePropertyPixelHeight] as? Int,
              width > 0, height > 0 else {
            throw .logged(.decodeFailed, "animation size")
        }
        var scale = min(1, Double(maxLongEdge) / Double(max(width, height)))
        let perFrame = Double(maxBytes) / Double(count)
        let bytes = Double(width * height * 4) * scale * scale
        if bytes > perFrame { scale *= (perFrame / bytes).squareRoot() }
        let target = (w: max(1, Int((Double(width) * scale).rounded())), h: max(1, Int((Double(height) * scale).rounded())))
        try MemoryBudget.check(bytes: target.w * target.h * 4 * (count + 2))
        var frames: [CGImage] = []
        var durations: [Int64] = []
        frames.reserveCapacity(count)
        for i in 0..<count {
            guard let image = CGImageSourceCreateImageAtIndex(source, i, [kCGImageSourceShouldCacheImmediately: true] as CFDictionary),
                  let context = BitmapContext.make(width: target.w, height: target.h) else {
                // 깨진 뒷부분은 버리고 앞 프레임만 쓴다.
                if frames.isEmpty { throw .logged(.decodeFailed, "animation frame \(i)") }
                break
            }
            context.interpolationQuality = .high
            context.draw(image, in: CGRect(x: 0, y: 0, width: target.w, height: target.h))
            guard let scaled = context.makeImage() else { throw .logged(.insufficientMemory, "animation frame") }
            frames.append(scaled)
            durations.append(Self.delayMs(CGImageSourceCopyPropertiesAtIndex(source, i, nil) as? [CFString: Any]))
        }
        let animation = AnimatedFrames(frames: frames, durationsMs: durations, width: target.w, height: target.h)
        return (animation, total > count)
    }

    func close() {
        if removeOnClose { try? FileManager.default.removeItem(at: url) }
    }

    /// 프레임 지연(밀리초). 10ms 이하는 브라우저처럼 100ms로 본다.
    static func delayMs(_ properties: [CFString: Any]?) -> Int64 {
        let pairs: [(CFString, CFString, CFString)] = [
            (kCGImagePropertyGIFDictionary, kCGImagePropertyGIFUnclampedDelayTime, kCGImagePropertyGIFDelayTime),
            (kCGImagePropertyWebPDictionary, kCGImagePropertyWebPUnclampedDelayTime, kCGImagePropertyWebPDelayTime),
            (kCGImagePropertyPNGDictionary, kCGImagePropertyAPNGUnclampedDelayTime, kCGImagePropertyAPNGDelayTime),
        ]
        for (dictionary, unclamped, clamped) in pairs {
            guard let values = properties?[dictionary] as? [CFString: Any] else { continue }
            let seconds = (values[unclamped] as? Double) ?? (values[clamped] as? Double) ?? 0.1
            let ms = Int64((seconds * 1000).rounded())
            return ms <= 10 ? 100 : ms
        }
        return 100
    }
}

/// 사진을 필요할 때 풀고 최근 몇 장만 들고 있는 캐시.
final class SlideCache: @unchecked Sendable {
    private let sources: [MotionSource]
    private let longEdge: Int
    private let capacity: Int
    private let lock = NSLock()
    private var entries: [(index: Int, image: CGImage)] = []

    init(sources: [MotionSource], longEdge: Int, capacity: Int) {
        self.sources = sources
        self.longEdge = longEdge
        self.capacity = max(1, capacity)
    }

    func image(_ index: Int) -> CGImage? {
        guard sources.indices.contains(index) else { return nil }
        lock.lock()
        if let found = entries.firstIndex(where: { $0.index == index }) {
            let entry = entries.remove(at: found)
            entries.append(entry)
            lock.unlock()
            return entry.image
        }
        lock.unlock()
        guard let image = sources[index].decodeStill(maxLongEdge: longEdge) else { return nil }
        lock.lock()
        defer { lock.unlock() }
        entries.removeAll { $0.index == index }
        entries.append((index, image))
        if entries.count > capacity { entries.removeFirst(entries.count - capacity) }
        return image
    }
}
