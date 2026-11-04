import AVFoundation
import CoreGraphics
import CoreVideo
import Foundation
internal import FrameKitCore

/// 저장할 형식·크기.
struct MotionExportSettings: Hashable, Sendable {
    var format: MotionFormat
    var longEdge: Int
    var frameRate: Int
    var loopCount: Int
}

/// 움직이는 이미지 저장. 프레임을 하나씩 그려 바로 인코더에 넘긴다.
enum MotionExporter {
    /// MP4 진행률 중 화면 부분 몫. 나머지는 소리 넣기.
    private static let videoShare = 0.8

    static func export(_ project: MotionProject, media: MotionMedia, audio: [AudioSource], settings: MotionExportSettings,
                       progress: @escaping @Sendable (Double) -> Void) async throws -> EditedMedia {
        let size = MotionTimeline.outputSize(project, media, maxLongEdge: settings.longEdge)
        let frames = MotionTimeline.outputFrames(project, media, fps: settings.frameRate)
        let duration = MotionTimeline.durationMs(project, media)
        let spec: ColorEffectSpec? = project.hasColor ? project.colorSpec : nil
        let ext = settings.format == .mp4 ? "mp4" : "gif"
        let (slot, partial) = try OutputStore.reserve(extension: ext)
        let work = try OutputStore.temporaryDirectory.appendingPathComponent("motion-\(UUID().uuidString).\(ext)")
        defer { try? FileManager.default.removeItem(at: work) }
        let estimate = settings.format == .mp4 ? Int64(Double(size.width * size.height) * Double(settings.frameRate) * 0.12 / 8 * Double(duration) / 1000)
            : Int64(size.width * size.height) * Int64(frames.count)
        try OutputStore.checkFreeSpace(bytes: estimate + 5_000_000)
        try MemoryBudget.check(bytes: Int(size.width * size.height) * 4 * 6)

        func frame(_ f: OutputFrame) throws -> CGImage {
            try Task.checkCancellation()
            guard let image = autoreleasepool(invoking: { MotionRenderer.render(project, media: media, timeMs: f.timeMs, size: size, colorSpec: spec) })
            else { throw EditorError.logged(.insufficientMemory, "motion frame") }
            return image
        }

        do {
            switch settings.format {
            case .gif:
                let encoder = try GifEncoder(url: work, width: Int(size.width), height: Int(size.height), loopCount: settings.loopCount)
                for (i, f) in frames.enumerated() {
                    try encoder.addFrame(try frame(f), durationMs: f.durationMs)
                    progress(Double(i + 1) / Double(frames.count))
                }
                try encoder.finish()
            case .mp4:
                let withAudio = !project.audio.isEmpty && !audio.isEmpty
                let videoURL = withAudio ? work.deletingPathExtension().appendingPathExtension("video.mp4") : work
                defer { if withAudio { try? FileManager.default.removeItem(at: videoURL) } }
                try await writeVideo(to: videoURL, size: size, frameRate: settings.frameRate, frames: frames, durationMs: duration,
                                     render: frame) { progress($0 * (withAudio ? videoShare : 1)) }
                if withAudio {
                    try await addAudio(video: videoURL, clips: project.audio, sources: audio, durationMs: duration, output: work)
                    progress(1)
                }
            }
        } catch is CancellationError {
            throw CancellationError()
        } catch let error as EditorError {
            throw error
        } catch {
            throw ExportErrors.map(error, context: "motion export")
        }
        try Task.checkCancellation()
        try FileManager.default.moveItemSafely(work, to: partial)
        let final = try OutputStore.publish(partial: partial, to: slot)
        let bytes = (try? final.resourceValues(forKeys: [.fileSizeKey]).fileSize).map { Int64($0) } ?? 0
        guard bytes > 0 else { throw EditorError.logged(.encodeFailed, "empty motion output") }
        return EditedMedia(url: final, mediaType: settings.format == .mp4 ? .video : .animatedImage, width: Int(size.width), height: Int(size.height),
                           mimeType: settings.format.mimeType, fileSize: bytes, warnings: [], durationMs: duration, pageCount: nil,
                           recognizedText: nil, isOriginal: false)
    }

