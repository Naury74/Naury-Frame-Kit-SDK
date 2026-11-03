import SwiftUI

/// 음성 타임라인. 재생 위치는 가운데 고정이고 내용이 움직인다.
/// 손잡이(구간 > 고른 조각 양 끝 > 고른 겹친 조각 몸통)를 끌거나, 그 밖을 끌어 재생 위치를 옮긴다. 두 손가락으로 확대한다.
struct AudioTimelineView: View {
    let model: AudioEditorModel
    let tall: Bool
    @Environment(\.editorTheme) private var theme
    @Environment(\.strings) private var strings
    @State private var pointsPerSecond: CGFloat?
    @State private var pinchStart: CGFloat?
    @State private var drag: Drag?

    private struct Drag {
        let target: AudioEditorModel.DragTarget?
        let startPosition: Micros
    }

    private static let handleReach: CGFloat = 20
    private static let layerColor = Color(red: 0.18, green: 0.71, blue: 0.49)

    private var mainHeight: CGFloat { tall ? 150 : 84 }
    private var layerHeight: CGFloat { tall ? 72 : 44 }
    private var height: CGFloat { 8 + mainHeight + 8 + layerHeight + 8 }

    var body: some View {
        GeometryReader { geo in
            let width = geo.size.width
            let pps = scale(width)
            Canvas { context, size in draw(&context, size: size, pps: pps) }
                .contentShape(Rectangle())
                .gesture(dragGesture(width: width, pps: pps))
                .simultaneousGesture(MagnifyGesture()
                    .onChanged { value in
                        if pinchStart == nil { pinchStart = pps }
                        pointsPerSecond = min(max((pinchStart ?? pps) * value.magnification, 6), 320)
                    }
                    .onEnded { _ in pinchStart = nil })
                .onTapGesture { location in tap(location, width: width, pps: pps) }
                .accessibilityElement()
                .accessibilityLabel(strings("audio_timeline"))
                .accessibilityValue(clock(Double(model.position) / 1_000_000))
                .accessibilityAdjustableAction { direction in
                    model.scrub(to: model.position + (direction == .increment ? 1_000_000 : -1_000_000))
                }
        }
        .frame(height: height)
    }

    /// 처음에는 전체가 폭의 80%에 들어가게 한다.
    private func scale(_ width: CGFloat) -> CGFloat {
        if let pointsPerSecond { return pointsPerSecond }
        let seconds = max(0.5, Double(model.duration) / 1_000_000)
        return min(max(width * 0.8 / seconds, 6), 320)
    }

    private func x(_ t: Micros, width: CGFloat, pps: CGFloat) -> CGFloat {
        width / 2 + CGFloat(Double(t - model.position) / 1_000_000) * pps
    }

    private var mainLane: ClosedRange<CGFloat> { 8...(8 + mainHeight) }
    private var layerLane: ClosedRange<CGFloat> { (16 + mainHeight)...(16 + mainHeight + layerHeight) }

    // MARK: - 그리기

