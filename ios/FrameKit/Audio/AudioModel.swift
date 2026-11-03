import Foundation

/// 음성 편집 상태. 시간은 결과 시간(µs)이다.
///
/// - 본 줄(`voice`): 녹음·불러온 음성이 빈틈 없이 이어진다. 끼워 넣거나 구간을 지우면 뒤가 밀리거나 당겨진다.
/// - 겹친 줄(`music`): 본 줄 위에 자유롭게 놓인다.
///
/// 결과 길이는 본 줄 끝이고, 본 줄이 비면 반복하지 않는 겹친 조각의 끝이다. Android AudioProject와 같은 계산이다.
struct AudioProjectState: Hashable, Sendable, Codable {
    static let unbounded: Micros = Micros.max / 4

    var clips: [AudioClipState] = []

    var mainClips: [AudioClipState] { clips.filter { $0.role == .voice }.sorted { $0.timelineStart < $1.timelineStart } }
    var layerClips: [AudioClipState] { clips.filter { $0.role != .voice } }

    var duration: Micros {
        let main = mainClips.map { $0.outputRange(videoDuration: Self.unbounded).end }.max() ?? 0
        if main > 0 { return main }
        return layerClips.filter { !$0.loop }.map { $0.outputRange(videoDuration: Self.unbounded).end }.max() ?? 0
    }

    func range(of clip: AudioClipState) -> TimeRangeUs { clip.outputRange(videoDuration: duration) }
}

/// 조각 하나의 나누기·끝 자르기·옮기기. Android AudioEditing과 같다.
enum AudioClipEditing {
    /// [at]에서 둘로 나눈다. 두 조각 모두 [min] 이상이어야 한다.
    static func split(_ clip: AudioClipState, at: Micros, total: Micros, newId: String, min: Micros) -> (AudioClipState, AudioClipState)? {
        let range = clip.outputRange(videoDuration: total)
        guard at - range.start >= min, range.end - at >= min else { return nil }
        var first = clip, second = clip
        first.timelineEnd = at
        first.fadeOut = 0
        second.id = newId
        second.timelineStart = at
        second.startOffset = offset(clip, after: at - clip.timelineStart)
        second.timelineEnd = range.end
        second.fadeIn = 0
        return (first, second)
    }

    /// 왼쪽 끝을 옮긴다. 들리던 소리는 제자리에 남는다. 반복하지 않으면 원본 처음보다 앞으로 늘지 않는다.
    static func trimStart(_ clip: AudioClipState, to start: Micros, total: Micros, min: Micros) -> AudioClipState {
        let range = clip.outputRange(videoDuration: total)
        let length = clip.sourceRange.duration
        let earliest = clip.loop ? 0 : max(0, clip.timelineStart - clip.startOffset)
        let target = Swift.min(Swift.max(start, earliest), range.end - min)
        let delta = target - clip.timelineStart
        var result = clip
        result.timelineStart = target
        result.startOffset = clip.loop ? mod(clip.startOffset + delta, length) : Swift.min(Swift.max(0, clip.startOffset + delta), length - 1)
        result.timelineEnd = range.end
        return result
    }

    /// 오른쪽 끝을 옮긴다. 반복하지 않으면 원본 끝보다 길어지지 않는다.
    static func trimEnd(_ clip: AudioClipState, to end: Micros, total: Micros, min: Micros) -> AudioClipState {
        let natural = clip.loop ? total : clip.timelineStart + clip.sourceRange.duration - clip.startOffset
        var result = clip
        result.timelineEnd = Swift.max(clip.timelineStart + min, Swift.min(end, Swift.min(natural, total)))
        return result
    }

    /// 길이를 그대로 두고 [start]로 옮긴다.
    static func move(_ clip: AudioClipState, to start: Micros, total: Micros) -> AudioClipState {
        let length = clip.outputRange(videoDuration: total).duration
        let target = Swift.min(Swift.max(0, start), Swift.max(0, total - length))
        var result = clip
        result.timelineStart = target
        result.timelineEnd = target + length
        return result
    }

    private static func offset(_ clip: AudioClipState, after elapsed: Micros) -> Micros {
        let length = clip.sourceRange.duration
        let raw = clip.startOffset + elapsed
        return clip.loop ? mod(raw, length) : Swift.min(Swift.max(0, raw), length - 1)
    }

    private static func mod(_ a: Micros, _ b: Micros) -> Micros { b <= 0 ? 0 : ((a % b) + b) % b }
}

/// 본 줄은 빈틈 없이(리플), 겹친 줄은 같은 소리가 같은 자리에 남도록 고치는 편집. Android AudioProjectEditing과 같다.
enum AudioProjectEditing {
    /// 본 줄의 [at]에 끼워 넣는다. 그 자리 조각은 나누고, 뒤(겹친 줄 포함)는 넣은 길이만큼 민다.
    static func insert(_ project: AudioProjectState, _ clip: AudioClipState, at: Micros, newId: () -> String) -> AudioProjectState {
        let at = min(max(0, at), project.duration)
        let length = clip.sourceRange.duration - clip.startOffset
        var cut = cutAt(project, at, newId: newId)
        cut.clips = cut.clips.map { $0.timelineStart >= at ? shift($0, length) : $0 }
        var inserted = clip
        inserted.role = .voice
        inserted.timelineStart = at
        inserted.timelineEnd = at + length
        inserted.loop = false
        cut.clips.append(inserted)
        return cut
    }

