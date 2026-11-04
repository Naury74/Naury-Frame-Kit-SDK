import AVFoundation
import CoreGraphics
import Foundation
import ImageIO
import Testing
import UniformTypeIdentifiers
@testable import FrameKit

func solid(_ argb: UInt32, width: Int = 64, height: Int = 48) -> CGImage {
    let context = BitmapContext.make(width: width, height: height)!
    context.setFillColor(CGColor.argb(argb))
    context.fill(CGRect(x: 0, y: 0, width: width, height: height))
    return context.makeImage()!
}

func stillFile(_ argb: UInt32, width: Int = 64, height: Int = 48) -> URL {
    let url = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + ".png")
    let destination = CGImageDestinationCreateWithURL(url as CFURL, UTType.png.identifier as CFString, 1, nil)!
    CGImageDestinationAddImage(destination, solid(argb, width: width, height: height), nil)
    #expect(CGImageDestinationFinalize(destination))
    return url
}

private func gifFile(_ colors: [UInt32], durations: [Int64], width: Int = 40, height: Int = 30) throws -> URL {
    let url = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + ".gif")
    let encoder = try GifEncoder(url: url, width: width, height: height, loopCount: 0)
    for (color, duration) in zip(colors, durations) { try encoder.addFrame(solid(color, width: width, height: height), durationMs: duration) }
    try encoder.finish()
    return url
}

private func slideshow(_ count: Int, slideMs: Int64 = 1000) -> (MotionProject, MotionMedia) {
    var project = MotionProject(kind: .slideshow, slides: Array(0..<count))
    project.slideDurationMs = slideMs
    project.cameraMotion = .none
    let colors: [UInt32] = [0xFFFF_0000, 0xFF00_00FF, 0xFF00_FF00]
    let images = (0..<count).map { solid(colors[$0 % colors.count]) }
    let media = MotionMedia(kind: .slideshow, aspects: Array(repeating: 64.0 / 48, count: count), animation: nil) { images[$0] }
    return (project, media)
}

private func animation(_ durations: [Int64]) -> (MotionProject, MotionMedia) {
    let frames = durations.indices.map { solid($0 % 2 == 0 ? 0xFFFF_0000 : 0xFF00_00FF, width: 40, height: 30) }
    let animated = AnimatedFrames(frames: frames, durationsMs: durations, width: 40, height: 30)
    return (MotionProject(kind: .animation, slides: [0]), MotionMedia(kind: .animation, aspects: [40.0 / 30], animation: animated) { _ in nil })
}

struct MotionTimelineTests {
    @Test func slideshowDurationAndBoomerang() {
        var (project, media) = slideshow(3)
        #expect(MotionTimeline.durationMs(project, media) == 3000)
        project.loop = .boomerang
        #expect(MotionTimeline.durationMs(project, media) == 6000)
        #expect(MotionTimeline.baseTime(project, media, 4000) == 2000)
        #expect(MotionTimeline.slideAt(project, media, 5500).index == 0)
    }

    @Test func transitionPlaysAtStartOfIncomingSlide() {
        var (project, media) = slideshow(2)
        project.transitionMs = 400
        let start = MotionTimeline.slideAt(project, media, 1100)
        #expect(start.index == 1)
        #expect(abs((start.transition ?? -1) - 0.25) < 1e-9)
        #expect(MotionTimeline.slideAt(project, media, 1500).transition == nil)
        #expect(MotionTimeline.slideAt(project, media, 100).transition == nil)
        // 사진 길이의 절반을 넘지 않는다.
        project.transitionMs = 2000
        #expect(abs((MotionTimeline.slideAt(project, media, 1250).transition ?? -1) - 0.5) < 1e-9)
    }

    @Test func animationFrameFollowsTrimAndSpeed() {
        var (project, media) = animation([100, 100, 100, 100])
        #expect(MotionTimeline.durationMs(project, media) == 400)
        #expect(MotionTimeline.animationFrame(project, media, 150) == 1)
        project.trimStartMs = 100
        project.trimEndMs = 300
        project.speed = 2
        #expect(MotionTimeline.durationMs(project, media) == 100)
        #expect(MotionTimeline.animationFrame(project, media, 0) == 1)
        #expect(MotionTimeline.animationFrame(project, media, 60) == 2)
    }