    private func draw(_ context: inout GraphicsContext, size: CGSize, pps: CGFloat) {
        let width = size.width
        let project = model.project
        let total = project.duration
        let lanes: [(ClosedRange<CGFloat>, AudioRole)] = [(mainLane, .voice), (layerLane, .music)]
        for (lane, role) in lanes {
            let rect = CGRect(x: 0, y: lane.lowerBound, width: width, height: lane.upperBound - lane.lowerBound)
            context.fill(Path(roundedRect: rect, cornerRadius: role == .voice ? 12 : 10), with: .color(theme.surface.opacity(role == .voice ? 1 : 0.6)))
        }
        if project.layerClips.isEmpty && model.tools.contains(.record) {
            context.draw(Text("+ " + strings("audio_add_layer")).font(.system(size: 13)).foregroundStyle(theme.foregroundMuted),
                         at: CGPoint(x: width / 2, y: (layerLane.lowerBound + layerLane.upperBound) / 2))
        }
        for clip in project.clips {
            let lane = clip.role == .voice ? mainLane : layerLane
            let r = clip.outputRange(videoDuration: total)
            guard !r.isEmpty else { continue }
            let x0 = x(r.start, width: width, pps: pps), x1 = x(r.end, width: width, pps: pps)
            guard x1 > 0, x0 < width else { continue }
            let rect = CGRect(x: x0, y: lane.lowerBound, width: max(2, x1 - x0), height: lane.upperBound - lane.lowerBound).insetBy(dx: 1, dy: 0)
            let selected = clip.id == model.selectedClipId
            let color = clip.role == .voice ? theme.accent : Self.layerColor
            context.fill(Path(roundedRect: rect, cornerRadius: 8), with: .color(color.opacity(selected ? 0.95 : 0.75)))
            drawWave(&context, clip: clip, rect: rect, width: width, pps: pps)
            drawFades(&context, clip: clip, range: r, rect: rect, pps: pps)
            let name = model.sources.indices.contains(clip.source) ? model.sources[clip.source].name : ""
            let label = (clip.loop ? "↻ " : "") + (name.isEmpty ? strings(clip.role == .voice ? "audio_lane_voice" : "audio_lane_layer") : name)
            var text = context.resolve(Text(label).font(.system(size: 11, weight: .medium)).foregroundStyle(.white))
            text.shading = .color(.white)
            context.draw(text, in: CGRect(x: rect.minX + 6, y: rect.minY + 3, width: max(0, rect.width - 12), height: 14))
            if selected {
                context.stroke(Path(roundedRect: rect, cornerRadius: 8), with: .color(theme.foreground), lineWidth: 2)
                for hx in [rect.minX, rect.maxX] {
                    context.fill(Path(roundedRect: CGRect(x: hx - 4, y: rect.midY - 14, width: 8, height: 28), cornerRadius: 4), with: .color(theme.foreground))
                }
            }
        }
        if let range = model.range {
            let x0 = x(range.start, width: width, pps: pps), x1 = x(range.end, width: width, pps: pps)
            context.fill(Path(CGRect(x: x0, y: 0, width: x1 - x0, height: size.height)), with: .color(theme.error.opacity(0.22)))
            for hx in [x0, x1] {
                context.fill(Path(roundedRect: CGRect(x: hx - 3, y: 0, width: 6, height: size.height), cornerRadius: 3), with: .color(theme.error))
            }
        }
        // 결과 밖은 흐리게.
        let start = x(0, width: width, pps: pps), end = x(total, width: width, pps: pps)
        if start > 0 { context.fill(Path(CGRect(x: 0, y: 0, width: start, height: size.height)), with: .color(theme.background.opacity(0.6))) }
        if end < width { context.fill(Path(CGRect(x: end, y: 0, width: width - end, height: size.height)), with: .color(theme.background.opacity(0.6))) }
        let mid = width / 2
        context.fill(Path(CGRect(x: mid - 2, y: 0, width: 4, height: size.height)), with: .color(theme.background.opacity(0.5)))
        context.fill(Path(CGRect(x: mid - 1, y: 0, width: 2, height: size.height)), with: .color(theme.foreground))
        context.fill(Path(ellipseIn: CGRect(x: mid - 4, y: 0, width: 8, height: 8)), with: .color(theme.foreground))
    }

    private func drawWave(_ context: inout GraphicsContext, clip: AudioClipState, rect: CGRect, width: CGFloat, pps: CGFloat) {
        guard let wave = model.waveforms[clip.source], !wave.peaks.isEmpty else { return }
        // 위쪽 이름 자리를 비운다.
        let area = CGRect(x: rect.minX, y: rect.minY + 12, width: rect.width, height: rect.height - 14)
        let path = wave.bars(for: clip, in: area, visible: 0...width) { bx in
            model.position + Micros(Double((bx - width / 2) / pps) * 1_000_000)
        }
        context.fill(path, with: .color(.white.opacity(0.7)))
    }

