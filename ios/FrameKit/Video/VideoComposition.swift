import AVFoundation
import CoreGraphics
import UIKit
internal import FrameKitCore

/// 편집 상태로 만든 재생·저장용 합성.
struct BuiltComposition: @unchecked Sendable {
    let asset: AVMutableComposition
    let videoComposition: AVMutableVideoComposition
    let audioMix: AVMutableAudioMix
    let size: CGSize
    let duration: Micros
}

enum VideoCompositionBuilder {
    static let maxLongSide = 4096.0

    /// 결과 화면 크기. 첫 클립의 자른 크기(또는 고른 비율)를 짧은 변 상한·긴 변 4096으로 줄이고 짝수로 맞춘다. 키우지 않는다.
    static func canvasSize(_ edit: VideoEdit, sources: [VideoSource], maxShortSide: Int) -> CGSize {
        guard let first = edit.clips.first, sources.indices.contains(first.source) else { return CGSize(width: 2, height: 2) }
        let crop = GeometryMath(source: sources[first.source].uprightSize, geometry: first.effects.geometry).cropPixelSize(first.effects.geometry.crop)
        var base = crop
        if let w = edit.canvas.aspectWidth, let h = edit.canvas.aspectHeight, w > 0, h > 0 {
            let ratio = Double(w) / Double(h)
            base = crop.width / crop.height > ratio ? CGSize(width: crop.height * ratio, height: crop.height)
                : CGSize(width: crop.width, height: crop.width / ratio)
        }
        let scale = min(1, Double(maxShortSide) / min(base.width, base.height), maxLongSide / max(base.width, base.height))
        func even(_ v: Double) -> Double { max(2, (v / 2).rounded() * 2) }
        return CGSize(width: even(base.width * scale), height: even(base.height * scale))
    }

