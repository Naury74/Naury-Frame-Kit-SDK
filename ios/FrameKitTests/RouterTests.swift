import AVFoundation
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
        #expect(try await SourceInspector.kind(imageFile()) == .image)
        #expect(try await SourceInspector.kind(imageFile(frames: 3, ext: "gif")) == .animated)
        #expect(try await SourceInspector.kind(try await makeTestVideo(seconds: 1)) == .video)
        #expect(try await SourceInspector.kind(try makeTestAudio()) == .audio)
    }

    @Test func unreadableSourcesMapToCodes() async throws {
        func code(_ url: URL) async -> EditorErrorCode? {
            do throws(EditorError) { _ = try await SourceInspector.kind(url); return nil } catch { return error.code }
        }
        let dir = FileManager.default.temporaryDirectory
        #expect(await code(dir.appendingPathComponent("missing-\(UUID().uuidString).png")) == .sourceUnavailable)
        let empty = dir.appendingPathComponent("empty-\(UUID().uuidString).png")
        try Data().write(to: empty)
        #expect(await code(empty) == .decodeFailed)
        let junk = dir.appendingPathComponent("junk-\(UUID().uuidString).bin")
        try Data(repeating: 7, count: 64).write(to: junk)
        #expect(await code(junk) == .unsupportedFormat)
        [empty, junk].forEach { try? FileManager.default.removeItem(at: $0) }
    }

    @Test func temporaryCopyReportsMissingAndEmptyFiles() throws {
        let dir = FileManager.default.temporaryDirectory
        #expect(throws: EditorError.self) { try OutputStore.copyToTemporary(dir.appendingPathComponent("none.png"), fallbackExtension: "img") }
        do throws(EditorError) {
            _ = try OutputStore.copyToTemporary(dir.appendingPathComponent("none.png"), fallbackExtension: "img")
        } catch {
            #expect(error.code == .sourceUnavailable)
        }
        let source = imageFile()
        let copy = try OutputStore.copyToTemporary(source, fallbackExtension: "img")
        #expect(FileManager.default.fileExists(atPath: copy.path))
        OutputStore.discardTemporary(copy)
        #expect(!FileManager.default.fileExists(atPath: copy.path))
        // 임시 폴더 밖 파일은 지우지 않는다.
        OutputStore.discardTemporary(source)
        #expect(FileManager.default.fileExists(atPath: source.path))
    }

    @Test func exportErrorsMapToRecoverableCodes() {
        #expect(ExportErrors.code(NSError(domain: AVFoundationErrorDomain, code: AVError.Code.diskFull.rawValue)) == .insufficientStorage)
        #expect(ExportErrors.code(NSError(domain: AVFoundationErrorDomain, code: AVError.Code.operationInterrupted.rawValue)) == .outputWriteFailed)
        #expect(ExportErrors.code(NSError(domain: NSPOSIXErrorDomain, code: Int(ENOSPC))) == .insufficientStorage)
        let wrapped = NSError(domain: AVFoundationErrorDomain, code: AVError.Code.unknown.rawValue,
                              userInfo: [NSUnderlyingErrorKey: NSError(domain: NSPOSIXErrorDomain, code: Int(ENOSPC))])
        #expect(ExportErrors.code(wrapped) == .insufficientStorage)
        #expect(ExportErrors.code(NSError(domain: AVFoundationErrorDomain, code: AVError.Code.unknown.rawValue)) == .encodeFailed)
        #expect(EditorErrorCode.outputWriteFailed.defaultRecoverable && EditorErrorCode.outputWriteFailed.defaultAction == .retry)
        #expect(EditorError.deviceAccessDenied.suggestedAction == .openSettings)
    }

    @Test func chooserActionsFollowAndroidRules() {
        let all = Set(EditorAction.allCases)
        #expect(SourceInspector.actions([.image, .image], allowed: all).actions == [.editPhoto, .makePDF, .motion, .sendOriginal])
        #expect(SourceInspector.actions([.animated], allowed: all).actions == [.motion, .editPhoto, .sendOriginal])
        #expect(SourceInspector.actions([.video], allowed: all).actions == [.editVideo, .sendOriginal])
        let mixed = SourceInspector.actions([.image, .video], allowed: all)
        #expect(mixed.mixed && mixed.actions == [.editEach, .sendOriginal])
        #expect(SourceInspector.actions([.image, .video], allowed: all, mixedEdit: false).actions == [.sendOriginal])
        #expect(SourceInspector.actions([.image, .audio], allowed: all).actions == [.sendOriginal])
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

    @Test func mixedRejectFailsAndSendsOriginals() async throws {
        let video = try await makeTestVideo(seconds: 1)
        let (_, auto) = try await run(FrameKitRequest(input: .files([imageFile(), video]), mixedSelection: .reject))
        if case .failure(let error) = auto() { #expect(error.code == .unsupportedOperation) } else { Issue.record("expected failure") }
        // 할 일이 원본 보내기 하나라 바로 보낸다.
        let (_, result) = try await run(FrameKitRequest(input: .files([imageFile(), video]), chooser: .ask, mixedSelection: .reject))
        for _ in 0..<100 where result() == nil { try await Task.sleep(for: .milliseconds(20)) }
        guard case .success(_, let outputs) = result() else { Issue.record("expected originals"); return }
        #expect(outputs.count == 2 && outputs.allSatisfy(\.isOriginal))
        #expect(outputs[0].mediaType == .image && outputs[1].mediaType == .video && outputs[1].width == 320)
        outputs.forEach { FrameKitOutputs.delete($0.url) }
    }

    @Test func mixedEditsEachAndSavesInOrder() async throws {
        let video = try await makeTestVideo(seconds: 1)
        let (router, result) = try await run(FrameKitRequest(input: .files([imageFile(), video, imageFile()])))
        guard case .editor(.mixed(let mixed)) = router.phase else { Issue.record("no mixed editor"); return }
        #expect(mixed.items.map(\.kind.mediaKind) == [.image, .video, .image])
        for _ in 0..<200 {
            let ready = mixed.items.allSatisfy { item in
                switch item.editor {
                case .image(let model): model.phase == .ready
                case .video(let model): model.phase == .ready
                default: false
                }
            }
            if ready { break }
            try await Task.sleep(for: .milliseconds(20))
        }
        mixed.select(1)
        #expect(mixed.selected == 1)
        mixed.saveAll()
        for _ in 0..<500 where result() == nil { try await Task.sleep(for: .milliseconds(20)) }
        guard case .success(let first, let outputs) = result() else { Issue.record("expected outputs \(String(describing: result()))"); return }
        #expect(outputs.map(\.mediaType) == [.image, .video, .image])
        #expect(first.url == outputs[0].url && !outputs.contains(where: \.isOriginal))
        outputs.forEach { FrameKitOutputs.delete($0.url) }
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

    @Test func pickerFailuresShowErrorOrNotice() async throws {
        var result: FrameKitResult?
        let model = RouterModel(request: FrameKitRequest(input: .pick)) { result = $0 }
        model.start()
        guard case .picking = model.phase else { Issue.record("not picking"); return }
        model.picked(PickedFiles(errors: [EditorError(code: .sourceUnavailable)]))
        guard case .failed(let error) = model.phase else { Issue.record("no failure screen"); return }
        #expect(error.code == .sourceUnavailable && model.canChooseAnother && result == nil)
        model.chooseAnother()
        guard case .picking = model.phase else { Issue.record("not picking again"); return }
        model.picked(PickedFiles(urls: [imageFile()], errors: [EditorError(code: .sourceUnavailable)]))
        #expect(model.notice == "add_failed")
        for _ in 0..<150 {
            if case .editor = model.phase { break }
            try await Task.sleep(nanoseconds: 20_000_000)
        }
        guard case .editor(.image) = model.phase else { Issue.record("no editor"); return }
        model.dismissedWithoutResult()
        if case .cancelled = result {} else { Issue.record("expected cancelled") }
    }

    @Test func mixedTruncationShowsNotice() async throws {
        let video = try await makeTestVideo(seconds: 1)
        let urls = [video] + (0..<MixedEditorModel.maxItems).map { _ in imageFile() }
        let (router, _) = try await run(FrameKitRequest(input: .files(urls)))
        guard case .editor(.mixed(let mixed)) = router.phase else { Issue.record("no mixed editor"); return }
        #expect(mixed.items.count == MixedEditorModel.maxItems && router.notice == "items_truncated")
        router.dismissedWithoutResult()
    }

    @Test func mixedRemovesFailedItemAndDeletesSavedOnDismiss() async throws {
        let video = try await makeTestVideo(seconds: 1)
        let broken = FileManager.default.temporaryDirectory.appendingPathComponent("broken-\(UUID().uuidString).png")
        try Data(repeating: 1, count: 32).write(to: broken)
        var result: FrameKitResult?
        let mixed = MixedEditorModel(request: FrameKitRequest(input: .pick), urls: [imageFile(), broken, video],
                                     kinds: [.image, .image, .video]) { result = $0 }
        for _ in 0..<200 {
            if case .image(let model) = mixed.items[1].editor, case .failed = model.phase { break }
            try await Task.sleep(nanoseconds: 20_000_000)
        }
        guard case .image(let failed) = mixed.items[1].editor, case .failed(let error) = failed.phase else { Issue.record("item did not fail"); return }
        // 열기 실패 화면의 닫기는 그 항목만 뺀다.
        failed.finish(.failure(error))
        #expect(mixed.items.count == 2 && result == nil)
        // 저장해 둔 파일은 호스트가 닫으면 지운다.
        let (slot, partial) = try OutputStore.reserve(extension: "png")
        try FileManager.default.copyItem(at: imageFile(), to: partial)
        let saved = try OutputStore.publish(partial: partial, to: slot)
        mixed.items[0].outputs = [EditedMedia(url: saved, mediaType: .image, width: 1, height: 1, mimeType: "image/png", fileSize: 1,
                                              warnings: [], durationMs: nil, pageCount: nil, recognizedText: nil, isOriginal: false)]
        mixed.finish(.cancelled)
        #expect(!FileManager.default.fileExists(atPath: saved.path))
        if case .cancelled = result {} else { Issue.record("expected cancelled") }
    }
}
