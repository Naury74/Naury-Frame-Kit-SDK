import Foundation
import Testing
@testable import FrameKit

private func waitFor(_ condition: @MainActor () -> Bool) async throws {
    for _ in 0..<250 where await !condition() { try await Task.sleep(nanoseconds: 20_000_000) }
}

/// 결과를 모으고, 결과가 나면 화면처럼 연결을 끊는다.
@MainActor
private final class Sink {
    var results: [FrameKitResult] = []
    var cancelled: Int { results.filter { if case .cancelled = $0 { true } else { false } }.count }
    var succeeded: Int { results.filter { if case .success = $0 { true } else { false } }.count }
}

@MainActor
private func imageEditor(_ control: FrameKitEditorControl, _ sink: Sink, files: Int = 1) async throws -> EditorModel {
    var model: EditorModel?
    let request = ImageEditorRequest(input: .files((0..<files).map { _ in stillFile(0xFFFF_0000) }))
    model = EditorModel(request: request) { result in
        sink.results.append(result)
        if let model { control.detach(model) }
    }
    model!.start()
    try await waitFor { model!.phase == .ready }
    control.attach(model!)
    return model!
}

@MainActor
struct HostCloseTests {
    @Test func requestBeforeAttachDoesNothing() async throws {
        let control = FrameKitEditorControl()
        #expect(!control.canDismissInteractively)
        control.requestClose()
        #expect(!control.canDismissInteractively)
    }

    @Test func closesWithoutChangesOnce() async throws {
        let control = FrameKitEditorControl()
        let sink = Sink()
        let model = try await imageEditor(control, sink)
        #expect(control.canDismissInteractively)
        control.requestClose()
        control.requestClose()
        #expect(sink.results.count == 1 && sink.cancelled == 1)
        #expect(!control.canDismissInteractively)
        model.dismissedWithoutResult()
        #expect(sink.results.count == 1)
    }

    @Test func changesAskForConfirmation() async throws {
        let control = FrameKitEditorControl()
        let sink = Sink()
        let model = try await imageEditor(control, sink)
        var edit = model.history.current
        edit.filterId = "mono"
        edit.filterIntensity = 1
        model.history.commit(edit)
        control.requestClose()
        #expect(model.showDiscardDialog && sink.results.isEmpty)
        #expect(!control.canDismissInteractively)
        // 대화상자가 떠 있으면 더 받지 않는다.
        control.requestClose()
        #expect(sink.results.isEmpty)
        // 계속 편집을 고르면 다시 닫을 수 있다.
        model.showDiscardDialog = false
        try await waitFor { control.canDismissInteractively }
        #expect(control.canDismissInteractively)
        control.requestClose()
        model.discardAndClose()
        #expect(sink.results.count == 1 && sink.cancelled == 1)
    }

    @Test func toolDraftCountsAsChangeAndIsNotApplied() async throws {
        let control = FrameKitEditorControl()
        let sink = Sink()
        let model = try await imageEditor(control, sink)
        model.selectTool(.rotate)
        model.working?.geometry.quarterTurns = 1
        control.requestClose()
        #expect(model.showDiscardDialog && sink.results.isEmpty)
        #expect(model.history.current.geometry.quarterTurns == 0)
        model.discardAndClose()
        #expect(sink.cancelled == 1)
    }

    @Test func openToolWithoutChangesCloses() async throws {
        let control = FrameKitEditorControl()
        let sink = Sink()
        let model = try await imageEditor(control, sink)
        model.selectTool(.crop)
        control.requestClose()
        #expect(sink.cancelled == 1 && !model.showDiscardDialog)
    }

    @Test func ignoredWhileSavingAndDeliveredOnce() async throws {
        let control = FrameKitEditorControl()
        let sink = Sink()
        let model = try await imageEditor(control, sink)
        model.save()
        #expect(model.export != nil)
        control.requestClose()
        #expect(!model.showDiscardDialog && sink.results.isEmpty)
        try await waitFor { !control.canDismissInteractively }
        #expect(!control.canDismissInteractively)
        // 저장이 끝나는 사이에도 계속 요청한다.
        for _ in 0..<250 where sink.results.isEmpty {
            control.requestClose()
            try await Task.sleep(nanoseconds: 10_000_000)
        }
        control.requestClose()
        model.dismissedWithoutResult()
        #expect(sink.results.count == 1 && sink.succeeded == 1)
        if case .success(let output, _) = sink.results.first { FrameKitOutputs.delete(output.url) }
    }

    @Test func sheetClosesBeforeConfirmation() async throws {
        let control = FrameKitEditorControl()
        let sink = Sink()
        let model = try await imageEditor(control, sink)
        model.showAddPicker = true
        try await waitFor { !control.canDismissInteractively }
        #expect(!control.canDismissInteractively)
        var edit = model.history.current
        edit.filterId = "mono"
        model.history.commit(edit)
        control.requestClose()
        #expect(!model.showAddPicker && !model.showDiscardDialog && sink.results.isEmpty)
        model.sheetDismissed()
        #expect(model.showDiscardDialog)
    }

    @Test func recordingScreenDiscardsRecording() async throws {
        let sink = Sink()
        let control = FrameKitEditorControl()
        var model: AudioEditorModel?
        model = AudioEditorModel(request: AudioEditorRequest(input: .capture)) { result in
            sink.results.append(result)
            if let model { control.detach(model) }
        }
        let audio = model!
        #expect(audio.phase == .recording)
        control.attach(audio)
        let file = FileManager.default.temporaryDirectory.appendingPathComponent("rec-\(UUID().uuidString).m4a")
        try Data([0]).write(to: file)
        try audio.recorder.simulateRecording(into: file)
        #expect(control.canDismissInteractively)
        control.requestClose()
        #expect(sink.results.count == 1 && sink.cancelled == 1)
        #expect(audio.recorder.state == .idle)
        #expect(!FileManager.default.fileExists(atPath: file.path))
    }

