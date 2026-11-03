import AVFoundation
import CoreGraphics
import Foundation

/// 연 원본 영상. 파일은 SDK 임시 폴더에 복사한 것이다.
final class VideoSource: @unchecked Sendable {
    let url: URL
    let asset: AVURLAsset
    let videoTrack: AVAssetTrack
    let audioTrack: AVAssetTrack?
    let duration: Micros
    /// 방향을 적용한 크기(px).
    let uprightSize: PixelSizeValue
    /// 원본 프레임 → 똑바로 선 프레임(y 아래 방향, 원점 왼쪽 위).
    let orientation: CGAffineTransform
    let naturalSize: CGSize
    let frameRate: Double
    let isHDR: Bool
    private let removeOnClose: Bool

    var hasAudio: Bool { audioTrack != nil }

    static func open(url: URL, removeOnClose: Bool) async throws(EditorError) -> VideoSource {
        let asset = AVURLAsset(url: url, options: [AVURLAssetPreferPreciseDurationAndTimingKey: true])
        do {
            guard try await asset.load(.isPlayable) else { throw EditorError.logged(.unsupportedFormat, "not playable") }
            guard let video = try await asset.loadTracks(withMediaType: .video).first else {
                throw EditorError.logged(.invalidSource, "no video track")
            }
            let audio = try await asset.loadTracks(withMediaType: .audio).first
            let duration = try await asset.load(.duration)
            let (natural, transform, rate, formats) = try await video.load(.naturalSize, .preferredTransform, .nominalFrameRate, .formatDescriptions)
            guard natural.width > 0, natural.height > 0, duration.seconds > 0, duration.isNumeric else {
                throw EditorError.logged(.decodeFailed, "bad header")
            }
            let hdr = formats.contains { format in
                let transfer = CMFormatDescriptionGetExtension(format, extensionKey: kCMFormatDescriptionExtension_TransferFunction) as? String
                return transfer == (kCMFormatDescriptionTransferFunction_ITU_R_2100_HLG as String)
                    || transfer == (kCMFormatDescriptionTransferFunction_SMPTE_ST_2084_PQ as String)
            }
            // preferredTransform은 원점이 왼쪽 위인 좌표계에서 원본 → 표시 방향이다. 결과 상자를 원점으로 옮긴다.
            let box = CGRect(origin: .zero, size: natural).applying(transform)
            let orientation = transform.concatenating(CGAffineTransform(translationX: -box.minX, y: -box.minY))
            return VideoSource(url: url, asset: asset, videoTrack: video, audioTrack: audio,
                               duration: Micros((duration.seconds * 1_000_000).rounded()),
                               uprightSize: PixelSizeValue(width: Int(abs(box.width).rounded()), height: Int(abs(box.height).rounded())),
                               orientation: orientation, naturalSize: natural, frameRate: Double(rate), isHDR: hdr, removeOnClose: removeOnClose)
        } catch let error as EditorError {
            throw error
        } catch {
            throw .logged(.decodeFailed, "video open: \(error)")
        }
    }

    private init(url: URL, asset: AVURLAsset, videoTrack: AVAssetTrack, audioTrack: AVAssetTrack?, duration: Micros,
                 uprightSize: PixelSizeValue, orientation: CGAffineTransform, naturalSize: CGSize, frameRate: Double, isHDR: Bool,
                 removeOnClose: Bool) {
        self.url = url
        self.asset = asset
        self.videoTrack = videoTrack
        self.audioTrack = audioTrack
        self.duration = duration
        self.uprightSize = uprightSize
        self.orientation = orientation
        self.naturalSize = naturalSize
        self.frameRate = frameRate
        self.isHDR = isHDR
        self.removeOnClose = removeOnClose
    }

    /// 원본 시각 [time]의 똑바로 선 프레임. 긴 변이 [maxSide] 이하.
    func frame(at time: Micros, maxSide: CGFloat) async -> UprightImage? {
        let generator = AVAssetImageGenerator(asset: asset)
        generator.appliesPreferredTrackTransform = true
        generator.maximumSize = CGSize(width: maxSide, height: maxSide)
        guard let image = try? await generator.image(at: time.cmTime).image else { return nil }
        return UprightImage(cgImage: image)
    }

    func close() {
        if removeOnClose { try? FileManager.default.removeItem(at: url) }
    }
}

/// 음악·음성 원본.
final class AudioSource: @unchecked Sendable {
    let url: URL
    let asset: AVURLAsset
    let track: AVAssetTrack
    let duration: Micros
    let name: String

    static func open(url: URL, name: String) async throws(EditorError) -> AudioSource {
        let asset = AVURLAsset(url: url)
        do {
            guard let track = try await asset.loadTracks(withMediaType: .audio).first else {
                throw EditorError.logged(.invalidSource, "no audio track")
            }
            let duration = try await asset.load(.duration)
            guard duration.seconds > 0 else { throw EditorError.logged(.decodeFailed, "empty audio") }
            return AudioSource(url: url, asset: asset, track: track, duration: Micros((duration.seconds * 1_000_000).rounded()), name: name)
        } catch let error as EditorError {
            throw error
        } catch {
            throw .logged(.decodeFailed, "audio open: \(error)")
        }
    }

    func close() { try? FileManager.default.removeItem(at: url) }

    private init(url: URL, asset: AVURLAsset, track: AVAssetTrack, duration: Micros, name: String) {
        self.url = url
        self.asset = asset
        self.track = track
        self.duration = duration
        self.name = name
    }
}

extension Micros {
    var cmTime: CMTime { CMTime(value: self, timescale: 1_000_000) }
}

extension CMTime {
    var micros: Micros { isNumeric ? Micros((seconds * 1_000_000).rounded()) : 0 }
}