    /// H.264 영상. 프레임마다 길이가 달라도 되며, 마지막 프레임 길이는 세션 끝으로 지킨다.
    private static func writeVideo(to url: URL, size: CGSize, frameRate: Int, frames: [OutputFrame], durationMs: Int64,
                                   render: (OutputFrame) throws -> CGImage, progress: (Double) -> Void) async throws {
        let width = Int(size.width), height = Int(size.height)
        let fps = min(max(frameRate, 1), 60)
        let bitrate = min(max(Double(width * height * fps) * 0.12, 1_000_000), 20_000_000)
        let writer: AVAssetWriter
        do { writer = try AVAssetWriter(outputURL: url, fileType: .mp4) } catch { throw EditorError.logged(.outputWriteFailed, "writer: \(error)") }
        let settings: [String: Any] = [
            AVVideoCodecKey: AVVideoCodecType.h264,
            AVVideoWidthKey: width,
            AVVideoHeightKey: height,
            AVVideoCompressionPropertiesKey: [
                AVVideoAverageBitRateKey: Int(bitrate),
                AVVideoExpectedSourceFrameRateKey: fps,
                AVVideoMaxKeyFrameIntervalKey: fps,
                AVVideoProfileLevelKey: AVVideoProfileLevelH264HighAutoLevel,
            ],
            AVVideoColorPropertiesKey: [
                AVVideoColorPrimariesKey: AVVideoColorPrimaries_ITU_R_709_2,
                AVVideoTransferFunctionKey: AVVideoTransferFunction_ITU_R_709_2,
                AVVideoYCbCrMatrixKey: AVVideoYCbCrMatrix_ITU_R_709_2,
            ],
        ]
        guard writer.canApply(outputSettings: settings, forMediaType: .video) else {
            throw EditorError.logged(.unsupportedOperation, "h264 \(width)x\(height)")
        }
        let input = AVAssetWriterInput(mediaType: .video, outputSettings: settings)
        input.expectsMediaDataInRealTime = false
        let adaptor = AVAssetWriterInputPixelBufferAdaptor(assetWriterInput: input, sourcePixelBufferAttributes: [
            kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA,
            kCVPixelBufferWidthKey as String: width,
            kCVPixelBufferHeightKey as String: height,
            kCVPixelBufferCGImageCompatibilityKey as String: true,
            kCVPixelBufferCGBitmapContextCompatibilityKey as String: true,
        ])
        guard writer.canAdd(input) else { throw EditorError.logged(.encodeFailed, "writer input") }
        writer.add(input)
        guard writer.startWriting() else { throw ExportErrors.map(writer.error, context: "start") }
        writer.startSession(atSourceTime: .zero)
        do {
            var lastTime: Int64 = -1
            for (i, f) in frames.enumerated() {
                let image = try render(f)
                while !input.isReadyForMoreMediaData {
                    if writer.status == .failed { throw ExportErrors.map(writer.error, context: "writer") }
                    try await Task.sleep(for: .milliseconds(5))
                }
                guard let pool = adaptor.pixelBufferPool else { throw EditorError.logged(.encodeFailed, "pixel pool") }
                var buffer: CVPixelBuffer?
                guard CVPixelBufferPoolCreatePixelBuffer(nil, pool, &buffer) == kCVReturnSuccess, let buffer else {
                    throw EditorError.logged(.insufficientMemory, "pixel buffer")
                }
                try draw(image, into: buffer)
                let time = max(f.timeMs, lastTime + 1)
                lastTime = time
                guard adaptor.append(buffer, withPresentationTime: CMTime(value: time, timescale: 1000)) else {
                    throw ExportErrors.map(writer.error, context: "append")
                }
                progress(Double(i + 1) / Double(frames.count))
            }
            input.markAsFinished()
            writer.endSession(atSourceTime: CMTime(value: max(durationMs, lastTime + 1), timescale: 1000))
            await writer.finishWriting()
            guard writer.status == .completed else { throw ExportErrors.map(writer.error, context: "finish") }
        } catch {
            writer.cancelWriting()
            throw error
        }
    }

