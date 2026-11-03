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

/// 타임라인 줄.
enum TrackLane: Hashable {
    case video, original, music, voice, overlay, mask

    var height: CGFloat {
        switch self {
        case .video: 52
        case .original: 20
        case .music, .voice: 26
        case .overlay, .mask: 14
        }
    }

    var labelKey: String {
        switch self {
        case .video: "lane_video"
        case .original: "lane_original"
        case .music: "track_music"
        case .voice: "track_voice"
        case .overlay: "lane_text"
        case .mask: "lane_mask"
        }
    }
}

/// 재생 위치가 가운데 고정되고 내용이 그 아래로 지나가는 트랙 타임라인.
/// 끌어서 이동, 두 손가락으로 확대(초당 8~240pt), 누르면 그 줄의 항목을 고른다.
struct TrackTimeline: View {
    let model: VideoEditorModel
    @Environment(\.editorTheme) private var theme
    @Environment(\.strings) private var strings
    @Environment(\.haptics) private var haptics
    @State private var pointsPerSecond: CGFloat = 0
    @State private var pinchStart: CGFloat?
    @State private var dragStart: Micros?
    @State private var trimDrag: (start: Bool, origin: Micros)?
    @State private var audioDrag: Micros?
    @State private var tiles: [String: CGImage] = [:]
    @State private var loading: Set<String> = []

    static let gap: CGFloat = 6
    static let musicColor = Color(argb: 0xFF2E_B67D)
    static let voiceColor = Color(argb: 0xFFF5_9F00)

    private var lanes: [TrackLane] {
        let edit = model.displayed
        var result: [TrackLane] = [.video]
        if edit.clips.contains(where: { model.sources.indices.contains($0.source) && model.sources[$0.source].hasAudio }) { result.append(.original) }
        let showAdd = model.activeTool == nil && model.tools.contains(.audio)
        if showAdd || edit.audio.contains(where: { $0.role == .music }) { result.append(.music) }
        if showAdd || edit.audio.contains(where: { $0.role == .voice }) { result.append(.voice) }
        if !edit.overlays.isEmpty { result.append(.overlay) }
        if !edit.masks.isEmpty { result.append(.mask) }
        return result
    }

    private func laneTops(_ lanes: [TrackLane]) -> [TrackLane: CGFloat] {
        var y: CGFloat = 0
        var result: [TrackLane: CGFloat] = [:]
        for lane in lanes {
            result[lane] = y
            y += lane.height + Self.gap
        }
        return result
    }

