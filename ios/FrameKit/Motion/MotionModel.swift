import CoreGraphics
import Foundation
internal import FrameKitCore

enum MotionKind: Hashable, Sendable, Codable { case slideshow, animation }

enum CameraMotion: String, Hashable, Sendable, Codable, CaseIterable { case auto, none, zoomIn, zoomOut, panLeft, panRight }

enum MotionTransition: String, Hashable, Sendable, Codable, CaseIterable { case none, fade, slide, zoom }

enum LoopMode: String, Hashable, Sendable, Codable, CaseIterable { case forward, boomerang }

/// 결과 화면 비율. `original`은 첫 원본 비율.
enum MotionAspect: String, Hashable, Sendable, Codable, CaseIterable {
    case original, square, portrait45, story916, wide169

    var widthOverHeight: Double? {
        switch self {
        case .original: nil
        case .square: 1
        case .portrait45: 0.8
        case .story916: 9.0 / 16
        case .wide169: 16.0 / 9
        }
    }
}

/// `fill`은 잘라 채우고, `fit`은 배경 색으로 남는 곳을 채운다.
enum MotionFit: String, Hashable, Sendable, Codable, CaseIterable { case fill, fit }

enum OverlayAnimation: String, Hashable, Sendable, Codable, CaseIterable {
    case none, fadeIn, pop, slideUp, bounce, wiggle, pulse

    /// 처음에 한 번만 움직이는 효과.
    var isEntrance: Bool { self == .fadeIn || self == .pop || self == .slideUp }
}

struct MotionOverlay: Hashable, Sendable, Codable {
    var overlay: Overlay
    var animation = OverlayAnimation.none
}

/// 움직이는 이미지 편집 상태. 시간은 밀리초, 소리 조각만 마이크로초다.
struct MotionProject: Hashable, Sendable, Codable {
    static let minSlideMs: Int64 = 300
    static let maxSlideMs: Int64 = 10_000
    static let defaultTransitionMs: Int64 = 500
    static let minSpeed = 0.25
    static let maxSpeed = 4.0
    static let minTrimMs: Int64 = 100

    var kind: MotionKind
    /// 슬라이드 순서(원본 번호). 움직이는 원본은 하나.
    var slides: [Int]
    var slideDurationMs: Int64 = 2000
    var transition = MotionTransition.fade
    var transitionMs = MotionProject.defaultTransitionMs
    var cameraMotion = CameraMotion.auto
    var speed = 1.0
    var trimStartMs: Int64 = 0
    /// `nil`이면 끝까지.
    var trimEndMs: Int64?
    var loop = LoopMode.forward
    var aspect = MotionAspect.original
    var fit = MotionFit.fill
    var backgroundArgb: UInt32 = 0xFF00_0000
    var color = ClipEffects()
    var overlays: [MotionOverlay] = []
    var audio: [AudioClipState] = []
    var grainSeed: Int64 = Int64.random(in: Int64.min...Int64.max)

    var colorSpec: ColorEffectSpec { color.colorSpec(grainSeed: grainSeed) }
    var hasColor: Bool { color.hasColor || color.adjustments[.fade] != nil }

    func sameContent(as other: MotionProject) -> Bool {
        var a = self, b = other
        a.grainSeed = 0
        b.grainSeed = 0
        return a == b
    }
}

/// 움직이는 원본에서 푼 프레임(정방향, 캔버스 전체를 합성한 그림).
struct AnimatedFrames: @unchecked Sendable {
    let frames: [CGImage]
    let durationsMs: [Int64]
    let width: Int
    let height: Int

    var totalMs: Int64 { durationsMs.reduce(0, +) }
}

/// 렌더러가 그릴 원본.
final class MotionMedia: @unchecked Sendable {
    let kind: MotionKind
    /// 원본마다 정방향 너비/높이.
    let aspects: [Double]
    let animation: AnimatedFrames?
    private let slide: @Sendable (Int) -> CGImage?