    private static func draw(_ image: CGImage, into buffer: CVPixelBuffer) throws {
        CVPixelBufferLockBaseAddress(buffer, [])
        defer { CVPixelBufferUnlockBaseAddress(buffer, []) }
        guard let context = CGContext(data: CVPixelBufferGetBaseAddress(buffer), width: CVPixelBufferGetWidth(buffer),
                                      height: CVPixelBufferGetHeight(buffer), bitsPerComponent: 8,
                                      bytesPerRow: CVPixelBufferGetBytesPerRow(buffer), space: BitmapContext.sRGB,
                                      bitmapInfo: CGImageAlphaInfo.premultipliedFirst.rawValue | CGBitmapInfo.byteOrder32Little.rawValue) else {
            throw EditorError.logged(.encodeFailed, "pixel context")
        }
        context.draw(image, in: CGRect(x: 0, y: 0, width: CVPixelBufferGetWidth(buffer), height: CVPixelBufferGetHeight(buffer)))
    }

    /// 영상 파일에 음악·음성을 섞어 넣는다: 소리만 AAC로 만든 뒤 화면과 함께 그대로 옮겨 담는다.
    private static func addAudio(video: URL, clips: [AudioClipState], sources: [AudioSource], durationMs: Int64, output: URL) async throws {
        let durationUs = Micros(durationMs) * 1000
        let range = CMTimeRange(start: .zero, duration: durationUs.cmTime)
        let sound = AVMutableComposition()
        let mix = AVMutableAudioMix()
        mix.inputParameters = AudioClipMixer.add(clips, sources: sources, duration: durationUs, to: sound)
        let audioURL = video.deletingPathExtension().appendingPathExtension("m4a")
        defer { try? FileManager.default.removeItem(at: audioURL) }
        let hasSound = !mix.inputParameters.isEmpty
        if hasSound {
            guard let session = AVAssetExportSession(asset: sound, presetName: AVAssetExportPresetAppleM4A) else {
                throw EditorError.logged(.unsupportedOperation, "audio session")
            }
            session.audioMix = mix
            session.timeRange = range
            try await run(session, to: audioURL, type: .m4a)
        }
        let combined = AVMutableComposition()
        let videoAsset = AVURLAsset(url: video)
        guard let videoTrack = try await videoAsset.loadTracks(withMediaType: .video).first,
              let target = combined.addMutableTrack(withMediaType: .video, preferredTrackID: kCMPersistentTrackID_Invalid) else {
            throw EditorError.logged(.encodeFailed, "video track")
        }
        let videoRange = try await videoTrack.load(.timeRange)
        try target.insertTimeRange(videoRange, of: videoTrack, at: .zero)
        if hasSound {
            let audioAsset = AVURLAsset(url: audioURL)
            if let audioTrack = try await audioAsset.loadTracks(withMediaType: .audio).first,
               let audioTarget = combined.addMutableTrack(withMediaType: .audio, preferredTrackID: kCMPersistentTrackID_Invalid) {
                let audioRange = try await audioTrack.load(.timeRange)
                try audioTarget.insertTimeRange(CMTimeRange(start: .zero, duration: min(audioRange.duration, videoRange.duration)), of: audioTrack, at: .zero)
            }
        }
        guard let session = AVAssetExportSession(asset: combined, presetName: AVAssetExportPresetPassthrough) else {
            throw EditorError.logged(.unsupportedOperation, "mux session")
        }
        session.shouldOptimizeForNetworkUse = true
        try await run(session, to: output, type: .mp4)
    }

    private static func run(_ session: AVAssetExportSession, to url: URL, type: AVFileType) async throws {
        try? FileManager.default.removeItem(at: url)
        session.outputURL = url
        session.outputFileType = type
        let box = ExportSessionBox(session: session)
        await withTaskCancellationHandler {
            await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
                box.session.exportAsynchronously { continuation.resume() }
            }
        } onCancel: {
            box.session.cancelExport()
        }
        if Task.isCancelled || session.status == .cancelled { throw CancellationError() }
        guard session.status == .completed else { throw ExportErrors.map(session.error, context: "audio export") }
    }

    private final class ExportSessionBox: @unchecked Sendable {
        let session: AVAssetExportSession
        init(session: AVAssetExportSession) { self.session = session }
    }
}