    @Test func staticSlidesMergeIntoOneFramePerSlide() {
        let (project, media) = slideshow(2)
        var still = project
        still.transition = .none
        let frames = MotionTimeline.outputFrames(still, media, fps: 10)
        #expect(frames.count == 2)
        #expect(frames.map(\.durationMs) == [1000, 1000])
        // 반복해서 움직이는 요소가 있으면 합치지 않는다.
        still.overlays = [MotionOverlay(overlay: Overlay(id: "s", content: .sticker(assetId: "x", widthRatio: 0.2)), animation: .pulse)]
        #expect(MotionTimeline.outputFrames(still, media, fps: 10).count == 20)
    }

    @Test func outputSizeIsEvenAndCappedForAnimation() {
        var (project, media) = animation([100])
        #expect(MotionTimeline.outputSize(project, media, maxLongEdge: 720) == CGSize(width: 40, height: 30))
        project.aspect = .story916
        let size = MotionTimeline.outputSize(project, media, maxLongEdge: 721)
        #expect(Int(size.width) % 2 == 0 && Int(size.height) % 2 == 0)
        #expect(size.height == 720)
    }

    @Test func frameRateNeverExceedsSource() {
        let (project, media) = animation([100, 100])
        #expect(MotionTimeline.frameRate(project, media, base: 30) == 10)
        #expect(MotionTimeline.frameRate(project, media, base: 6) == 6)
    }
}

struct MotionCodecTests {
    @Test func gifRoundTripKeepsFramesDelaysAndColors() throws {
        let url = try gifFile([0xFFFF_0000, 0xFF00_00FF, 0xFF00_FF00], durations: [100, 250, 75])
        let source = try MotionSource(url: url, removeOnClose: true)
        defer { source.close() }
        #expect(source.isAnimated)
        let (frames, trimmed) = try source.decodeAnimation(maxLongEdge: 720, maxFrames: 10, maxBytes: 10_000_000)
        #expect(!trimmed)
        #expect(frames.frames.count == 3)
        #expect(frames.durationsMs == [100, 250, 80])
        let p = pixel(frames.frames[1], x: 5, y: 5)
        #expect(p.b > 240 && p.r < 15 && p.g < 15)
    }

    @Test func gifDelayCarryKeepsTotalLength() throws {
        let url = try gifFile(Array(repeating: 0xFF80_8080, count: 6), durations: Array(repeating: 33, count: 6))
        let source = try MotionSource(url: url, removeOnClose: true)
        defer { source.close() }
        let (frames, _) = try source.decodeAnimation(maxLongEdge: 720, maxFrames: 10, maxBytes: 10_000_000)
        #expect(frames.totalMs == 200)
    }