    init(kind: MotionKind, aspects: [Double], animation: AnimatedFrames?, slide: @escaping @Sendable (Int) -> CGImage?) {
        self.kind = kind
        self.aspects = aspects
        self.animation = animation
        self.slide = slide
    }

    func slide(_ source: Int) -> CGImage? { slide(source) }
}

/// 결과에 들어갈 프레임. 그림이 같은 구간은 하나로 합쳐 길어진다.
struct OutputFrame: Hashable, Sendable {
    let timeMs: Int64
    let durationMs: Int64
}

/// 슬라이드쇼의 한 시점. `transition`은 앞 사진에서 넘어오는 진행률(0...1).
struct SlidePosition: Hashable, Sendable {
    let index: Int
    let localMs: Int64
    let transition: Double?
}

/// 결과 시각을 원본(사진 번호·프레임)으로 옮기는 계산. Android MotionTimeline과 같다.
enum MotionTimeline {
    static let entranceMs: Int64 = 600
    private static let autoSequence: [CameraMotion] = [.zoomIn, .panLeft, .zoomOut, .panRight]

    static func durationMs(_ project: MotionProject, _ media: MotionMedia) -> Int64 {
        let base = baseDurationMs(project, media)
        return project.loop == .boomerang ? base * 2 : base
    }

    static func baseDurationMs(_ project: MotionProject, _ media: MotionMedia) -> Int64 {
        switch project.kind {
        case .slideshow:
            return project.slideDurationMs * Int64(max(1, project.slides.count))
        case .animation:
            let total = media.animation?.totalMs ?? 0
            let end = min(max(0, project.trimEndMs ?? total), total)
            let start = min(max(0, project.trimStartMs), end)
            return max(1, Int64((Double(end - start) / project.speed).rounded()))
        }
    }

    /// 부메랑을 풀어 앞으로 재생하는 시각.
    static func baseTime(_ project: MotionProject, _ media: MotionMedia, _ timeMs: Int64) -> Int64 {
        let base = baseDurationMs(project, media)
        let t = min(max(0, timeMs), durationMs(project, media))
        return project.loop == .boomerang && t > base ? max(0, 2 * base - t) : min(t, base)
    }

    static func animationFrame(_ project: MotionProject, _ media: MotionMedia, _ timeMs: Int64) -> Int {
        guard let animation = media.animation, !animation.frames.isEmpty else { return 0 }
        let source = project.trimStartMs + Int64((Double(baseTime(project, media, timeMs)) * project.speed).rounded())
        var elapsed: Int64 = 0
        for (index, duration) in animation.durationsMs.enumerated() {
            elapsed += duration
            if source < elapsed { return index }
        }
        return animation.frames.count - 1
    }

    static func slideAt(_ project: MotionProject, _ media: MotionMedia, _ timeMs: Int64) -> SlidePosition {
        let count = max(1, project.slides.count)
        let t = baseTime(project, media, timeMs)
        let index = min(max(0, Int(t / project.slideDurationMs)), count - 1)
        let local = t - Int64(index) * project.slideDurationMs
        let transitionMs = min(max(0, project.transitionMs), project.slideDurationMs / 2)
        let transition: Double? = index > 0 && project.transition != .none && transitionMs > 0 && local < transitionMs
            ? Double(local) / Double(transitionMs) : nil
        return SlidePosition(index: index, localMs: local, transition: transition)
    }

    /// `auto`는 사진마다 번갈아 고른다.
    static func cameraMotion(_ project: MotionProject, _ index: Int) -> CameraMotion {
        project.cameraMotion == .auto ? autoSequence[index % autoSequence.count] : project.cameraMotion
    }