    /// - Parameters:
    ///   - frameRate: 초당 프레임. 저장은 상한, 미리 보기는 30.
    ///   - singleClip: 자르기·회전 도구에서 그 클립만 자르기 없이 보여 줄 때의 클립 위치.
    /// - Parameter overlays: `false`면 요소·워터마크를 빼고 만든다(미리 보기는 화면 위에 따로 그린다). 가리기는 항상 넣는다.
    static func build(_ edit: VideoEdit, sources: [VideoSource], audio: [AudioSource], maxShortSide: Int, frameRate: Int,
                      overlays: Bool = true, singleClip: Int? = nil) throws(EditorError) -> BuiltComposition {
        var edit = edit
        if let index = singleClip, edit.clips.indices.contains(index) {
            var clip = edit.clips[index]
            clip.effects.geometry.crop = .full
            edit.clips = [clip]
            edit.canvas = CanvasState()
        }
        guard !edit.clips.isEmpty else { throw .logged(.invalidProject, "no clips") }
        let size = canvasSize(edit, sources: sources, maxShortSide: maxShortSide)
        let composition = AVMutableComposition()
        guard let videoTrack = composition.addMutableTrack(withMediaType: .video, preferredTrackID: kCMPersistentTrackID_Invalid) else {
            throw .logged(.unknown, "video track")
        }
        let originalAudio = edit.clips.contains { sources[$0.source].hasAudio && !$0.muted }
            ? composition.addMutableTrack(withMediaType: .audio, preferredTrackID: kCMPersistentTrackID_Invalid) : nil
        let originalParams = originalAudio.map { AVMutableAudioMixInputParameters(track: $0) }
        let frame = VideoFrameExtras(size: size, masks: singleClip == nil ? edit.masks : [], overlays: overlays ? edit.overlays : [],
                                     watermark: overlays ? edit.watermark : nil)
        var instructions: [VideoInstruction] = []
        var cursor: Micros = 0
        do {
            for clip in edit.clips {
                guard sources.indices.contains(clip.source) else { throw EditorError.logged(.invalidProject, "unknown source") }
                let source = sources[clip.source]
                let range = CMTimeRange(start: clip.sourceRange.start.cmTime, duration: clip.sourceRange.duration.cmTime)
                let at = cursor.cmTime
                let out = clip.outputDuration
                try videoTrack.insertTimeRange(range, of: source.videoTrack, at: at)
                if clip.speed != 1 {
                    videoTrack.scaleTimeRange(CMTimeRange(start: at, duration: range.duration), toDuration: out.cmTime)
                }
                if let originalAudio, let audioTrack = source.audioTrack, !clip.muted {
                    try originalAudio.insertTimeRange(range, of: audioTrack, at: at)
                    if clip.speed != 1 { originalAudio.scaleTimeRange(CMTimeRange(start: at, duration: range.duration), toDuration: out.cmTime) }
                    originalParams?.setVolume(Float(clip.volume), at: at)
                    // 원본 시간으로 저장한 음소거 구간을 결과 시간으로 바꿔 소리를 끈다.
                    for muted in TimeRangeUs.normalize(clip.mutedRanges) {
                        let s = max(muted.start, clip.sourceRange.start), e = min(muted.end, clip.sourceRange.end)
                        guard e > s else { continue }
                        let os = cursor + Micros((Double(s - clip.sourceRange.start) / clip.speed).rounded())
                        let oe = cursor + Micros((Double(e - clip.sourceRange.start) / clip.speed).rounded())
                        originalParams?.setVolume(0, at: os.cmTime)
                        originalParams?.setVolume(Float(clip.volume), at: oe.cmTime)
                    }
                } else if let originalAudio {
                    originalParams?.setVolume(0, at: at)
                    _ = originalAudio
                }
                let transform = frameTransform(clip: clip, source: source, canvas: size, fill: edit.canvas.fit == .fill)
                let spec = clip.effects.hasColor || clip.effects.adjustments[.fade] != nil ? clip.effects.colorSpec(grainSeed: edit.grainSeed) : nil
                instructions.append(VideoInstruction(timeRange: CMTimeRange(start: at, duration: out.cmTime), trackID: videoTrack.trackID,
                                                     transform: transform, colorSpec: spec, frame: frame))
                cursor += out
            }
        } catch let error as EditorError {
            throw error
        } catch {
            throw .logged(.decodeFailed, "compose: \(error)")
        }
        let duration = cursor
        var params: [AVMutableAudioMixInputParameters] = originalParams.map { [$0] } ?? []
        for clip in edit.audio where audio.indices.contains(clip.source) {
            let segments = clip.segments(videoDuration: duration)
            guard !segments.isEmpty, let track = composition.addMutableTrack(withMediaType: .audio, preferredTrackID: kCMPersistentTrackID_Invalid)
            else { continue }
            for segment in segments {
                try? track.insertTimeRange(CMTimeRange(start: segment.source.start.cmTime, duration: segment.source.duration.cmTime),
                                           of: audio[clip.source].track, at: segment.output.cmTime)
            }
            let p = AVMutableAudioMixInputParameters(track: track)
            let range = clip.outputRange(videoDuration: duration)
            let volume = Float(clip.volume)
            let fadeIn = min(clip.fadeIn, range.duration / 2), fadeOut = min(clip.fadeOut, range.duration / 2)
            p.setVolume(fadeIn > 0 ? 0 : volume, at: .zero)
            if fadeIn > 0 {
                p.setVolumeRamp(fromStartVolume: 0, toEndVolume: volume, timeRange: CMTimeRange(start: range.start.cmTime, duration: fadeIn.cmTime))
            }
            if fadeOut > 0 {
                p.setVolumeRamp(fromStartVolume: volume, toEndVolume: 0,
                                timeRange: CMTimeRange(start: (range.end - fadeOut).cmTime, duration: fadeOut.cmTime))
            }
            params.append(p)
        }
        let videoComposition = AVMutableVideoComposition()
        videoComposition.customVideoCompositorClass = FrameKitCompositor.self
        videoComposition.renderSize = size
        videoComposition.frameDuration = CMTime(value: 1, timescale: CMTimeScale(max(1, frameRate)))
        videoComposition.instructions = instructions
        let mix = AVMutableAudioMix()
        mix.inputParameters = params
        return BuiltComposition(asset: composition, videoComposition: videoComposition, audioMix: mix, size: size, duration: duration)
    }

    /// 원본 프레임(y 아래 방향) → 결과 화면 변환: 방향 → 기하(core) → 화면 비율 맞추기·채우기.
    static func frameTransform(clip: VideoClipState, source: VideoSource, canvas: CGSize, fill: Bool) -> CGAffineTransform {
        let math = GeometryMath(source: source.uprightSize, geometry: clip.effects.geometry)
        let crop = math.cropPixelSize(clip.effects.geometry.crop)
        let cropSize = PixelSizeValue(width: max(1, Int(crop.width.rounded())), height: max(1, Int(crop.height.rounded())))
        let geometry = math.sourceToOutput(crop: clip.effects.geometry.crop, output: cropSize)
        let w = CGFloat(cropSize.width), h = CGFloat(cropSize.height)
        let scale = fill ? max(canvas.width / w, canvas.height / h) : min(canvas.width / w, canvas.height / h)
        let place = CGAffineTransform(scaleX: scale, y: scale)
            .concatenating(CGAffineTransform(translationX: (canvas.width - w * scale) / 2, y: (canvas.height - h * scale) / 2))
        return source.orientation.concatenating(geometry).concatenating(place)
    }
}