    @Test func gifKeepsTransparency() throws {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + ".gif")
        let encoder = try GifEncoder(url: url, width: 20, height: 20, loopCount: 3)
        try encoder.addFrame(solid(0x0000_0000, width: 20, height: 20), durationMs: 100)
        try encoder.addFrame(solid(0xFFFF_FFFF, width: 20, height: 20), durationMs: 100)
        try encoder.finish()
        let source = CGImageSourceCreateWithURL(url as CFURL, nil)!
        let first = CGImageSourceCreateImageAtIndex(source, 0, nil)!
        #expect([.premultipliedLast, .premultipliedFirst, .last, .first].contains(first.alphaInfo))
        let props = CGImageSourceCopyProperties(source, nil) as? [CFString: Any]
        let gif = props?[kCGImagePropertyGIFDictionary] as? [CFString: Any]
        // ImageIO는 반복 횟수 대신 재생 횟수(반복 + 1)를 보고한다.
        #expect(gif?[kCGImagePropertyGIFLoopCount] as? Int == 4)
    }

    @Test func manyColorsQuantizeCloseToSource() throws {
        let context = BitmapContext.make(width: 64, height: 64)!
        for y in 0..<64 {
            for x in 0..<64 {
                context.setFillColor(CGColor(srgbRed: CGFloat(x) / 63, green: CGFloat(y) / 63, blue: 0.5, alpha: 1))
                context.fill(CGRect(x: x, y: y, width: 1, height: 1))
            }
        }
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + ".gif")
        let encoder = try GifEncoder(url: url, width: 64, height: 64, loopCount: 0)
        try encoder.addFrame(context.makeImage()!, durationMs: 100)
        try encoder.finish()
        let decoded = CGImageSourceCreateImageAtIndex(CGImageSourceCreateWithURL(url as CFURL, nil)!, 0, nil)!
        let p = pixel(decoded, x: 32, y: 32)
        #expect(abs(p.r - 130) < 24 && abs(p.b - 128) < 24)
    }

    @Test func framesBeyondLimitAreDropped() throws {
        let url = try gifFile(Array(repeating: 0xFFFF_0000, count: 5), durations: Array(repeating: 100, count: 5))
        let source = try MotionSource(url: url, removeOnClose: true)
        defer { source.close() }
        let (frames, trimmed) = try source.decodeAnimation(maxLongEdge: 720, maxFrames: 2, maxBytes: 10_000_000)
        #expect(trimmed && frames.frames.count == 2)
    }

    @Test func stillImageIsNotAnimated() throws {
        let source = try MotionSource(url: stillFile(0xFFFF_0000), removeOnClose: true)
        defer { source.close() }
        #expect(!source.isAnimated)
        #expect(abs(source.aspect - 64.0 / 48) < 1e-9)
        #expect(source.decodeStill(maxLongEdge: 32)?.width == 32)
    }
}

struct MotionRenderTests {
    @Test func fadeBlendsPreviousAndNextSlide() {
        var (project, media) = slideshow(2)
        project.transition = .fade
        project.transitionMs = 400
        let image = MotionRenderer.render(project, media: media, timeMs: 1200, size: CGSize(width: 64, height: 48), colorSpec: nil)!
        let p = pixel(image, x: 32, y: 24)
        #expect(p.r > 60 && p.b > 60)
    }

    @Test func fitLeavesBackgroundColor() {
        var (project, media) = slideshow(1)
        project.fit = .fit
        project.backgroundArgb = 0xFF00_FF00
        let image = MotionRenderer.render(project, media: media, timeMs: 0, size: CGSize(width: 64, height: 128), colorSpec: nil)!
        let top = pixel(image, x: 32, y: 2)
        #expect(top.g > 240 && top.r < 15)
        let middle = pixel(image, x: 32, y: 64)
        #expect(middle.r > 240)
    }

    @Test func popStartsSmallAndSettles() {
        let item = MotionOverlay(overlay: Overlay(id: "t", content: .sticker(assetId: "x", widthRatio: 0.2)), animation: .pop)
        #expect(MotionRenderer.animate(item, timeMs: 0).transform.opacity == 0)
        #expect(abs(MotionRenderer.animate(item, timeMs: 1000).transform.scale - 1) < 1e-9)
    }
}

struct MotionExportTests {
    @Test func slideshowExportsMp4WithSound() async throws {
        let sources = try [MotionSource(url: stillFile(0xFFFF_0000), removeOnClose: true), MotionSource(url: stillFile(0xFF00_00FF), removeOnClose: true)]
        defer { sources.forEach { $0.close() } }
        let cache = SlideCache(sources: sources, longEdge: 540, capacity: 3)
        let media = MotionMedia(kind: .slideshow, aspects: sources.map(\.aspect), animation: nil) { cache.image($0) }
        var project = MotionProject(kind: .slideshow, slides: [0, 1])
        project.slideDurationMs = 1000
        let music = try await AudioSource.open(url: try makeTestAudio(), name: "tone")
        project.audio = [AudioClipState(id: "m", source: 0, sourceRange: TimeRangeUs(start: 0, end: music.duration), timelineStart: 0, loop: true)]
        let output = try await MotionExporter.export(project, media: media, audio: [music],
                                                     settings: MotionExportSettings(format: .mp4, longEdge: 540, frameRate: 30, loopCount: 0)) { _ in }
        defer { FrameKitOutputs.delete(output.url) }
        #expect(output.mediaType == .video && output.width == 540 && output.height == 404)
        let asset = AVURLAsset(url: output.url)
        let length = try await asset.load(.duration).seconds
        #expect(abs(length - 2) < 0.15)
        #expect(try await !asset.loadTracks(withMediaType: .audio).isEmpty)
    }

