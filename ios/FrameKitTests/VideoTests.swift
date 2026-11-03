import AVFoundation
import CoreGraphics
import Foundation
import Testing
@testable import FrameKit

/// 왼쪽 절반 빨강, 오른쪽 절반 파랑인 시험 영상.
func makeTestVideo(seconds: Double = 2, width: Int = 320, height: Int = 240, fps: Int32 = 30) async throws -> URL {
    let url = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + ".mp4")
    let writer = try AVAssetWriter(outputURL: url, fileType: .mp4)
    let input = AVAssetWriterInput(mediaType: .video, outputSettings: [
        AVVideoCodecKey: AVVideoCodecType.h264, AVVideoWidthKey: width, AVVideoHeightKey: height,
    ])
    let adaptor = AVAssetWriterInputPixelBufferAdaptor(assetWriterInput: input, sourcePixelBufferAttributes: [
        kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA, kCVPixelBufferWidthKey as String: width,
        kCVPixelBufferHeightKey as String: height,
    ])
    writer.add(input)
    writer.startWriting()
    writer.startSession(atSourceTime: .zero)
    let frames = Int(seconds * Double(fps))
    for i in 0..<frames {
        while !input.isReadyForMoreMediaData { try await Task.sleep(for: .milliseconds(5)) }
        var buffer: CVPixelBuffer?
        CVPixelBufferPoolCreatePixelBuffer(nil, adaptor.pixelBufferPool!, &buffer)
        let pb = buffer!
        CVPixelBufferLockBaseAddress(pb, [])
        let row = CVPixelBufferGetBytesPerRow(pb)
        let base = CVPixelBufferGetBaseAddress(pb)!.bindMemory(to: UInt8.self, capacity: row * height)
        for y in 0..<height {
            for x in 0..<width {
                let o = y * row + x * 4
                let red = x < width / 2
                base[o] = red ? 0 : 255; base[o + 1] = 0; base[o + 2] = red ? 255 : 0; base[o + 3] = 255
            }
        }
        CVPixelBufferUnlockBaseAddress(pb, [])
        adaptor.append(pb, withPresentationTime: CMTime(value: CMTimeValue(i), timescale: fps))
    }
    input.markAsFinished()
    await writer.finishWriting()
    return url
}

/// 440Hz 사인파 소리 파일.
func makeTestAudio(seconds: Double = 1) throws -> URL {
    let url = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + ".m4a")
    let format = AVAudioFormat(standardFormatWithSampleRate: 44_100, channels: 1)!
    let file = try AVAudioFile(forWriting: url, settings: [AVFormatIDKey: kAudioFormatMPEG4AAC, AVSampleRateKey: 44_100, AVNumberOfChannelsKey: 1])
    let frames = AVAudioFrameCount(seconds * 44_100)
    let buffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: frames)!
    buffer.frameLength = frames
    for i in 0..<Int(frames) { buffer.floatChannelData![0][i] = Float(sin(Double(i) * 2 * .pi * 440 / 44_100) * 0.5) }
    try file.write(from: buffer)
    return url
}

private func frame(of url: URL, at seconds: Double) async throws -> CGImage {
    let generator = AVAssetImageGenerator(asset: AVURLAsset(url: url))
    generator.appliesPreferredTrackTransform = true
    generator.requestedTimeToleranceBefore = .zero
    generator.requestedTimeToleranceAfter = .zero
    return try await generator.image(at: CMTime(seconds: seconds, preferredTimescale: 600)).image
}

struct VideoEngineTests {
    @Test func timelineMath() {
        let clip = VideoClipState(id: "a", source: 0, sourceRange: TimeRangeUs(start: 0, end: 2_000_000), speed: 2)
        var edit = VideoEdit(clips: [clip, VideoClipState(id: "b", source: 0, sourceRange: TimeRangeUs(start: 500_000, end: 1_500_000))])
        #expect(edit.duration == 2_000_000)
        #expect(edit.locate(1_500_000)?.index == 1 && edit.locate(1_500_000)?.sourceTime == 1_000_000)
        #expect(edit.locate(500_000)?.sourceTime == 1_000_000)
        let music = AudioClipState(id: "m", source: 0, sourceRange: TimeRangeUs(start: 0, end: 700_000), timelineStart: 200_000, loop: true)
        let segments = music.segments(videoDuration: edit.duration)
        #expect(segments.count == 3 && segments.last!.source.duration == 400_000)
        edit.overlays = [TimedOverlay(overlay: Overlay(id: "t", content: .text("x", TextStyle())), range: TimeRangeUs(start: 1_500_000, end: 3_000_000))]
        edit.clampTimed()
        #expect(edit.overlays.first?.range.end == 2_000_000)
    }