    /// 초당 [fps] 간격의 결과 프레임. 그림이 바뀌지 않는 구간은 하나로 합친다.
    static func outputFrames(_ project: MotionProject, _ media: MotionMedia, fps: Int) -> [OutputFrame] {
        let total = durationMs(project, media)
        let step = 1000.0 / Double(min(max(fps, 1), 60))
        let count = max(1, Int((Double(total) / step).rounded(.up)))
        var frames: [OutputFrame] = []
        var previousKey: Int64?
        for k in 0..<count {
            let start = Int64((Double(k) * step).rounded())
            let end = max(min(Int64((Double(k + 1) * step).rounded()), total), start + 1)
            let key = staticKey(project, media, start)
            if let key, key == previousKey, let last = frames.popLast() {
                frames.append(OutputFrame(timeMs: last.timeMs, durationMs: end - last.timeMs))
            } else {
                frames.append(OutputFrame(timeMs: start, durationMs: end - start))
            }
            previousKey = key
        }
        return frames
    }

    /// 그림이 시간이 지나도 그대로면 같은 그림끼리 같은 값, 움직이는 중이면 `nil`.
    static func staticKey(_ project: MotionProject, _ media: MotionMedia, _ timeMs: Int64) -> Int64? {
        if project.overlays.contains(where: { !$0.animation.isEntrance && $0.animation != .none }) { return nil }
        if project.overlays.contains(where: { $0.animation.isEntrance }) && timeMs < entranceMs { return nil }
        switch project.kind {
        case .animation:
            return Int64(animationFrame(project, media, timeMs))
        case .slideshow:
            let position = slideAt(project, media, timeMs)
            return position.transition != nil || cameraMotion(project, position.index) != .none ? nil : Int64(position.index)
        }
    }

    /// 결과 크기. 긴 변을 [maxLongEdge]에 맞추고 두 변을 짝수로 맞춘다. 움직이는 원본은 원본 비율일 때 원본보다 키우지 않는다.
    static func outputSize(_ project: MotionProject, _ media: MotionMedia, maxLongEdge: Int) -> CGSize {
        let first = project.slides.first.flatMap { media.aspects.indices.contains($0) ? media.aspects[$0] : nil }
        let aspect = project.aspect.widthOverHeight ?? (media.animation.map { Double($0.width) / Double(max(1, $0.height)) } ?? first ?? 1)
        var longEdge = Double(max(2, maxLongEdge))
        if let animation = media.animation, project.aspect.widthOverHeight == nil {
            longEdge = min(longEdge, Double(max(animation.width, animation.height)))
        }
        let width = aspect >= 1 ? longEdge : longEdge * aspect
        let height = aspect >= 1 ? longEdge / aspect : longEdge
        func even(_ v: Double) -> CGFloat { CGFloat(max(2, Int(v.rounded()) / 2 * 2)) }
        return CGSize(width: even(width), height: even(height))
    }

    /// 저장할 초당 프레임. 움직이는 원본은 원본 프레임 간격보다 촘촘하게 만들지 않는다.
    static func frameRate(_ project: MotionProject, _ media: MotionMedia, base: Int) -> Int {
        guard project.kind == .animation, let animation = media.animation, let shortest = animation.durationsMs.min() else { return base }
        let source = Int((1000.0 / Double(max(10, shortest)) * project.speed).rounded())
        return min(max(source, 5), max(5, base))
    }
}

/// 실행 취소 기록.
struct MotionHistory {
    static let capacity = 50
    private(set) var current: MotionProject
    private(set) var baseline: MotionProject
    private var past: [MotionProject] = []
    private var future: [MotionProject] = []

    init(_ initial: MotionProject) {
        current = initial
        baseline = initial
    }

    /// 세션에서 복원한다. 실행 취소 기록은 비어 있다.
    init(current: MotionProject, baseline: MotionProject) {
        self.current = current
        self.baseline = baseline
    }

    var canUndo: Bool { !past.isEmpty }
    var canRedo: Bool { !future.isEmpty }
    var isDirty: Bool { !current.sameContent(as: baseline) }

    mutating func commit(_ next: MotionProject) {
        guard !next.sameContent(as: current) else { return }
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