    /// 겹친 줄의 [at]에 놓는다. 다른 소리는 그대로다.
    static func overlay(_ project: AudioProjectState, _ clip: AudioClipState, at: Micros) -> AudioProjectState {
        var result = project
        var placed = clip
        placed.role = .music
        placed.timelineStart = max(0, at)
        result.clips.append(placed)
        return result
    }

    static func append(_ project: AudioProjectState, _ clip: AudioClipState, newId: () -> String) -> AudioProjectState {
        insert(project, clip, at: project.duration, newId: newId)
    }

    /// [range]를 잘라 내고 뒤를 당긴다. 겹친 줄의 그 구간도 지운다(반복하는 겹친 조각은 남긴다).
    static func deleteRange(_ project: AudioProjectState, _ range: TimeRangeUs, newId: () -> String) -> AudioProjectState {
        let start = min(max(0, range.start), project.duration)
        let end = min(max(start, range.end), project.duration)
        guard end > start else { return project }
        var cut = cutAt(cutAt(project, start, newId: newId), end, newId: newId)
        let total = cut.duration
        let length = end - start
        cut.clips = cut.clips.compactMap { clip in
            let r = clip.outputRange(videoDuration: total)
            if r.start >= start && r.end <= end && !(clip.loop && clip.role != .voice) { return nil }
            if r.start >= end { return shift(clip, -length) }
            return clip
        }
        return cut
    }

    /// [range]만 남긴다.
    static func keepRange(_ project: AudioProjectState, _ range: TimeRangeUs, newId: () -> String) -> AudioProjectState {
        let afterEnd = deleteRange(project, TimeRangeUs(start: range.end, end: project.duration), newId: newId)
        return deleteRange(afterEnd, TimeRangeUs(start: 0, end: range.start), newId: newId)
    }

    /// 조각을 지운다. 본 줄이면 뒤를 당긴다.
    static func deleteClip(_ project: AudioProjectState, id: String) -> AudioProjectState {
        guard let clip = project.clips.first(where: { $0.id == id }) else { return project }
        var result = project
        result.clips.removeAll { $0.id == id }
        guard clip.role == .voice else { return result }
        let r = clip.outputRange(videoDuration: project.duration)
        result.clips = result.clips.map { $0.timelineStart >= r.end ? shift($0, -r.duration) : $0 }
        return result
    }

    static func split(_ project: AudioProjectState, id: String, at: Micros, newId: String, min: Micros) -> AudioProjectState {
        guard let clip = project.clips.first(where: { $0.id == id }),
              let pieces = AudioClipEditing.split(clip, at: at, total: project.duration, newId: newId, min: min) else { return project }
        var result = project
        result.clips = project.clips.flatMap { $0.id == id ? [pieces.0, pieces.1] : [$0] }
        return result
    }

    /// [at]을 지나는 조각을 모두 둘로 나눈다.
    static func cutAt(_ project: AudioProjectState, _ at: Micros, newId: () -> String) -> AudioProjectState {
        let total = project.duration
        var result = project
        result.clips = project.clips.flatMap { clip -> [AudioClipState] in
            let r = clip.outputRange(videoDuration: total)
            guard at > r.start, at < r.end, let pieces = AudioClipEditing.split(clip, at: at, total: total, newId: newId(), min: 1) else { return [clip] }
            return [pieces.0, pieces.1]
        }
        return result
    }

    /// 본 줄을 시작 순서대로 빈틈 없이 붙인다.
    static func closeGaps(_ project: AudioProjectState) -> AudioProjectState {
        var cursor: Micros = 0
        let main = project.mainClips.map { clip -> AudioClipState in
            let length = clip.outputRange(videoDuration: AudioProjectState.unbounded).duration
            var placed = clip
            placed.timelineStart = cursor
            placed.timelineEnd = cursor + length
            cursor += length
            return placed
        }
        var result = project
        result.clips = main + project.layerClips
        return result
    }

    private static func shift(_ clip: AudioClipState, _ delta: Micros) -> AudioClipState {
        var result = clip
        result.timelineStart = max(0, clip.timelineStart + delta)
        result.timelineEnd = clip.timelineEnd.map { max(0, $0 + delta) }
        return result
    }
}

/// 실행 취소 기록.
struct AudioHistory {
    static let capacity = 50
    private(set) var current: AudioProjectState
    private(set) var baseline: AudioProjectState
    private var past: [AudioProjectState] = []
    private var future: [AudioProjectState] = []

    init(_ initial: AudioProjectState) {
        current = initial
        baseline = initial
    }

    /// 세션에서 복원한다. 실행 취소 기록은 비어 있다.
    init(current: AudioProjectState, baseline: AudioProjectState) {
        self.current = current
        self.baseline = baseline
    }

    var canUndo: Bool { !past.isEmpty }
    var canRedo: Bool { !future.isEmpty }
    var isDirty: Bool { current != baseline }

    mutating func commit(_ next: AudioProjectState) {
        guard next != current else { return }
        past.append(current)
        if past.count > Self.capacity { past.removeFirst(past.count - Self.capacity) }
        current = next
        future.removeAll()
    }

    mutating func undo() {
        guard let previous = past.popLast() else { return }
        future.append(current)
        current = previous
    }

    mutating func redo() {
        guard let next = future.popLast() else { return }
        past.append(current)
        current = next
    }
}