    @Test func exportsTwoClipsWithSpeedCanvasAndMusic() async throws {
        let source = try await VideoSource.open(url: try await makeTestVideo(), removeOnClose: true)
        let music = try await AudioSource.open(url: try makeTestAudio(), name: "tone")
        defer { source.close() }
        var second = VideoClipState(id: "b", source: 0, sourceRange: TimeRangeUs(start: 0, end: 2_000_000), speed: 2)
        second.effects.geometry.quarterTurns = 2
        var edit = VideoEdit(clips: [VideoClipState(id: "a", source: 0, sourceRange: TimeRangeUs(start: 0, end: 1_000_000)), second])
        edit.canvas = CanvasState(aspectWidth: 1, aspectHeight: 1, fit: .fill)
        edit.audio = [AudioClipState(id: "m", source: 0, sourceRange: TimeRangeUs(start: 0, end: music.duration), timelineStart: 0, loop: true, fadeIn: 300_000)]
        let media = try await VideoExporter.export(edit, sources: [source], audio: [music], config: VideoExportConfig()) { _ in }
        defer { FrameKitOutputs.delete(media.url) }
        #expect(media.mediaType == .video && media.mimeType == "video/mp4")
        #expect(media.width == 240 && media.height == 240)
        #expect(abs((media.durationMs ?? 0) - 2000) < 100)
        let asset = AVURLAsset(url: media.url)
        #expect(try await !asset.loadTracks(withMediaType: .audio).isEmpty)
        // 첫 클립: 왼쪽 빨강. 둘째 클립(180° 회전): 왼쪽 파랑.
        let a = try await frame(of: media.url, at: 0.4)
        let b = try await frame(of: media.url, at: 1.5)
        #expect(pixel(a, x: 20, y: 120).r > 200)
        #expect(pixel(b, x: 20, y: 120).b > 200)
    }
}

struct VideoLayerTests {
    @Test func timedOverlayAndMaskAppearOnlyInRange() async throws {
        let source = try await VideoSource.open(url: try await makeTestVideo(seconds: 2), removeOnClose: true)
        defer { source.close() }
        var edit = VideoEdit(clips: [VideoClipState(id: "a", source: 0, sourceRange: TimeRangeUs(start: 0, end: 2_000_000))])
        edit.overlays = [TimedOverlay(
            overlay: Overlay(id: "box", content: .shape(.rectangle, widthRatio: 0.2, heightRatio: 0.2, ShapeStyle(color: 0xFFFF_FF00, filled: true)),
                             transform: OverlayTransform(center: NPoint(x: 0.75, y: 0.5))),
            range: TimeRangeUs(start: 0, end: 1_000_000))]
        edit.watermark = WatermarkSpec(text: "WM", pattern: .band, position: .bottomCenter)
        let media = try await VideoExporter.export(edit, sources: [source], audio: [], config: VideoExportConfig()) { _ in }
        defer { FrameKitOutputs.delete(media.url) }
        let generator = AVAssetImageGenerator(asset: AVURLAsset(url: media.url))
        generator.requestedTimeToleranceBefore = .zero
        generator.requestedTimeToleranceAfter = .zero
        let early = try await generator.image(at: CMTime(seconds: 0.5, preferredTimescale: 600)).image
        let late = try await generator.image(at: CMTime(seconds: 1.5, preferredTimescale: 600)).image
        let pe = pixel(early, x: early.width * 3 / 4, y: early.height / 2)
        let pl = pixel(late, x: late.width * 3 / 4, y: late.height / 2)
        #expect(pe.r > 200 && pe.g > 200 && pe.b < 80)   // 노란 상자
        #expect(pl.b > 200 && pl.g < 60)                  // 원래 파랑
        // 워터마크 띠는 끝까지 있다(아래쪽이 어두워짐).
        let band = pixel(late, x: late.width / 4, y: late.height - 3)
        let above = pixel(late, x: late.width / 4, y: late.height / 2)
        #expect(band.r + 15 < above.r)
    }
}
