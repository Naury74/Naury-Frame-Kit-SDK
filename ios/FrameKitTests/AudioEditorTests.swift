import AVFoundation
import Foundation
import Testing
@testable import FrameKit

private func voice(_ id: String, _ start: Micros, _ length: Micros, source: Int = 0) -> AudioClipState {
    AudioClipState(id: id, source: source, sourceRange: TimeRangeUs(start: 0, end: length), timelineStart: start, role: .voice, timelineEnd: start + length)
}

private func newId() -> String { "n" + UUID().uuidString.prefix(6) }

struct AudioProjectEditingTests {
    @Test func insertSplitsAndPushesLaterClips() {
        var project = AudioProjectState(clips: [voice("a", 0, 4_000_000)])
        project.clips.append(AudioClipState(id: "m", source: 1, sourceRange: TimeRangeUs(start: 0, end: 1_000_000), timelineStart: 3_000_000))
        let next = AudioProjectEditing.insert(project, voice("b", 0, 1_000_000), at: 2_000_000, newId: newId)
        #expect(next.duration == 5_000_000)
        #expect(next.mainClips.map(\.timelineStart) == [0, 2_000_000, 3_000_000])
        #expect(next.layerClips.first?.timelineStart == 4_000_000)
    }

    @Test func deleteRangeRipplesAndKeepRangeTrims() {
        let project = AudioProjectState(clips: [voice("a", 0, 3_000_000), voice("b", 3_000_000, 3_000_000)])
        let cut = AudioProjectEditing.deleteRange(project, TimeRangeUs(start: 2_000_000, end: 4_000_000), newId: newId)
        #expect(cut.duration == 4_000_000)
        let kept = AudioProjectEditing.keepRange(project, TimeRangeUs(start: 1_000_000, end: 2_500_000), newId: newId)
        #expect(kept.duration == 1_500_000)
        #expect(kept.mainClips.first?.timelineStart == 0)
        #expect(kept.mainClips.first?.startOffset == 1_000_000)
    }

    @Test func deleteVoiceClipPullsBackAndSplitRespectsMinimum() {
        let project = AudioProjectState(clips: [voice("a", 0, 2_000_000), voice("b", 2_000_000, 1_000_000)])
        let deleted = AudioProjectEditing.deleteClip(project, id: "a")
        #expect(deleted.mainClips.first?.timelineStart == 0 && deleted.duration == 1_000_000)
        #expect(AudioProjectEditing.split(project, id: "a", at: 100_000, newId: "x", min: 200_000) == project)
        let split = AudioProjectEditing.split(project, id: "a", at: 1_000_000, newId: "x", min: 200_000)
        #expect(split.clips.count == 3 && split.duration == 3_000_000)
        #expect(split.clips.first { $0.id == "x" }?.startOffset == 1_000_000)
    }

    @Test func trimStartCannotPassSourceStartUnlessLooping() {
        let clip = AudioClipState(id: "a", source: 0, sourceRange: TimeRangeUs(start: 0, end: 2_000_000), timelineStart: 1_000_000, startOffset: 500_000)
        let trimmed = AudioClipEditing.trimStart(clip, to: 0, total: 10_000_000, min: 200_000)
        #expect(trimmed.timelineStart == 500_000 && trimmed.startOffset == 0)
        let end = AudioClipEditing.trimEnd(clip, to: 9_000_000, total: 10_000_000, min: 200_000)
        #expect(end.timelineEnd == 2_500_000)
    }

    @Test func closeGapsPacksMainLane() {
        let project = AudioProjectState(clips: [voice("a", 0, 1_000_000), voice("b", 3_000_000, 1_000_000)])
        #expect(AudioProjectEditing.closeGaps(project).mainClips.map(\.timelineStart) == [0, 1_000_000])
    }
}

struct AudioEngineTests {
    @Test func waveformFindsPeaks() async throws {
        let source = try await AudioSource.open(url: try makeTestAudio(seconds: 1), name: "tone")
        defer { source.close() }
        let wave = await Waveform.extract(source)
        #expect(wave.peaks.count >= 50)
        #expect(wave.peak(at: 500_000) > 0.8)
        #expect(wave.peak(at: 5_000_000) == 0)
    }

    @Test func exportsM4aWithRequestedLength() async throws {
        let source = try await AudioSource.open(url: try makeTestAudio(seconds: 1), name: "tone")
        defer { source.close() }
        var project = AudioProjectState()
        project = AudioProjectEditing.append(project, voice("a", 0, source.duration), newId: newId)
        project = AudioProjectEditing.append(project, voice("b", 0, source.duration), newId: newId)
        let media = try await AudioExporter.export(project, sources: [source], bitrate: 96_000) { _ in }
        defer { FrameKitOutputs.delete(media.url) }
        #expect(media.mediaType == .audio && media.mimeType == "audio/mp4")
        let length = try await AVURLAsset(url: media.url).load(.duration).seconds
        #expect(abs(length - 2) < 0.1)
        #expect(media.durationMs == project.duration / 1000)
    }
}

@MainActor
struct AudioEditorModelTests {
    @Test func opensFilesSplitsAndUndoes() async throws {
        let model = AudioEditorModel(request: AudioEditorRequest(input: .files([try makeTestAudio(seconds: 1), try makeTestAudio(seconds: 1)]))) { _ in }
        model.start()
        for _ in 0..<150 where model.phase != .ready { try await Task.sleep(nanoseconds: 20_000_000) }
        #expect(model.phase == .ready)
        #expect(model.project.mainClips.count == 2)
        #expect(abs(model.duration - 2_000_000) < 50_000)
        #expect(!model.isDirty)
        model.seek(to: 500_000)
        model.splitAtPlayhead()
        #expect(model.project.clips.count == 3 && model.isDirty)
        model.undo()
        #expect(model.project.clips.count == 2 && !model.isDirty)
        model.finish(.cancelled)
    }

    @Test func rejectsDataInputAndBadBitrate() {
        #expect(!AudioEditorRequest(input: .data(Data([1]))).validate().isEmpty)
        #expect(!AudioEditorRequest(input: .pick, export: AudioExportConfig(bitrate: 8_000)).validate().isEmpty)
        #expect(AudioEditorRequest(input: .capture).validate().isEmpty)
    }
}
