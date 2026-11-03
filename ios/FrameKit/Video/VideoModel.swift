import CoreGraphics
import Foundation
internal import FrameKitCore

/// 마이크로초. Android core와 같은 시간 단위다.
typealias Micros = Int64

/// 반열린 시간 범위 [start, end).
struct TimeRangeUs: Hashable, Sendable {
    var start: Micros
    var end: Micros

    var duration: Micros { end - start }
    var isEmpty: Bool { duration <= 0 }
    func contains(_ t: Micros) -> Bool { t >= start && t < end }

    /// 정렬하고 겹치거나 맞닿은 범위를 합친다. 빈 범위는 버린다.
    static func normalize(_ ranges: [TimeRangeUs]) -> [TimeRangeUs] {
        var result: [TimeRangeUs] = []
        for range in ranges.filter({ !$0.isEmpty }).sorted(by: { $0.start < $1.start }) {
            if var last = result.last, range.start <= last.end {
                last.end = max(last.end, range.end)
                result[result.count - 1] = last
            } else {
                result.append(range)
            }
        }
        return result
    }
}

/// 클립 하나의 기하·색 편집.
struct ClipEffects: Hashable, Sendable {
    var geometry = GeometryState()
    var adjustments: [AdjustKind: Double] = [:]
    var filterId = "original"
    var filterIntensity = 0.0

    func colorSpec(grainSeed: Int64) -> ColorEffectSpec {
        var edit = PhotoEdit(grainSeed: grainSeed)
        edit.adjustments = adjustments
        edit.filterId = filterId
        edit.filterIntensity = filterIntensity
        return edit.colorSpec
    }

    var hasColor: Bool { adjustments.values.contains { $0 != 0 } || (filterId != "original" && filterIntensity > 0) }
}

/// 영상 클립. 타임라인 위치는 저장하지 않고 순서로 정한다.
struct VideoClipState: Hashable, Sendable, Identifiable {
    static let minSpeed = 0.25
    static let maxSpeed = 4.0
    static let maxVolume = 2.0
    static let speedPresets = [0.25, 0.5, 1.0, 1.5, 2.0, 4.0]

    var id: String
    /// 원본 목록에서의 위치.
    var source: Int
    /// 원본 시간 기준 사용 범위.
    var sourceRange: TimeRangeUs
    var speed = 1.0
    var effects = ClipEffects()
    var muted = false
    /// 0...2.
    var volume = 1.0
    /// 원본 시간 기준 소리를 끈 구간.
    var mutedRanges: [TimeRangeUs] = []

    /// 속도를 반영한 결과 길이(한 번만 반올림).
    var outputDuration: Micros { Micros((Double(sourceRange.duration) / speed).rounded()) }
}

enum AudioRole: String, Hashable, Sendable { case music, voice }

/// 배경 음악·음성 조각.
struct AudioClipState: Hashable, Sendable, Identifiable {
    var id: String
    /// 소리 원본 목록에서의 위치.
    var source: Int
    var sourceRange: TimeRangeUs
    var timelineStart: Micros
    var volume = 1.0
    var loop = false
    var role = AudioRole.music
    var timelineEnd: Micros?
    var fadeIn: Micros = 0
    var fadeOut: Micros = 0
    /// 첫 재생만 원본 범위 안에서 이만큼 건너뛴다. 반복은 원본 범위 처음부터.
    var startOffset: Micros = 0

    /// 결과 시간 범위. 영상 길이를 넘지 않는다.
    func outputRange(videoDuration: Micros) -> TimeRangeUs {
        let start = max(0, timelineStart)
        let natural = loop ? Micros.max : start + max(0, sourceRange.duration - startOffset)
        let end = min(natural, timelineEnd ?? Micros.max, videoDuration)
        return TimeRangeUs(start: start, end: max(end, start))
    }

    /// 재생 조각. Android AudioPlacement.segments와 같다.
    func segments(videoDuration: Micros) -> [(output: Micros, source: TimeRangeUs)] {
        let range = outputRange(videoDuration: videoDuration)
        let length = sourceRange.duration
        guard length > 0, !range.isEmpty else { return [] }
        var result: [(Micros, TimeRangeUs)] = []
        var cursor = range.start
        var from = sourceRange.start + min(max(0, startOffset), length - 1)
        while cursor < range.end {
            let play = min(sourceRange.end - from, range.end - cursor)
            guard play > 0 else { break }
            result.append((cursor, TimeRangeUs(start: from, end: from + play)))
            cursor += play
            guard loop else { break }
            from = sourceRange.start
        }
        return result
    }
}

/// 시간이 정해진 요소·가리기(결과 시간 기준).
struct TimedOverlay: Hashable, Sendable {
    var overlay: Overlay
    var range: TimeRangeUs
}

struct TimedMask: Hashable, Sendable {
    var mask: PrivacyMask
    var range: TimeRangeUs
}

enum CanvasFit: String, Hashable, Sendable, CaseIterable { case fit, fill }

/// 결과 화면 비율. 비율이 없으면 첫 클립의 편집 결과 비율.
struct CanvasState: Hashable, Sendable {
    var aspectWidth: Int?
    var aspectHeight: Int?
    var fit = CanvasFit.fit
}

/// 영상 편집 상태 전체.
struct VideoEdit: Hashable, Sendable {
    static let maxPrivacyMasks = 8

    var clips: [VideoClipState]
    var audio: [AudioClipState] = []
    var overlays: [TimedOverlay] = []
    var masks: [TimedMask] = []
    var watermark: WatermarkSpec?
    var canvas = CanvasState()
    var grainSeed: Int64 = Int64.random(in: Int64.min...Int64.max)

    var duration: Micros { clips.reduce(0) { $0 + $1.outputDuration } }

    /// 각 클립의 결과 시작 시각.
    var clipStarts: [Micros] {
        var t: Micros = 0
        return clips.map { clip in
            defer { t += clip.outputDuration }
            return t
        }
    }

    /// 결과 시각 [t]의 클립 위치와 원본 시각. 끝이면 마지막 클립의 끝.
    func locate(_ t: Micros) -> (index: Int, sourceTime: Micros, clipStart: Micros)? {
        guard t >= 0, !clips.isEmpty else { return nil }
        let starts = clipStarts
        for (i, clip) in clips.enumerated() where t < starts[i] + clip.outputDuration {
            return (i, clip.sourceRange.start + Micros((Double(t - starts[i]) * clip.speed).rounded()), starts[i])
        }
        let last = clips.count - 1
        return (last, clips[last].sourceRange.end, starts[last])
    }

    /// 길이가 바뀐 뒤 범위를 넘는 요소·가리기를 줄이거나 지우고, 끝 뒤에서 시작하는 소리를 지운다.
    mutating func clampTimed() {
        let total = duration
        func clamp(_ r: TimeRangeUs) -> TimeRangeUs { TimeRangeUs(start: min(r.start, total), end: min(r.end, total)) }
        overlays = overlays.map { TimedOverlay(overlay: $0.overlay, range: clamp($0.range)) }.filter { !$0.range.isEmpty }
        masks = masks.map { TimedMask(mask: $0.mask, range: clamp($0.range)) }.filter { !$0.range.isEmpty }
        audio = audio.filter { $0.timelineStart < total }
    }

    func sameContent(as other: VideoEdit) -> Bool {
        clips == other.clips && audio == other.audio && overlays == other.overlays && masks == other.masks
            && watermark == other.watermark && canvas == other.canvas
    }
}