    var body: some View {
        let lanes = lanes
        let height = lanes.reduce(0) { $0 + $1.height + Self.gap }
        GeometryReader { geo in
            let pps = pointsPerSecond > 0 ? pointsPerSecond : fitScale(width: geo.size.width)
            let edit = model.displayed
            let tops = laneTops(lanes)
            Canvas { context, size in
                draw(context: context, size: size, pps: pps, edit: edit, lanes: lanes, tops: tops)
            }
            .contentShape(Rectangle())
            .gesture(scrub(pps: pps, width: geo.size.width, tops: tops))
            .simultaneousGesture(zoom(pps: pps))
            .onTapGesture { location in tap(location, pps: pps, width: geo.size.width, lanes: lanes, tops: tops) }
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
        .frame(height: height)
    }

    private func fitScale(width: CGFloat) -> CGFloat {
        let seconds = max(0.1, Double(model.duration) / 1_000_000)
        return min(240, max(8, width * 0.8 / seconds))
    }

    private func x(_ t: Micros, width: CGFloat, pps: CGFloat) -> CGFloat {
        width / 2 + CGFloat(Double(t - model.position) / 1_000_000) * pps
    }

    private func time(_ x: CGFloat, width: CGFloat, pps: CGFloat) -> Micros {
        model.position + Micros(Double((x - width / 2) / pps) * 1_000_000)
    }

    private func draw(context: GraphicsContext, size: CGSize, pps: CGFloat, edit: VideoEdit, lanes: [TrackLane], tops: [TrackLane: CGFloat]) {
        let starts = edit.clipStarts
        let tileWidth = TrackLane.video.height * 16 / 9
        let videoH = TrackLane.video.height
        for (index, clip) in edit.clips.enumerated() {
            let x0 = x(starts[index], width: size.width, pps: pps)
            let x1 = x(starts[index] + clip.outputDuration, width: size.width, pps: pps)
            guard x1 > 0, x0 < size.width else { continue }
            let lane = CGRect(x: x0, y: 0, width: x1 - x0, height: videoH)
            context.fill(Path(roundedRect: lane, cornerRadius: 6), with: .color(theme.raised))
            context.drawLayer { layer in
                layer.clip(to: Path(roundedRect: lane, cornerRadius: 6))
                var tx = x0
                while tx < min(x1, size.width) {
                    if tx + tileWidth > 0, let image = tiles[tileKey(clip: clip, outputOffset: tx - x0, pps: pps)] {
                        layer.draw(Image(decorative: image, scale: 1).resizable(), in: CGRect(x: tx, y: 0, width: tileWidth, height: videoH))
                    }
                    tx += tileWidth
                }
            }
            if clip.id == model.selectedClipId && !model.hasTimelineSelection && (edit.clips.count > 1 || canTrimHere) {
                context.stroke(Path(roundedRect: lane.insetBy(dx: 1.25, dy: 1.25), cornerRadius: 6), with: .color(theme.accent), lineWidth: 2.5)
                if canTrimHere {
                    for hx in [x0, x1 - 7] {
                        context.fill(Path(roundedRect: CGRect(x: hx, y: 0, width: 7, height: videoH), cornerRadius: 3), with: .color(theme.accent))
                    }
                }
            }
            if index > 0 { context.fill(Path(CGRect(x: x0 - 1, y: 0, width: 2, height: videoH)), with: .color(theme.background)) }
            if let top = tops[.original] {
                let rect = CGRect(x: x0, y: top, width: x1 - x0, height: TrackLane.original.height)
                let hasAudio = model.sources[clip.source].hasAudio
                let color = !hasAudio || clip.muted ? Color(argb: 0xFF55_555E) : Color(argb: 0xFF4C_7DFF).opacity(0.7)
                context.fill(Path(roundedRect: rect, cornerRadius: 4), with: .color(color))
                // 소리를 끈 구간은 회색으로 덮는다.
                for muted in clip.mutedRanges {
                    let s = max(muted.start, clip.sourceRange.start), e = min(muted.end, clip.sourceRange.end)
                    guard e > s else { continue }
                    let ms = starts[index] + Micros(Double(s - clip.sourceRange.start) / clip.speed)
                    let me = starts[index] + Micros(Double(e - clip.sourceRange.start) / clip.speed)
                    let mx0 = x(ms, width: size.width, pps: pps), mx1 = x(me, width: size.width, pps: pps)
                    context.fill(Path(CGRect(x: mx0, y: top, width: mx1 - mx0, height: rect.height)), with: .color(Color(argb: 0xFF55_555E)))
                }
                if model.originalSelected { context.stroke(Path(roundedRect: rect, cornerRadius: 4), with: .color(theme.accent), lineWidth: 2) }
            }
        }
        if let top = tops[.original], let selection = model.muteSelection {
            let rect = CGRect(x: x(selection.start, width: size.width, pps: pps), y: top,
                              width: x(selection.end, width: size.width, pps: pps) - x(selection.start, width: size.width, pps: pps),
                              height: TrackLane.original.height)
            context.fill(Path(rect), with: .color(Color.red.opacity(0.35)))
            context.stroke(Path(rect), with: .color(.red), lineWidth: 2)
        }
        for role in [AudioRole.music, .voice] {
            guard let top = tops[role == .music ? .music : .voice] else { continue }
            let lane: TrackLane = role == .music ? .music : .voice
            let pieces = edit.audio.filter { $0.role == role }
            let color = role == .music ? Self.musicColor : Self.voiceColor
            if pieces.isEmpty {
                let rect = CGRect(x: max(0, x(0, width: size.width, pps: pps)), y: top,
                                  width: max(0, min(size.width, x(edit.duration, width: size.width, pps: pps)) - max(0, x(0, width: size.width, pps: pps))),
                                  height: lane.height)
                context.fill(Path(roundedRect: rect, cornerRadius: 4), with: .color(color.opacity(0.12)))
                let text = context.resolve(Text("+ " + strings(role == .music ? "track_music_add" : "track_voice_add")).font(.system(size: 11, weight: .medium))
                    .foregroundColor(color))
                context.draw(text, at: CGPoint(x: rect.midX, y: rect.midY))
            }
            for piece in pieces {
                let range = piece.outputRange(videoDuration: edit.duration)
                let rect = CGRect(x: x(range.start, width: size.width, pps: pps), y: top,
                                  width: x(range.end, width: size.width, pps: pps) - x(range.start, width: size.width, pps: pps), height: lane.height)
                context.fill(Path(roundedRect: rect, cornerRadius: 4), with: .color(color))
                if piece.id == model.selectedAudioId { context.stroke(Path(roundedRect: rect, cornerRadius: 4), with: .color(.white), lineWidth: 2) }
                let name = model.audioSources.indices.contains(piece.source) ? model.audioSources[piece.source].name : strings("track_untitled")
                let label = context.resolve(Text((piece.loop ? "↻ " : "") + name).font(.system(size: 11, weight: .medium)).foregroundColor(.white))
                context.drawLayer { layer in
                    layer.clip(to: Path(rect))
                    layer.draw(label, at: CGPoint(x: rect.minX + 6, y: rect.midY), anchor: .leading)
                }
            }
        }
        if let top = tops[.overlay] {
            for timed in edit.overlays {
                let rect = CGRect(x: x(timed.range.start, width: size.width, pps: pps), y: top,
                                  width: x(timed.range.end, width: size.width, pps: pps) - x(timed.range.start, width: size.width, pps: pps), height: 14)
                context.fill(Path(roundedRect: rect, cornerRadius: 7), with: .color(theme.accent.opacity(0.75)))
                if timed.overlay.id == model.selectedOverlayId { context.stroke(Path(roundedRect: rect, cornerRadius: 7), with: .color(.white), lineWidth: 2) }
            }
        }
        if let top = tops[.mask] {
            for timed in edit.masks {
                let rect = CGRect(x: x(timed.range.start, width: size.width, pps: pps), y: top,
                                  width: x(timed.range.end, width: size.width, pps: pps) - x(timed.range.start, width: size.width, pps: pps), height: 14)
                context.fill(Path(roundedRect: rect, cornerRadius: 7), with: .color(Color(argb: 0xFF8E_8E99)))
                if timed.mask.id == model.selectedMaskId { context.stroke(Path(roundedRect: rect, cornerRadius: 7), with: .color(.white), lineWidth: 2) }
            }
        }
        let start = x(0, width: size.width, pps: pps), end = x(edit.duration, width: size.width, pps: pps)
        if start > 0 { context.fill(Path(CGRect(x: 0, y: 0, width: start, height: size.height)), with: .color(theme.background.opacity(0.6))) }
        if end < size.width {
            context.fill(Path(CGRect(x: end, y: 0, width: size.width - end, height: size.height)), with: .color(theme.background.opacity(0.6)))
        }
        for lane in lanes where lane != .video && lane.height >= 20 {
            guard let top = tops[lane] else { continue }
            let label = context.resolve(Text(strings(lane.labelKey)).font(.system(size: 10)).foregroundColor(.white))
            let labelSize = label.measure(in: size)
            let rect = CGRect(x: 4, y: top + (lane.height - 14) / 2, width: labelSize.width + 8, height: 14)
            context.fill(Path(roundedRect: rect, cornerRadius: 4), with: .color(.black.opacity(0.55)))
            context.draw(label, at: CGPoint(x: rect.midX, y: rect.midY))
        }
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
        let tileWidth = TrackLane.video.height * 16 / 9
        var wanted: [(String, Int, Micros)] = []
        for (index, clip) in edit.clips.enumerated() {
            let x0 = x(starts[index], width: width, pps: pps), x1 = x(starts[index] + clip.outputDuration, width: width, pps: pps)
            var tx = x0
            while tx < min(x1, width) {
                if tx + tileWidth > 0 {
                    let key = tileKey(clip: clip, outputOffset: tx - x0, pps: pps)
                    if tiles[key] == nil && !loading.contains(key) {
                        wanted.append((key, clip.source, clip.sourceRange.start + Micros(Double((tx - x0) / pps) * clip.speed * 1_000_000)))
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

    private func scrub(pps: CGFloat, width: CGFloat, tops: [TrackLane: CGFloat]) -> some Gesture {
        DragGesture(minimumDistance: 2)
            .onChanged { g in
                if dragStart == nil {
                    dragStart = model.position
                    if canTrimHere, let index = model.selectedIndex, g.startLocation.y < TrackLane.video.height {
                        let start = model.displayed.clipStarts[index]
                        let x0 = x(start, width: width, pps: pps)
                        let x1 = x(start + model.displayed.clips[index].outputDuration, width: width, pps: pps)
                        if abs(g.startLocation.x - x0) < 18 { trimDrag = (true, model.displayed.clips[index].sourceRange.start) }
                        else if abs(g.startLocation.x - x1) < 18 { trimDrag = (false, model.displayed.clips[index].sourceRange.end) }
                    }
                    // 고른 소리 조각 위에서 끌면 조각을 옮긴다.
                    if let audio = model.selectedAudio, let top = tops[audio.role == .music ? .music : .voice],
                       g.startLocation.y >= top, g.startLocation.y <= top + 26 {
                        let range = audio.outputRange(videoDuration: model.duration)
                        let t = time(g.startLocation.x, width: width, pps: pps)
                        if range.contains(t) { audioDrag = audio.timelineStart }
                    }
                    if trimDrag == nil && audioDrag == nil { model.pause() }
                }
                if let trim = trimDrag, let clip = model.selectedClip {
                    model.dragTrim(start: trim.start, to: trim.origin + Micros(Double(g.translation.width / pps) * clip.speed * 1_000_000))
                    return
                }
                if let origin = audioDrag {
                    model.moveSelectedAudio(from: origin, by: Micros(Double(g.translation.width / pps) * 1_000_000))
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
                } else if audioDrag != nil {
                    model.commitGesture()
                } else if !model.hasTimelineSelection {
                    model.selectClip(at: model.position)
                }
                trimDrag = nil
                audioDrag = nil
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

    private func tap(_ location: CGPoint, pps: CGFloat, width: CGFloat, lanes: [TrackLane], tops: [TrackLane: CGFloat]) {
        let t = time(location.x, width: width, pps: pps)
        guard let lane = lanes.first(where: { lane in (tops[lane] ?? 0) <= location.y && location.y <= (tops[lane] ?? 0) + lane.height + Self.gap }) else { return }
        guard model.activeTool == nil || lane == .video else { return }
        haptics.selection()
        let edit = model.displayed
        switch lane {
        case .video:
            guard t >= 0, t < model.duration else { return }
            model.clearSelection()
            model.selectClip(at: t)
        case .original:
            model.clearSelection()
            model.selectClip(at: max(0, min(t, model.duration - 1)))
            model.originalSelected = true
        case .music, .voice:
            let role: AudioRole = lane == .music ? .music : .voice
            if let piece = edit.audio.first(where: { $0.role == role && $0.outputRange(videoDuration: edit.duration).contains(t) }) {
                model.clearSelection()
                model.selectedAudioId = piece.id
            } else if role == .voice && model.micDeclared {
                model.showRecorder = true
            } else {
                model.musicPickerRole = role
                model.showMusicPicker = true
            }
        case .overlay:
            if let hit = edit.overlays.last(where: { $0.range.contains(t) }) {
                model.clearSelection()
                model.selectedOverlayId = hit.overlay.id
            }
        case .mask:
            if let hit = edit.masks.last(where: { $0.range.contains(t) }) {
                model.clearSelection()
                model.selectedMaskId = hit.mask.id
            }
        }
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