    @Test func recordSheetDiscardsRecordingThenConfirms() async throws {
        let sink = Sink()
        let model = AudioEditorModel(request: AudioEditorRequest(input: .file(try makeTestAudio(seconds: 1)))) { sink.results.append($0) }
        model.start()
        try await waitFor { model.phase == .ready }
        model.seek(to: 500_000)
        model.splitAtPlayhead()
        let clips = model.project.clips.count
        model.openRecordSheet(.insert)
        #expect(model.recordTarget != nil && !model.hostCanDismiss)
        let file = FileManager.default.temporaryDirectory.appendingPathComponent("rec-\(UUID().uuidString).m4a")
        try Data([0]).write(to: file)
        try model.recorder.simulateRecording(into: file)
        model.hostRequestClose()
        #expect(model.recordTarget == nil && model.recorder.state == .idle)
        #expect(!FileManager.default.fileExists(atPath: file.path))
        #expect(model.project.clips.count == clips && sink.results.isEmpty)
        model.sheetDismissed()
        #expect(model.showDiscardDialog)
        model.discardAndClose()
        #expect(sink.results.count == 1 && sink.cancelled == 1)
    }

    @Test func audioIgnoredWhileExporting() async throws {
        let sink = Sink()
        let model = AudioEditorModel(request: AudioEditorRequest(input: .file(try makeTestAudio(seconds: 1)))) { sink.results.append($0) }
        model.start()
        try await waitFor { model.phase == .ready }
        model.save()
        #expect(model.export != nil && !model.hostCanDismiss)
        model.hostRequestClose()
        #expect(!model.showDiscardDialog)
        try await waitFor { !sink.results.isEmpty }
        model.hostRequestClose()
        #expect(sink.results.count == 1 && sink.succeeded == 1)
        if case .success(let output, _) = sink.results.first { FrameKitOutputs.delete(output.url) }
    }

    @Test func videoAndMotionFollowSameRules() async throws {
        let sink = Sink()
        let video = VideoEditorModel(request: VideoEditorRequest(input: .file(try await makeTestVideo()))) { sink.results.append($0) }
        video.start()
        try await waitFor { video.phase == .ready }
        #expect(video.hostCanDismiss)
        video.showMusicPicker = true
        #expect(!video.hostCanDismiss)
        video.hostRequestClose()
        #expect(!video.showMusicPicker && sink.cancelled == 1)

        let motion = MotionEditorModel(request: MotionEditorRequest(input: .files([stillFile(0xFF00_FF00), stillFile(0xFF00_00FF)]))) {
            sink.results.append($0)
        }
        motion.start()
        try await waitFor { motion.phase == .ready }
        #expect(motion.hostCanDismiss)
        motion.showDraftDialog = true
        motion.hostRequestClose()
        #expect(!motion.hostCanDismiss && sink.results.count == 1)
        motion.showDraftDialog = false
        motion.hostRequestClose()
        #expect(sink.results.count == 2 && sink.cancelled == 2)
    }

    @Test func routerDelegatesToActiveEditor() async throws {
        let control = FrameKitEditorControl()
        let sink = Sink()
        var router: RouterModel?
        router = RouterModel(request: FrameKitRequest(input: .files([stillFile(0xFFFF_0000), stillFile(0xFF00_FF00)]), chooser: .ask)) { result in
            sink.results.append(result)
            if let router { control.detach(router) }
        }
        let model = router!
        control.attach(model)
        model.start()
        try await waitFor { if case .chooser = model.phase { true } else { false } }
        try await waitFor { control.canDismissInteractively }
        #expect(control.canDismissInteractively)
        model.choose(.editPhoto)
        guard case .editor(.image(let editor)) = model.phase else { Issue.record("no editor"); return }
        #expect(!model.hostCanDismiss)
        editor.start()
        try await waitFor { editor.phase == .ready && control.canDismissInteractively }
        var edit = editor.history.current
        edit.filterId = "mono"
        editor.history.commit(edit)
        control.requestClose()
        #expect(editor.showDiscardDialog && sink.results.isEmpty)
        try await waitFor { !control.canDismissInteractively }
        #expect(!control.canDismissInteractively)
        editor.discardAndClose()
        #expect(sink.results.count == 1 && sink.cancelled == 1)
        control.requestClose()
        #expect(sink.results.count == 1 && !control.canDismissInteractively)
    }

    @Test func routerChooserCancels() async throws {
        let sink = Sink()
        let model = RouterModel(request: FrameKitRequest(input: .files([stillFile(0xFFFF_0000), stillFile(0xFF00_FF00)]), chooser: .ask)) {
            sink.results.append($0)
        }
        model.start()
        try await waitFor { if case .chooser = model.phase { true } else { false } }
        model.hostRequestClose()
        model.hostRequestClose()
        #expect(sink.results.count == 1 && sink.cancelled == 1)
    }

    @Test func detachFromOldEditorKeepsNewOne() async throws {
        let control = FrameKitEditorControl()
        let sink = Sink()
        let first = try await imageEditor(control, sink)
        let second = try await imageEditor(control, sink)
        control.detach(first)
        #expect(control.canDismissInteractively)
        control.requestClose()
        #expect(second.isFinished && !first.isFinished)
        first.finish(.cancelled)
    }
}
