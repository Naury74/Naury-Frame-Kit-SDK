import Foundation
import Testing
@testable import FrameKit

private func sessionRoot() -> URL {
    FileManager.default.temporaryDirectory.appendingPathComponent("sessions-\(UUID().uuidString)", isDirectory: true)
}

private func waitReady(_ ready: @MainActor () -> Bool) async throws {
    for _ in 0..<200 where await !ready() { try await Task.sleep(nanoseconds: 20_000_000) }
}

@MainActor
struct EditSessionTests {
    @Test func photoEditSurvivesEncoding() throws {
        var edit = PhotoEdit(grainSeed: 7)
        edit.adjustments = [.brightness: 0.2, .vignette: 0.5]
        edit.filterId = "mono"
        edit.filterIntensity = 0.8
        edit.geometry.quarterTurns = 1
        edit.overlays = [Overlay(id: "t", content: .text("Hi", TextStyle())), Overlay(id: "s", content: .shape(.arrow, widthRatio: 0.3, heightRatio: 0.1, ShapeStyle()))]
        edit.drawing = [DrawingStroke(id: "d", points: [NPoint(x: 0.1, y: 0.2)], widthRatio: 0.01, color: 0xFF00_00FF, opacity: 1, brush: .pen)]
        edit.privacyMasks = [PrivacyMask(id: "m", shape: .brush([NPoint(x: 0.5, y: 0.5)], widthRatio: 0.05), effect: .mosaic(blockRatio: 0.02))]
        edit.watermark = WatermarkSpec(text: "FrameKit")
        let decoded = try JSONDecoder().decode(PhotoEdit.self, from: JSONEncoder().encode(edit))
        #expect(decoded == edit)
    }

    @Test func savesLoadsAndPrunesMedia() throws {
        let root = sessionRoot()
        let source = stillFile(0xFFFF_0000)
        let input = EditorInput.file(source)
        let first = try #require(EditSession(slot: "image", input: input, root: root))
        let a = first.name(for: stillFile(0xFF00_FF00))
        first.save(VideoSessionState(sources: [a], audio: [], current: VideoEdit(clips: []), baseline: VideoEdit(clips: [])), files: [a])
        let b = first.name(for: stillFile(0xFF00_00FF))
        first.save(VideoSessionState(sources: [b], audio: [], current: VideoEdit(clips: []), baseline: VideoEdit(clips: [])), files: [b])
        first.flush()
        let media = first.directory.appendingPathComponent("media")
        #expect(try FileManager.default.contentsOfDirectory(atPath: media.path) == [b])

        let second = try #require(EditSession(slot: "image", input: input, root: root))
        #expect(second.load(VideoSessionState.self)?.sources == [b])
        let restored = try #require(second.restoreFile(b))
        #expect(FileManager.default.contentsEqual(atPath: restored.path, andPath: media.appendingPathComponent(b).path))
        // 다른 편집기 자리는 따로 둔다.
        #expect(EditSession(slot: "video", input: input, root: root)?.storedKind == nil)

        second.discard()
        second.flush()
        #expect(!FileManager.default.fileExists(atPath: second.directory.path))
    }

    @Test func changedSourceStartsOver() throws {
        let root = sessionRoot()
        let source = stillFile(0xFFFF_0000)
        let first = try #require(EditSession(slot: "image", input: .file(source), root: root))
        first.save(AudioSessionState(sources: [], current: AudioProjectState(), baseline: AudioProjectState()), files: [])
        first.flush()
        try Data(repeating: 1, count: 10).write(to: source)
        let second = try #require(EditSession(slot: "image", input: .file(source), root: root))
        #expect(second.load(AudioSessionState.self) == nil)
        second.flush()
        #expect(!FileManager.default.fileExists(atPath: second.directory.path))
    }

    @Test func removesOldSessions() throws {
        let root = sessionRoot()
        let session = try #require(EditSession(slot: "audio", input: .pick, root: root))
        session.save(AudioSessionState(sources: [], current: AudioProjectState(), baseline: AudioProjectState()), files: [])
        session.flush()
        EditSession.removeSessions(in: root, olderThan: Date().addingTimeInterval(-60))
        #expect(FileManager.default.fileExists(atPath: session.directory.path))
        EditSession.removeSessions(in: root, olderThan: Date().addingTimeInterval(60))
        #expect(!FileManager.default.fileExists(atPath: session.directory.path))
    }