    private func drawFades(_ context: inout GraphicsContext, clip: AudioClipState, range: TimeRangeUs, rect: CGRect, pps: CGFloat) {
        let fadeIn = CGFloat(Double(min(clip.fadeIn, range.duration / 2)) / 1_000_000) * pps
        let fadeOut = CGFloat(Double(min(clip.fadeOut, range.duration / 2)) / 1_000_000) * pps
        if fadeIn > 1 {
            var p = Path()
            p.move(to: CGPoint(x: rect.minX, y: rect.minY)); p.addLine(to: CGPoint(x: rect.minX + fadeIn, y: rect.minY))
            p.addLine(to: CGPoint(x: rect.minX, y: rect.maxY)); p.closeSubpath()
            context.fill(p, with: .color(.black.opacity(0.3)))
        }
        if fadeOut > 1 {
            var p = Path()
            p.move(to: CGPoint(x: rect.maxX, y: rect.minY)); p.addLine(to: CGPoint(x: rect.maxX - fadeOut, y: rect.minY))
            p.addLine(to: CGPoint(x: rect.maxX, y: rect.maxY)); p.closeSubpath()
            context.fill(p, with: .color(.black.opacity(0.3)))
        }
    }

    // MARK: - 손동작

    private func target(at point: CGPoint, width: CGFloat, pps: CGFloat) -> AudioEditorModel.DragTarget? {
        if let range = model.range {
            if abs(point.x - x(range.start, width: width, pps: pps)) <= Self.handleReach { return .rangeStart }
            if abs(point.x - x(range.end, width: width, pps: pps)) <= Self.handleReach { return .rangeEnd }
        }
        guard let clip = model.selectedClip else { return nil }
        let lane = clip.role == .voice ? mainLane : layerLane
        guard lane.contains(point.y) else { return nil }
        let r = model.project.range(of: clip)
        let x0 = x(r.start, width: width, pps: pps), x1 = x(r.end, width: width, pps: pps)
        let reach = min(Self.handleReach, (x1 - x0) / 3)
        if abs(point.x - x0) <= reach { return .clipStart(clip.id) }
        if abs(point.x - x1) <= reach { return .clipEnd(clip.id) }
        if clip.role != .voice, point.x > x0, point.x < x1 { return .move(clip.id) }
        return nil
    }

    private func dragGesture(width: CGFloat, pps: CGFloat) -> some Gesture {
        DragGesture(minimumDistance: 6)
            .onChanged { g in
                if drag == nil {
                    let found = target(at: g.startLocation, width: width, pps: pps)
                    drag = Drag(target: found, startPosition: model.position)
                    if let found { model.beginDrag(found) } else { model.pause() }
                }
                guard let drag else { return }
                let delta = Micros(Double(g.translation.width / pps) * 1_000_000)
                if let target = drag.target {
                    model.drag(target, by: delta)
                } else {
                    // 왼쪽으로 끌면 앞으로 간다.
                    model.scrub(to: drag.startPosition - delta)
                }
            }
            .onEnded { _ in
                if drag?.target != nil { model.endDrag() }
                drag = nil
            }
    }

    private func tap(_ point: CGPoint, width: CGFloat, pps: CGFloat) {
        let total = model.project.duration
        for (lane, isVoice) in [(mainLane, true), (layerLane, false)] where lane.contains(point.y) {
            let hit = model.project.clips.last { clip in
                guard (clip.role == .voice) == isVoice else { return false }
                let r = clip.outputRange(videoDuration: total)
                return point.x >= x(r.start, width: width, pps: pps) && point.x <= x(r.end, width: width, pps: pps)
            }
            if let hit {
                model.selectClip(hit.id)
            } else if !isVoice && model.project.layerClips.isEmpty {
                model.openRecordSheet(.overlay)
            } else {
                model.clearSelection()
            }
            return
        }
    }
}