/// 영상 저장(H.264 + AAC MP4).
enum VideoExporter {
    static func export(_ edit: VideoEdit, sources: [VideoSource], audio: [AudioSource], config: VideoExportConfig,
                       progress: @escaping @Sendable (Double?) -> Void) async throws -> EditedMedia {
        let frameRate = max(1, min(config.maxFrameRate, edit.clips.map { Int((sources[$0.source].frameRate * $0.speed).rounded()) }.max() ?? 30))
        let built = try VideoCompositionBuilder.build(edit, sources: sources, audio: audio, maxShortSide: config.maxShortSide, frameRate: frameRate)
        let bitrate = Double(built.size.width * built.size.height) * Double(frameRate) * 0.15 + 192_000
        try OutputStore.checkFreeSpace(bytes: Int64(bitrate / 8 * Double(built.duration) / 1_000_000) + 5_000_000)
        guard let session = AVAssetExportSession(asset: built.asset, presetName: AVAssetExportPresetHighestQuality) else {
            throw EditorError.logged(.unsupportedOperation, "export session")
        }
        let (slot, partial) = try OutputStore.reserve(extension: "mp4")
        let output = partial.deletingPathExtension().appendingPathExtension("mp4")
        session.outputURL = output
        session.outputFileType = .mp4
        session.videoComposition = built.videoComposition
        session.audioMix = built.audioMix
        session.audioTimePitchAlgorithm = .spectral
        session.shouldOptimizeForNetworkUse = true
        let box = SessionBox(session: session)
        let poll = Task {
            while !Task.isCancelled {
                progress(Double(box.session.progress))
                try? await Task.sleep(for: .milliseconds(200))
            }
        }
        defer { poll.cancel() }
        await withTaskCancellationHandler {
            await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
                box.session.exportAsynchronously { continuation.resume() }
            }
        } onCancel: {
            box.session.cancelExport()
        }
        if Task.isCancelled || session.status == .cancelled {
            try? FileManager.default.removeItem(at: output)
            throw CancellationError()
        }
        guard session.status == .completed else {
            try? FileManager.default.removeItem(at: output)
            throw EditorError.logged(.encodeFailed, "export: \(String(describing: session.error))")
        }
        // 다 쓴 파일을 다시 열어 영상 트랙과 길이를 확인한다.
        let check = AVURLAsset(url: output)
        guard let track = try? await check.loadTracks(withMediaType: .video).first,
              let natural = try? await track.load(.naturalSize), let length = try? await check.load(.duration), length.seconds > 0 else {
            try? FileManager.default.removeItem(at: output)
            throw EditorError.logged(.encodeFailed, "verify")
        }
        try FileManager.default.moveItemSafely(output, to: partial)
        let final = try OutputStore.publish(partial: partial, to: slot)
        var warnings: [ExportWarning] = []
        if edit.clips.contains(where: { sources[$0.source].isHDR }) { warnings.append(.hdrConvertedToSDR) }
        if edit.clips.contains(where: { sources[$0.source].frameRate * $0.speed > Double(config.maxFrameRate) + 0.5 }) {
            warnings.append(.frameRateReduced)
        }
        let size = (try? final.resourceValues(forKeys: [.fileSizeKey]).fileSize).map { Int64($0) } ?? 0
        return EditedMedia(url: final, mediaType: .video, width: Int(natural.width), height: Int(natural.height), mimeType: "video/mp4",
                           fileSize: size, warnings: warnings, durationMs: Int64(length.seconds * 1000), pageCount: nil, recognizedText: nil,
                           isOriginal: false)
    }

    private final class SessionBox: @unchecked Sendable {
        let session: AVAssetExportSession
        init(session: AVAssetExportSession) { self.session = session }
    }
}

extension FileManager {
    func moveItemSafely(_ from: URL, to: URL) throws(EditorError) {
        do {
            if fileExists(atPath: to.path) { try removeItem(at: to) }
            try moveItem(at: from, to: to)
        } catch {
            throw .logged(.outputWriteFailed, "move: \(error)")
        }
    }
}

/// 타임라인 작은 그림.
final class VideoThumbnailer: @unchecked Sendable {
    private let generators: [AVAssetImageGenerator]
    private let cache = NSCache<NSString, CGImage>()

    init(sources: [VideoSource], height: CGFloat = 160) {
        generators = sources.map { source in
            let generator = AVAssetImageGenerator(asset: source.asset)
            generator.appliesPreferredTrackTransform = true
            generator.maximumSize = CGSize(width: height * 3, height: height)
            generator.requestedTimeToleranceBefore = CMTime(value: 1, timescale: 4)
            generator.requestedTimeToleranceAfter = CMTime(value: 1, timescale: 4)
            return generator
        }
        cache.countLimit = 240
    }

    /// 250ms 단위로 묶은 원본 시각의 그림.
    func image(source: Int, at time: Micros) async -> CGImage? {
        guard generators.indices.contains(source) else { return nil }
        let bucket = time / 250_000 * 250_000
        let key = "\(source)/\(bucket)" as NSString
        if let cached = cache.object(forKey: key) { return cached }
        guard let image = try? await generators[source].image(at: bucket.cmTime).image else { return nil }
        cache.setObject(image, forKey: key)
        return image
    }
}