    @Test func imageEditorResumesLastAppliedEdit() async throws {
        let root = sessionRoot()
        let request = ImageEditorRequest(input: .files([stillFile(0xFFFF_0000), stillFile(0xFF00_00FF)]))
        let first = EditorModel(request: request, session: EditSession(slot: "image", input: request.input, root: root)) { _ in }
        first.start()
        try await waitReady { first.phase == .ready }
        var edit = first.history.current
        edit.filterId = "mono"
        edit.filterIntensity = 1
        first.history.commit(edit)
        first.session?.flush()

        // 앱이 종료된 뒤 같은 요청으로 다시 연다.
        let second = EditorModel(request: request, session: EditSession(slot: "image", input: request.input, root: root)) { _ in }
        #expect(second.phase == .loading)
        second.start()
        try await waitReady { second.phase == .ready }
        #expect(second.pages.count == 2)
        #expect(second.history.current.filterId == "mono")
        #expect(second.isDirty && !second.canUndo)
        #expect(second.notice == "session_restored")
        second.finish(.cancelled)
        second.session?.flush()
        #expect(!FileManager.default.fileExists(atPath: second.session!.directory.path))
    }

    @Test func pickedPhotoResumesWithoutPicker() async throws {
        let root = sessionRoot()
        let first = EditorModel(request: ImageEditorRequest(input: .files([stillFile(0xFFFF_0000)])),
                                session: EditSession(slot: "image", input: .pick, root: root)) { _ in }
        first.start()
        try await waitReady { first.phase == .ready }
        var edit = first.history.current
        edit.geometry.quarterTurns = 1
        first.history.commit(edit)
        first.session?.flush()

        let second = EditorModel(request: ImageEditorRequest(input: .pick), session: EditSession(slot: "image", input: .pick, root: root)) { _ in }
        #expect(second.phase == .loading)
        second.start()
        try await waitReady { second.phase == .ready }
        #expect(second.history.current.geometry.quarterTurns == 1)
        second.finish(.cancelled)
    }

    @Test func videoEditorResumes() async throws {
        let root = sessionRoot()
        let request = VideoEditorRequest(input: .file(try await makeTestVideo()))
        let first = VideoEditorModel(request: request, session: EditSession(slot: "video", input: request.input, root: root)) { _ in }
        first.start()
        try await waitReady { first.phase == .ready }
        first.addAudio(url: try makeTestAudio(seconds: 1), role: .music)
        try await waitReady { !first.history.current.audio.isEmpty }
        first.session?.flush()

        let second = VideoEditorModel(request: request, session: EditSession(slot: "video", input: request.input, root: root)) { _ in }
        second.start()
        try await waitReady { second.phase == .ready }
        #expect(second.history.current == first.history.current)
        #expect(second.audioSources.count == 1 && second.isDirty)
        second.finish(.cancelled)
    }

    @Test func motionEditorResumes() async throws {
        let root = sessionRoot()
        let request = MotionEditorRequest(input: .files([stillFile(0xFFFF_0000), stillFile(0xFF00_00FF)]))
        let first = MotionEditorModel(request: request, session: EditSession(slot: "motion", input: request.input, root: root)) { _ in }
        first.start()
        try await waitReady { first.phase == .ready }
        var project = first.history.current
        project.slideDurationMs = 3000
        project.loop = .boomerang
        first.history.commit(project)
        first.session?.flush()

        let second = MotionEditorModel(request: request, session: EditSession(slot: "motion", input: request.input, root: root)) { _ in }
        second.start()
        try await waitReady { second.phase == .ready }
        #expect(second.history.current.sameContent(as: project))
        #expect(second.isDirty)
        second.finish(.cancelled)
    }

    @Test func audioEditorResumes() async throws {
        let root = sessionRoot()
        let request = AudioEditorRequest(input: .files([try makeTestAudio(seconds: 1)]))
        let first = AudioEditorModel(request: request, session: EditSession(slot: "audio", input: request.input, root: root)) { _ in }
        first.start()
        try await waitReady { first.phase == .ready }
        first.seek(to: 500_000)
        first.splitAtPlayhead()
        first.session?.flush()

        let second = AudioEditorModel(request: request, session: EditSession(slot: "audio", input: request.input, root: root)) { _ in }
        second.start()
        try await waitReady { second.phase == .ready }
        #expect(second.project == first.project)
        #expect(second.isDirty && !second.canUndo)
        second.finish(.cancelled)
    }
}
