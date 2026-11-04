import AVFoundation
import Foundation
import SwiftUI

/// 소리 원본의 파형. 20ms마다 가장 큰 진폭(0...1, 가장 큰 값이 1이 되게 키움)을 담는다.
struct Waveform: Sendable {
    static let bucket: Micros = 20_000
    /// 앞 20분까지만 읽는다.
    static let maxDuration: Micros = 20 * 60 * 1_000_000

    let peaks: [Float]

    func peak(at t: Micros) -> Float {
        guard t >= 0 else { return 0 }
        let i = Int(t / Self.bucket)
        return peaks.indices.contains(i) ? peaks[i] : 0
    }

    /// [clip]이 결과 시각 [t]에 내는 소리의 진폭. 반복·시작 위치를 반영한다.
    func peak(for clip: AudioClipState, at t: Micros) -> Float {
        let length = clip.sourceRange.duration
        let elapsed = t - clip.timelineStart
        let firstPass = length - clip.startOffset
        guard elapsed >= 0 else { return 0 }
        if elapsed < firstPass { return peak(at: clip.sourceRange.start + clip.startOffset + elapsed) }
        return clip.loop && length > 0 ? peak(at: clip.sourceRange.start + (elapsed - firstPass) % length) : 0
    }

    /// [rect] 안에 3pt 간격 막대로 그릴 경로. [time]은 화면 x를 결과 시각으로 바꾼다.
    func bars(for clip: AudioClipState, in rect: CGRect, visible: ClosedRange<CGFloat>, time: (CGFloat) -> Micros) -> Path {
        var path = Path()
        let half = max(0, rect.height / 2 - 3)
        var x = max(rect.minX, visible.lowerBound)
        while x < min(rect.maxX, visible.upperBound) {
            let h = max(0.75, half * CGFloat(peak(for: clip, at: time(x))))
            path.addRect(CGRect(x: x, y: rect.midY - h, width: 2, height: h * 2))
            x += 3
        }
        return path
    }

    /// 실패하거나 취소되면 빈 파형.
    static func extract(_ source: AudioSource) async -> Waveform {
        let asset = source.asset, track = source.track
        let duration = min(source.duration, maxDuration)
        return await Task.detached(priority: .utility) { () -> Waveform in
            guard let reader = try? AVAssetReader(asset: asset) else { return Waveform(peaks: []) }
            reader.timeRange = CMTimeRange(start: .zero, duration: duration.cmTime)
            let output = AVAssetReaderTrackOutput(track: track, outputSettings: [
                AVFormatIDKey: kAudioFormatLinearPCM, AVLinearPCMBitDepthKey: 32, AVLinearPCMIsFloatKey: true,
                AVLinearPCMIsBigEndianKey: false, AVLinearPCMIsNonInterleaved: false,
            ])
            guard reader.canAdd(output) else { return Waveform(peaks: []) }
            reader.add(output)
            guard reader.startReading() else { return Waveform(peaks: []) }
            var peaks = [Float](repeating: 0, count: Int(duration / bucket) + 1)
            while !Task.isCancelled, let buffer = output.copyNextSampleBuffer() {
                guard let block = CMSampleBufferGetDataBuffer(buffer),
                      let format = CMSampleBufferGetFormatDescription(buffer),
                      let description = CMAudioFormatDescriptionGetStreamBasicDescription(format)?.pointee else { continue }
                let channels = max(1, Int(description.mChannelsPerFrame))
                let rate = description.mSampleRate
                let start = CMSampleBufferGetPresentationTimeStamp(buffer).micros
                var length = 0
                var pointer: UnsafeMutablePointer<CChar>?
                guard CMBlockBufferGetDataPointer(block, atOffset: 0, lengthAtOffsetOut: nil, totalLengthOut: &length,
                                                  dataPointerOut: &pointer) == kCMBlockBufferNoErr, let pointer else { continue }
                let frames = length / 4 / channels
                pointer.withMemoryRebound(to: Float.self, capacity: frames * channels) { samples in
                    for f in 0..<frames {
                        var value: Float = 0
                        for c in 0..<channels { value = max(value, abs(samples[f * channels + c])) }
                        let t = start + Micros(Double(f) / rate * 1_000_000)
                        let i = Int(t / bucket)
                        if peaks.indices.contains(i), value > peaks[i] { peaks[i] = value }
                    }
                }
            }
            reader.cancelReading()
            let scale = 1 / max(peaks.max() ?? 0, 0.1)
            return Waveform(peaks: peaks.map { min(1, $0 * scale) })
        }.value
    }
}

/// 음성 편집 결과를 소리 합성으로 만든다.
enum AudioComposer {
    static func build(_ project: AudioProjectState, sources: [AudioSource]) -> (AVMutableComposition, AVMutableAudioMix) {
        let composition = AVMutableComposition()
        let mix = AVMutableAudioMix()
        mix.inputParameters = AudioClipMixer.add(project.clips, sources: sources, duration: project.duration, to: composition)
        return (composition, mix)
    }
}