    @Test func animationExportsGif() async throws {
        let (project, media) = animation([100, 100, 100])
        let output = try await MotionExporter.export(project, media: media, audio: [],
                                                     settings: MotionExportSettings(format: .gif, longEdge: 240, frameRate: 15, loopCount: 0)) { _ in }
        defer { FrameKitOutputs.delete(output.url) }
        #expect(output.mediaType == .animatedImage && output.mimeType == "image/gif")
        #expect(output.durationMs == 300)
        let source = CGImageSourceCreateWithURL(output.url as CFURL, nil)!
        #expect(CGImageSourceGetCount(source) == 3)
    }

    @Test func cancelledExportLeavesNoFile() async throws {
        let (project, media) = slideshow(3)
        let task = Task {
            try await MotionExporter.export(project, media: media, audio: [],
                                            settings: MotionExportSettings(format: .gif, longEdge: 480, frameRate: 30, loopCount: 0)) { _ in }
        }
        task.cancel()
        await #expect(throws: CancellationError.self) { try await task.value }
    }
}

struct MotionRequestTests {
    @Test func rejectsCaptureAndMissingFormat() {
        #expect(!MotionEditorRequest(input: .capture).validate().isEmpty)
        #expect(MotionEditorRequest(input: .pick).validate().isEmpty)
        let export = MotionExportConfig(formats: [.gif], format: .mp4)
        #expect(MotionEditorRequest(input: .pick, export: export).validate().contains { $0.path == "export.format" })
        // 편집기 상한(기본 20)보다 많이 고르게 해도 상한까지만 고르게 하고 열린다. 범위는 1...100.
        #expect(MotionEditorRequest(input: .pickMultiple(maxItems: 30)).validate().isEmpty)
        #expect(!MotionEditorRequest(input: .pickMultiple(maxItems: 101)).validate().isEmpty)
    }
}

@MainActor
struct InputLimitTests {
    @Test func opensOnlyUpToEditorLimit() async throws {
        let urls = [stillFile(0xFFFF_0000), stillFile(0xFF00_FF00), stillFile(0xFF00_00FF), stillFile(0xFFFF_FFFF)]
        let image = ImageEditorRequest(input: .files(urls), config: ImageEditorConfig(maxImageCount: 2))
        #expect(image.validate().isEmpty)
        let model = EditorModel(request: image) { _ in }
        model.start()
        for _ in 0..<150 where model.phase != .ready { try await Task.sleep(nanoseconds: 20_000_000) }
        #expect(model.phase == .ready)
        #expect(model.pages.count == 2)
        #expect(VideoEditorRequest(input: .pickMultiple(maxItems: 50)).validate().isEmpty)
        #expect(!VideoEditorRequest(input: .pickMultiple(maxItems: 0)).validate().isEmpty)
        #expect(!AudioEditorRequest(input: .files([])).validate().isEmpty)
    }
}

@MainActor
struct MotionEditorModelTests {
    @Test func textToolKeepsTypedText() async throws {
        let model = MotionEditorModel(request: MotionEditorRequest(input: .files([stillFile(0xFFFF_0000), stillFile(0xFF00_00FF)]))) { _ in }
        model.start()
        for _ in 0..<100 where model.phase != .ready { try await Task.sleep(nanoseconds: 20_000_000) }
        #expect(model.phase == .ready)
        model.selectTool(.text)
        #expect(model.editingText?.text == "")
        model.setText("Hello")
        #expect(model.editingText?.text == "Hello")
        model.applyTool()
        #expect(model.history.current.overlays.count == 1)
        model.finish(.cancelled)
    }
}
