import AVFoundation
import Foundation
import Testing
@testable import FrameKit

/// 섞은 소리의 가장 큰 진폭(0...1).
func mixedPeak(_ clips: [AudioClipState], sources: [AudioSource], duration: Micros) async throws -> Float {
    let composition = AVMutableComposition()
    let mix = AVMutableAudioMix()
    mix.inputParameters = AudioClipMixer.add(clips, sources: sources, duration: duration, to: composition)
    let reader = try AVAssetReader(asset: composition)
    let tracks = try await composition.loadTracks(withMediaType: .audio)
    let output = AVAssetReaderAudioMixOutput(audioTracks: tracks, audioSettings: [
        AVFormatIDKey: kAudioFormatLinearPCM, AVLinearPCMBitDepthKey: 32, AVLinearPCMIsFloatKey: true,
        AVLinearPCMIsBigEndianKey: false, AVLinearPCMIsNonInterleaved: false,
    ])
    output.audioMix = mix
    reader.add(output)
    reader.startReading()
    var peak: Float = 0
    while let buffer = output.copyNextSampleBuffer(), let block = CMSampleBufferGetDataBuffer(buffer) {
        var length = 0
        var pointer: UnsafeMutablePointer<CChar>?
        CMBlockBufferGetDataPointer(block, atOffset: 0, lengthAtOffsetOut: nil, totalLengthOut: &length, dataPointerOut: &pointer)
        pointer?.withMemoryRebound(to: Float.self, capacity: length / 4) { samples in
            for i in 0..<(length / 4) { peak = max(peak, abs(samples[i])) }
        }
    }
    return peak
}

struct AudioMixTests {
    @Test func volumeAboveOneAmplifies() async throws {
        let source = try await AudioSource.open(url: try makeTestAudio(seconds: 1), name: "tone")
        defer { source.close() }
        func clip(_ volume: Double) -> AudioClipState {
            AudioClipState(id: "a", source: 0, sourceRange: TimeRangeUs(start: 0, end: source.duration), timelineStart: 0, volume: volume)
        }
        let normal = try await mixedPeak([clip(1)], sources: [source], duration: 1_000_000)
        let loud = try await mixedPeak([clip(1.6)], sources: [source], duration: 1_000_000)
        #expect(normal > 0.4 && normal < 0.6)
        #expect(loud > normal * 1.4)
    }
}
