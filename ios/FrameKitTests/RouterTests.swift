import CoreGraphics
import Foundation
import ImageIO
import Testing
import UniformTypeIdentifiers
@testable import FrameKit

private func imageFile(frames: Int = 1, ext: String = "png") -> URL {
    let url = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + "." + ext)
    let type = frames > 1 ? UTType.gif : UTType.png
    let destination = CGImageDestinationCreateWithURL(url as CFURL, type.identifier as CFString, frames, nil)!
    for i in 0..<frames {
        let context = BitmapContext.make(width: 32, height: 24)!
        context.setFillColor(CGColor(gray: CGFloat(i) / CGFloat(max(1, frames)), alpha: 1))
        context.fill(CGRect(x: 0, y: 0, width: 32, height: 24))
        CGImageDestinationAddImage(destination, context.makeImage()!, nil)
    }
    #expect(CGImageDestinationFinalize(destination))
    return url
}

struct RouterRuleTests {
    @Test func detectsKinds() async throws {
        #expect(await SourceInspector.kind(imageFile()) == .image)
        #expect(await SourceInspector.kind(imageFile(frames: 3, ext: "gif")) == .animated)
        #expect(await SourceInspector.kind(try await makeTestVideo(seconds: 1)) == .video)
        #expect(await SourceInspector.kind(try makeTestAudio()) == .audio)
    }

    @Test func chooserActionsFollowAndroidRules() {
        let all = Set(EditorAction.allCases)
        #expect(SourceInspector.actions([.image, .image], allowed: all).actions == [.editPhoto, .makePDF, .motion, .sendOriginal])
        #expect(SourceInspector.actions([.animated], allowed: all).actions == [.motion, .editPhoto, .sendOriginal])
        #expect(SourceInspector.actions([.video], allowed: all).actions == [.editVideo, .sendOriginal])
        let mixed = SourceInspector.actions([.image, .video], allowed: all)
        #expect(mixed.mixed && mixed.actions == [.sendOriginal])
        #expect(SourceInspector.actions([.image], allowed: [.makePDF]).actions == [.makePDF])
    }

    @Test func validatesRequest() {
        #expect(FrameKitRequest(input: .pick).validate().isEmpty)
        #expect(!FrameKitRequest(input: .capture).validate().isEmpty)
        #expect(!FrameKitRequest(input: .pick, chooserActions: []).validate().isEmpty)
        let font = FrameKitRequest(input: .pick, ui: EditorUIConfig(uiFontId: "missing"))
        #expect(font.validate().contains { $0.path.hasSuffix("uiFontId") })
        #expect(!VideoEditorRequest(input: .pick, ui: EditorUIConfig(localeIdentifier: " ")).validate().isEmpty)
    }

    @Test func onPrimaryAndContrastMatchAndroid() {
        #expect(EditorTheme.readableOn(0xFFFF_3B30) == 0xFFFF_FFFF)
        #expect(EditorTheme.readableOn(0xFFFF_CC00) == 0xFF00_0000)
        #expect(EditorPalette().contrastWarnings().isEmpty)
        #expect(EditorPalette(foregroundARGB: 0xFF22_2222).contrastWarnings() .contains("foreground/background"))
    }

    @Test func watermarkNeedsTextWhenAppliedOnOpen() {
        var config = WatermarkConfig(text: "")
        config.applyOnOpen = true
        #expect(config.issues(path: "w").contains { $0.path == "w.text" })
        config.text = "ok"
        config.suggestions = [" "]
        #expect(config.issues(path: "w").contains { $0.path == "w.suggestions" })
    }
}

@MainActor
struct RouterModelTests {
    private func run(_ request: FrameKitRequest) async throws -> (RouterModel, () -> FrameKitResult?) {
        var result: FrameKitResult?
        let model = RouterModel(request: request) { result = $0 }
        model.start()
        for _ in 0..<150 {
            if result != nil { break }
            if case .editor = model.phase { break }
            if case .chooser = model.phase { break }
            try await Task.sleep(for: .milliseconds(20))
        }
        return (model, { result })
    }

    @Test func opensMatchingEditor() async throws {
        let (photo, _) = try await run(FrameKitRequest(input: .files([imageFile(), imageFile()])))
        guard case .editor(.image) = photo.phase else { Issue.record("not image editor"); return }
        photo.dismissedWithoutResult()
        let (gif, _) = try await run(FrameKitRequest(input: .file(imageFile(frames: 3, ext: "gif"))))
        guard case .editor(.motion) = gif.phase else { Issue.record("not motion editor"); return }
        gif.dismissedWithoutResult()
    }

    @Test func mixedAutoFailsAndAskOffersSend() async throws {
        let video = try await makeTestVideo(seconds: 1)
        let (_, auto) = try await run(FrameKitRequest(input: .files([imageFile(), video])))
        if case .failure(let error) = auto() { #expect(error.code == .unsupportedOperation) } else { Issue.record("expected failure") }
        let (ask, result) = try await run(FrameKitRequest(input: .files([imageFile(), video]), chooser: .ask))
        // 할 일이 원본 보내기 하나라 바로 보낸다.
        for _ in 0..<100 where result() == nil { try await Task.sleep(for: .milliseconds(20)) }
        guard case .success(_, let outputs) = result() else { Issue.record("expected originals"); return }
        #expect(outputs.count == 2 && outputs.allSatisfy(\.isOriginal))
        #expect(outputs[0].mediaType == .image && outputs[1].mediaType == .video && outputs[1].width == 320)
        outputs.forEach { FrameKitOutputs.delete($0.url) }
        _ = ask
    }

    @Test func askShowsChooserForPhotos() async throws {
        let (model, _) = try await run(FrameKitRequest(input: .files([imageFile(), imageFile()]), chooser: .ask))
        guard case .chooser(let choice) = model.phase else { Issue.record("no chooser"); return }
        #expect(choice.actions.first == .editPhoto)
        model.choose(.makePDF)
        guard case .editor(.image(let editor)) = model.phase else { Issue.record("no editor"); return }
        #expect(editor.request.export.format == .pdf)
        model.dismissedWithoutResult()
    }
}