/// AAC M4A 저장. 비트레이트를 지키려고 섞은 PCM을 직접 인코딩한다.
enum AudioExporter {
    static func export(_ project: AudioProjectState, sources: [AudioSource], bitrate: Int,
                       progress: @escaping @Sendable (Double) -> Void) async throws -> EditedMedia {
        let duration = project.duration
        guard duration > 0 else { throw EditorError.logged(.invalidProject, "empty audio") }
        try OutputStore.checkFreeSpace(bytes: Int64(Double(bitrate) / 8 * Double(duration) / 1_000_000) + 2_000_000)
        let (composition, mix) = AudioComposer.build(project, sources: sources)
        let tracks = try await composition.loadTracks(withMediaType: .audio)
        guard !tracks.isEmpty else { throw EditorError.logged(.invalidProject, "no playable audio") }
        let (slot, partial) = try OutputStore.reserve(extension: "m4a")
        let work = try OutputStore.temporaryDirectory.appendingPathComponent("audio-\(UUID().uuidString).m4a")
        defer { try? FileManager.default.removeItem(at: work) }
        do {
            try await encode(composition: composition, tracks: tracks, mix: mix, duration: duration, bitrate: bitrate, to: work, progress: progress)
        } catch is CancellationError {
            throw CancellationError()
        } catch let error as EditorError {
            throw error
        } catch {
            throw ExportErrors.map(error, context: "audio export")
        }
        try FileManager.default.moveItemSafely(work, to: partial)
        let final = try OutputStore.publish(partial: partial, to: slot)
        let bytes = (try? final.resourceValues(forKeys: [.fileSizeKey]).fileSize).map { Int64($0) } ?? 0
        return EditedMedia(url: final, mediaType: .audio, width: 0, height: 0, mimeType: "audio/mp4", fileSize: bytes, warnings: [],
                           durationMs: duration / 1000, pageCount: nil, recognizedText: nil, isOriginal: false)
    }

    private static func encode(composition: AVComposition, tracks: [AVAssetTrack], mix: AVAudioMix, duration: Micros, bitrate: Int,
                               to url: URL, progress: (Double) -> Void) async throws {
        let reader: AVAssetReader
        do { reader = try AVAssetReader(asset: composition) } catch { throw EditorError.logged(.decodeFailed, "reader: \(error)") }
        reader.timeRange = CMTimeRange(start: .zero, duration: duration.cmTime)
        let output = AVAssetReaderAudioMixOutput(audioTracks: tracks, audioSettings: [
            AVFormatIDKey: kAudioFormatLinearPCM, AVSampleRateKey: 44_100, AVNumberOfChannelsKey: 2,
            AVLinearPCMBitDepthKey: 16, AVLinearPCMIsFloatKey: false, AVLinearPCMIsBigEndianKey: false, AVLinearPCMIsNonInterleaved: false,
        ])
        output.audioMix = mix
        guard reader.canAdd(output) else { throw EditorError.logged(.encodeFailed, "reader output") }
        reader.add(output)
        let writer: AVAssetWriter
        do { writer = try AVAssetWriter(outputURL: url, fileType: .m4a) } catch { throw EditorError.logged(.outputWriteFailed, "writer: \(error)") }
        // 낮은 비트레이트는 스테레오로 받지 않는 인코더가 있어 모노로 낮춘다.
        var settings: [String: Any] = [AVFormatIDKey: kAudioFormatMPEG4AAC, AVSampleRateKey: 44_100, AVNumberOfChannelsKey: 2,
                                       AVEncoderBitRateKey: bitrate]
        if !writer.canApply(outputSettings: settings, forMediaType: .audio) || bitrate < 64_000 { settings[AVNumberOfChannelsKey] = 1 }
        guard writer.canApply(outputSettings: settings, forMediaType: .audio) else {
            throw EditorError.logged(.unsupportedOperation, "aac \(bitrate)")
        }
        let input = AVAssetWriterInput(mediaType: .audio, outputSettings: settings)
        input.expectsMediaDataInRealTime = false
        guard writer.canAdd(input) else { throw EditorError.logged(.encodeFailed, "writer input") }
        writer.add(input)
        guard reader.startReading() else { throw EditorError.logged(.decodeFailed, "read: \(String(describing: reader.error))") }
        guard writer.startWriting() else { throw ExportErrors.map(writer.error, context: "write") }
        writer.startSession(atSourceTime: .zero)
        do {
            while let buffer = output.copyNextSampleBuffer() {
                try Task.checkCancellation()
                while !input.isReadyForMoreMediaData {
                    if writer.status == .failed { throw ExportErrors.map(writer.error, context: "writer") }
                    try await Task.sleep(for: .milliseconds(5))
                }
                guard input.append(buffer) else { throw ExportErrors.map(writer.error, context: "append") }
                progress(min(1, Double(CMSampleBufferGetPresentationTimeStamp(buffer).micros) / Double(duration)))
            }
            guard reader.status == .completed else { throw EditorError.logged(.decodeFailed, "read: \(String(describing: reader.error))") }
            input.markAsFinished()
            await writer.finishWriting()
            guard writer.status == .completed else { throw ExportErrors.map(writer.error, context: "finish") }
            progress(1)
        } catch {
            reader.cancelReading()
            writer.cancelWriting()
            throw error
        }
    }

    /// 녹음 파일을 편집 없이 결과 폴더로 옮긴다.
    static func publishRecording(_ url: URL) async throws -> EditedMedia {
        let duration = try await AVURLAsset(url: url).load(.duration)
        let (slot, partial) = try OutputStore.reserve(extension: "m4a")
        try FileManager.default.moveItemSafely(url, to: partial)
        let final = try OutputStore.publish(partial: partial, to: slot)
        let bytes = (try? final.resourceValues(forKeys: [.fileSizeKey]).fileSize).map { Int64($0) } ?? 0
        return EditedMedia(url: final, mediaType: .audio, width: 0, height: 0, mimeType: "audio/mp4", fileSize: bytes, warnings: [],
                           durationMs: Int64(duration.seconds * 1000), pageCount: nil, recognizedText: nil, isOriginal: false)
    }
}
