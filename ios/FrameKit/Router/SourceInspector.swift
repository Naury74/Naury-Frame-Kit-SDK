import AVFoundation
import ImageIO
import UIKit
import UniformTypeIdentifiers

/// 원본 하나의 종류.
enum SourceKind: Hashable, Sendable {
    case image, animated, video, audio

    var mediaKind: MediaKind {
        switch self {
        case .image, .animated: .image
        case .video: .video
        case .audio: .audio
        }
    }
}

/// 원본 종류 판별, 작은 그림, 원본 그대로 보내기.
enum SourceInspector {
    /// 원본 종류. 보안 범위 URL은 판별하는 동안만 연다.
    /// - Throws: 없는 파일 `sourceUnavailable`, 읽기 권한 없음 `permissionDenied`, 빈 파일 `decodeFailed`, 알 수 없는 종류 `unsupportedFormat`.
    static func kind(_ url: URL) async throws(EditorError) -> SourceKind {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        try OutputStore.checkReadable(url)
        if let source = CGImageSourceCreateWithURL(url as CFURL, [kCGImageSourceShouldCache: false] as CFDictionary),
           let typeId = CGImageSourceGetType(source) as String?, let type = UTType(typeId), type.conforms(to: .image) {
            let animated = CGImageSourceGetCount(source) > 1 && MotionSource.animatedTypes.contains { type.conforms(to: $0) }
            return animated ? .animated : .image
        }
        if let type = UTType(filenameExtension: url.pathExtension) {
            if type.conforms(to: .movie) || type.conforms(to: .video) { return .video }
            if type.conforms(to: .audio) { return .audio }
        }
        let asset = AVURLAsset(url: url)
        if let video = try? await asset.loadTracks(withMediaType: .video), !video.isEmpty { return .video }
        if let audio = try? await asset.loadTracks(withMediaType: .audio), !audio.isEmpty { return .audio }
        throw .logged(.unsupportedFormat, "unknown source kind")
    }

    /// 긴 변 [maxSide]px 작은 그림. 소리는 `nil`.
    static func thumbnail(_ url: URL, kind: SourceKind, maxSide: Int) async -> CGImage? {
        switch kind {
        case .image, .animated:
            guard let source = CGImageSourceCreateWithURL(url as CFURL, nil) else { return nil }
            return CGImageSourceCreateThumbnailAtIndex(source, 0, [
                kCGImageSourceCreateThumbnailFromImageAlways: true, kCGImageSourceCreateThumbnailWithTransform: true,
                kCGImageSourceThumbnailMaxPixelSize: maxSide,
            ] as CFDictionary)
        case .video:
            let generator = AVAssetImageGenerator(asset: AVURLAsset(url: url))
            generator.appliesPreferredTrackTransform = true
            generator.maximumSize = CGSize(width: maxSide, height: maxSide)
            return try? await generator.image(at: .zero).image
        case .audio:
            return nil
        }
    }

    /// 원본을 결과 폴더로 복사해 돌려준다(`isOriginal == true`).
    static func original(_ url: URL, kind: SourceKind) async throws -> EditedMedia {
        let ext = url.pathExtension.isEmpty ? defaultExtension(kind) : url.pathExtension.lowercased()
        let (slot, partial) = try OutputStore.reserve(extension: ext)
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        try OutputStore.checkReadable(url)
        do {
            try FileManager.default.copyItem(at: url, to: partial)
        } catch {
            try? FileManager.default.removeItem(at: partial)
            let code = OutputStore.readErrorCode(error)
            // 읽기 쪽 문제가 아니면 결과를 쓰지 못한 것이다.
            throw EditorError.logged(code == .sourceUnavailable ? .outputWriteFailed : code, "original copy: \(error)")
        }
        let final = try OutputStore.publish(partial: partial, to: slot)
        let bytes = (try? final.resourceValues(forKeys: [.fileSizeKey]).fileSize).map { Int64($0) } ?? 0
        var width = 0, height = 0
        var duration: Int64?
        switch kind {
        case .image, .animated:
            if let source = CGImageSourceCreateWithURL(final as CFURL, nil),
               let props = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any] {
                let w = props[kCGImagePropertyPixelWidth] as? Int ?? 0, h = props[kCGImagePropertyPixelHeight] as? Int ?? 0
                let rotated = (props[kCGImagePropertyOrientation] as? Int ?? 1) >= 5
                (width, height) = rotated ? (h, w) : (w, h)
                if kind == .animated {
                    let count = CGImageSourceGetCount(source)
                    duration = (0..<count).reduce(Int64(0)) { $0 + MotionSource.delayMs(CGImageSourceCopyPropertiesAtIndex(source, $1, nil) as? [CFString: Any]) }
                }
            }
        case .video, .audio:
            let asset = AVURLAsset(url: final)
            if let seconds = try? await asset.load(.duration).seconds, seconds.isFinite { duration = Int64(seconds * 1000) }
            if kind == .video, let track = try? await asset.loadTracks(withMediaType: .video).first,
               let size = try? await track.load(.naturalSize), let transform = try? await track.load(.preferredTransform) {
                let box = CGRect(origin: .zero, size: size).applying(transform)
                width = Int(abs(box.width).rounded())
                height = Int(abs(box.height).rounded())
            }
        }
        let mime = UTType(filenameExtension: ext)?.preferredMIMEType ?? "application/octet-stream"
        let type: MediaType = switch kind {
        case .image: .image
        case .animated: .animatedImage
        case .video: .video
        case .audio: .audio
        }
        return EditedMedia(url: final, mediaType: type, width: width, height: height, mimeType: mime, fileSize: bytes, warnings: [],
                           durationMs: duration, pageCount: nil, recognizedText: nil, isOriginal: true)
    }

    private static func defaultExtension(_ kind: SourceKind) -> String {
        switch kind {
        case .image: "jpg"
        case .animated: "gif"
        case .video: "mov"
        case .audio: "m4a"
        }
    }

    /// 고를 수 있는 할 일. 순서가 보이는 순서다. Android ChooserRules와 같다.
    /// - Parameter mixedEdit: 사진·영상 혼합을 편집할 수 있으면 `true`.
    static func actions(_ kinds: [SourceKind], allowed: Set<EditorAction>, mixedEdit: Bool = true) -> (actions: [EditorAction], mixed: Bool) {
        let groups = Set(kinds.map(\.mediaKind))
        let mixed = groups.count > 1
        let candidates: [EditorAction] = if mixed {
            mixedEdit && !groups.contains(.audio) ? [.editEach, .sendOriginal] : [.sendOriginal]
        } else if groups.contains(.audio) {
            [.editAudio, .sendOriginal]
        } else if groups.contains(.video) {
            [.editVideo, .sendOriginal]
        } else if kinds == [.animated] {
            [.motion, .editPhoto, .sendOriginal]
        } else {
            [.editPhoto, .makePDF, .motion, .sendOriginal]
        }
        return (candidates.filter { allowed.contains($0) }, mixed)
    }
}
