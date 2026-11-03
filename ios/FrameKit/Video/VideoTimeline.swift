import AVFoundation
import SwiftUI

/// 시간 표시. 1시간 미만은 m:ss.d, 넘으면 h:mm:ss.
func formatTime(_ t: Micros) -> String {
    let total = Double(max(0, t)) / 1_000_000
    if total >= 3600 {
        let s = Int(total)
        return String(format: "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
    }
    let tenths = Int((total * 10).rounded(.down))
    return String(format: "%d:%02d.%d", tenths / 600, tenths / 10 % 60, tenths % 10)
}

/// 재생 위치가 가운데 고정되고 내용이 그 아래로 지나가는 트랙 타임라인.
/// 영상 줄(작은 그림), 원본 소리 줄을 그린다. 끌어서 이동, 두 손가락으로 확대, 탭해서 클립 선택.
struct TrackTimeline: View {
    let model: VideoEditorModel
    @Environment(\.editorTheme) private var theme
    @Environment(\.strings) private var strings
    @Environment(\.haptics) private var haptics
    @State private var pointsPerSecond: CGFloat = 0
    @State private var pinchStart: CGFloat?
    @State private var dragStart: Micros?
    @State private var trimDrag: (start: Bool, origin: Micros)?
    @State private var tiles: [String: CGImage] = [:]
    @State private var loading: Set<String> = []

    static let videoLane: CGFloat = 52
    static let audioLane: CGFloat = 20
    static let gap: CGFloat = 6

    var body: some View {
        GeometryReader { geo in
            let pps = pointsPerSecond > 0 ? pointsPerSecond : fitScale(width: geo.size.width)
            let edit = model.displayed
            let showAudio = edit.clips.contains { model.sources.indices.contains($0.source) && model.sources[$0.source].hasAudio }
            Canvas { context, size in
                draw(context: context, size: size, pps: pps, edit: edit, showAudio: showAudio)
            }
            .contentShape(Rectangle())
            .gesture(scrub(pps: pps, width: geo.size.width))
            .simultaneousGesture(zoom(pps: pps))
            .onTapGesture { location in tap(location, pps: pps, width: geo.size.width) }
            .onAppear { requestTiles(width: geo.size.width, pps: pps) }
            .onChange(of: model.position) { _, _ in requestTiles(width: geo.size.width, pps: pps) }
            .onChange(of: edit.clips) { _, _ in requestTiles(width: geo.size.width, pps: pps) }
            .accessibilityElement()
            .accessibilityLabel(strings("timeline"))
            .accessibilityValue(strings("time_position", formatTime(model.position), formatTime(model.duration)))
            .accessibilityAdjustableAction { direction in
                model.seek(to: model.position + (direction == .increment ? 1_000_000 : -1_000_000))
            }
        }
        .frame(height: Self.videoLane + Self.gap + Self.audioLane + 8)
    }

    /// 처음에는 전체 길이가 폭의 80%가 되게 한다(초당 8~240pt).
    private func fitScale(width: CGFloat) -> CGFloat {
        let seconds = max(0.1, Double(model.duration) / 1_000_000)
        return min(240, max(8, width * 0.8 / seconds))
    }

    private func x(_ t: Micros, width: CGFloat, pps: CGFloat) -> CGFloat {
        width / 2 + CGFloat(Double(t - model.position) / 1_000_000) * pps
    }

    private func draw(context: GraphicsContext, size: CGSize, pps: CGFloat, edit: VideoEdit, showAudio: Bool) {
        let starts = edit.clipStarts
        let tileWidth = Self.videoLane * 16 / 9
        for (index, clip) in edit.clips.enumerated() {
            let x0 = x(starts[index], width: size.width, pps: pps)
            let x1 = x(starts[index] + clip.outputDuration, width: size.width, pps: pps)
            guard x1 > 0, x0 < size.width else { continue }
            let lane = CGRect(x: x0, y: 0, width: x1 - x0, height: Self.videoLane)
            context.fill(Path(roundedRect: lane, cornerRadius: 6), with: .color(theme.raised))
            var tx = x0
            context.drawLayer { layer in
                layer.clip(to: Path(roundedRect: lane, cornerRadius: 6))
                while tx < min(x1, size.width) {
                    if tx + tileWidth > 0, let image = tiles[tileKey(clip: clip, outputOffset: tx - x0, pps: pps)] {
                        layer.draw(Image(decorative: image, scale: 1).resizable(), in: CGRect(x: tx, y: 0, width: tileWidth, height: Self.videoLane))
                    }
                    tx += tileWidth
                }
            }
            if clip.id == model.selectedClipId && (edit.clips.count > 1 || canTrimHere) {
                context.stroke(Path(roundedRect: lane.insetBy(dx: 1.25, dy: 1.25), cornerRadius: 6), with: .color(theme.accent), lineWidth: 2.5)
                if canTrimHere {
                    for hx in [x0, x1 - 7] {
                        context.fill(Path(roundedRect: CGRect(x: hx, y: 0, width: 7, height: Self.videoLane), cornerRadius: 3), with: .color(theme.accent))
                    }
                }
            }
            if showAudio {
                let audioRect = CGRect(x: x0, y: Self.videoLane + Self.gap, width: x1 - x0, height: Self.audioLane)
                let hasAudio = model.sources[clip.source].hasAudio
                let color = !hasAudio || clip.muted ? Color(argb: 0xFF55_555E) : Color(argb: 0xFF4C_7DFF).opacity(0.7)
                context.fill(Path(roundedRect: audioRect, cornerRadius: 4), with: .color(color))
            }
            // 클립 사이 경계.
            if index > 0 {
                context.fill(Path(CGRect(x: x0 - 1, y: 0, width: 2, height: Self.videoLane)), with: .color(theme.background))
            }
        }
        // 0 앞과 끝 뒤는 흐리게.
        let start = x(0, width: size.width, pps: pps), end = x(edit.duration, width: size.width, pps: pps)
        if start > 0 { context.fill(Path(CGRect(x: 0, y: 0, width: start, height: size.height)), with: .color(theme.background.opacity(0.6))) }
        if end < size.width {
            context.fill(Path(CGRect(x: end, y: 0, width: size.width - end, height: size.height)), with: .color(theme.background.opacity(0.6)))
        }
        if showAudio {
            let label = context.resolve(Text(strings("lane_original")).font(.system(size: 10)).foregroundColor(.white))
            let labelSize = label.measure(in: size)
            let rect = CGRect(x: 4, y: Self.videoLane + Self.gap + 3, width: labelSize.width + 8, height: Self.audioLane - 6)
            context.fill(Path(roundedRect: rect, cornerRadius: 4), with: .color(.black.opacity(0.55)))
            context.draw(label, at: CGPoint(x: rect.midX, y: rect.midY))
        }
        // 재생 위치.
        let mid = size.width / 2
        context.fill(Path(CGRect(x: mid - 2, y: -4, width: 4, height: size.height + 8)), with: .color(.black))
        context.fill(Path(CGRect(x: mid - 1, y: -4, width: 2, height: size.height + 8)), with: .color(.white))
    }

    private var canTrimHere: Bool { model.activeTool == nil && model.tools.contains(.trim) && model.export == nil }

    private func tileKey(clip: VideoClipState, outputOffset: CGFloat, pps: CGFloat) -> String {
        let sourceTime = clip.sourceRange.start + Micros(Double(outputOffset / pps) * clip.speed * 1_000_000)
        return "\(clip.source)/\(sourceTime / 250_000 * 250_000)"
    }

    private func requestTiles(width: CGFloat, pps: CGFloat) {
        guard let thumbnailer = model.thumbnailer else { return }
        let edit = model.displayed
        let starts = edit.clipStarts
        let tileWidth = Self.videoLane * 16 / 9
        var wanted: [(String, Int, Micros)] = []
        for (index, clip) in edit.clips.enumerated() {
            let x0 = x(starts[index], width: width, pps: pps), x1 = x(starts[index] + clip.outputDuration, width: width, pps: pps)
            var tx = x0
            while tx < min(x1, width) {
                if tx + tileWidth > 0 {
                    let key = tileKey(clip: clip, outputOffset: tx - x0, pps: pps)
                    if tiles[key] == nil && !loading.contains(key) {
                        let sourceTime = clip.sourceRange.start + Micros(Double((tx - x0) / pps) * clip.speed * 1_000_000)
                        wanted.append((key, clip.source, sourceTime))
                    }
                }
                tx += tileWidth
            }
        }
        for (key, source, time) in wanted.prefix(24) {
            loading.insert(key)
            Task {
                let image = await thumbnailer.image(source: source, at: time)
                if tiles.count > 120 { tiles.removeAll() }
                tiles[key] = image
                loading.remove(key)
            }
        }
    }

    private func scrub(pps: CGFloat, width: CGFloat) -> some Gesture {
        DragGesture(minimumDistance: 2)
            .onChanged { g in
                if dragStart == nil {
                    dragStart = model.position
                    // 고른 클립 가장자리 손잡이(18pt)를 잡으면 그 자리에서 구간을 줄인다.
                    if canTrimHere, let index = model.selectedIndex {
                        let start = model.displayed.clipStarts[index]
                        let x0 = x(start, width: width, pps: pps)
                        let x1 = x(start + model.displayed.clips[index].outputDuration, width: width, pps: pps)
                        if g.startLocation.y < Self.videoLane {
                            if abs(g.startLocation.x - x0) < 18 { trimDrag = (true, model.displayed.clips[index].sourceRange.start) }
                            else if abs(g.startLocation.x - x1) < 18 { trimDrag = (false, model.displayed.clips[index].sourceRange.end) }
                        }
                    }
                    if trimDrag == nil { model.pause() }
                }
                if let trim = trimDrag, let clip = model.selectedClip {
                    let delta = Micros(Double(g.translation.width / pps) * clip.speed * 1_000_000)
                    model.dragTrim(start: trim.start, to: trim.origin + delta)
                    return
                }
                guard let dragStart else { return }
                let before = model.displayed.locate(model.position)?.index
                model.seek(to: dragStart - Micros(Double(g.translation.width / pps) * 1_000_000), scrubbing: true)
                if before != model.displayed.locate(model.position)?.index { haptics.tick() }
            }
            .onEnded { _ in
                if trimDrag != nil {
                    model.endTrim()
                } else {
                    model.selectClip(at: model.position)
                }
                trimDrag = nil
                dragStart = nil
            }
    }

    private func zoom(pps: CGFloat) -> some Gesture {
        MagnifyGesture()
            .onChanged { value in
                if pinchStart == nil { pinchStart = pps }
                pointsPerSecond = min(240, max(8, (pinchStart ?? pps) * value.magnification))
            }
            .onEnded { _ in pinchStart = nil }
    }

    private func tap(_ location: CGPoint, pps: CGFloat, width: CGFloat) {
        let t = model.position + Micros(Double((location.x - width / 2) / pps) * 1_000_000)
        guard t >= 0, t < model.duration else { return }
        model.selectClip(at: t)
        haptics.selection()
    }
}

/// 구간 도구: 고른 클립 원본 전체 위에서 남길 구간을 고른다.
struct TrimStrip: View {
    let model: VideoEditorModel
    @Environment(\.editorTheme) private var theme
    @Environment(\.strings) private var strings
    @State private var frames: [Int: CGImage] = [:]
    @State private var drag: (start: Bool, origin: Micros)?

    var body: some View {
        GeometryReader { geo in
            let inset: CGFloat = 20
            let width = geo.size.width - inset * 2
            if let clip = model.selectedClip, let source = model.selectedSource {
                let total = Double(source.duration)
                let x0 = inset + CGFloat(Double(clip.sourceRange.start) / total) * width
                let x1 = inset + CGFloat(Double(clip.sourceRange.end) / total) * width
                ZStack(alignment: .topLeading) {
                    HStack(spacing: 0) {
                        ForEach(0..<12, id: \.self) { i in
                            Group {
                                if let image = frames[i] { Image(decorative: image, scale: 1).resizable().scaledToFill() } else { theme.raised }
                            }
                            .frame(width: width / 12, height: 56).clipped()
                        }
                    }
                    .offset(x: inset)
                    Path { $0.addRect(CGRect(x: inset, y: 0, width: x0 - inset, height: 56)); $0.addRect(CGRect(x: x1, y: 0, width: inset + width - x1, height: 56)) }
                        .fill(Color.black.opacity(0.6))
                    RoundedRectangle(cornerRadius: 4).stroke(theme.accent, lineWidth: 3).frame(width: x1 - x0, height: 56).offset(x: x0)
                    ForEach([true, false], id: \.self) { isStart in
                        RoundedRectangle(cornerRadius: 3).fill(theme.accent).frame(width: 12, height: 56)
                            .frame(width: 48, height: 56).contentShape(Rectangle())
                            .offset(x: (isStart ? x0 : x1) - 24)
                            .gesture(DragGesture(minimumDistance: 0)
                                .onChanged { g in
                                    if drag == nil { drag = (isStart, isStart ? clip.sourceRange.start : clip.sourceRange.end) }
                                    guard let drag else { return }
                                    model.dragTrim(start: drag.start, to: drag.origin + Micros(Double(g.translation.width / width) * total))
                                }
                                .onEnded { _ in drag = nil; model.endTrim() })
                            .accessibilityLabel(strings(isStart ? "trim_start" : "trim_end"))
                    }
                }
                .task(id: clip.source) {
                    for i in 0..<12 {
                        if let image = await source.frame(at: Micros(total * (Double(i) + 0.5) / 12), maxSide: 160) { frames[i] = image.cgImage }
                    }
                }
            }
        }
        .frame(height: 56)
    }
}
