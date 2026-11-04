import AVFoundation
import SwiftUI
internal import FrameKitCore

extension VideoEditorModel: LayerToolHost {
    // MARK: - 공통

    func clearSelection() {
        selectedOverlayId = nil
        selectedMaskId = nil
        selectedAudioId = nil
        originalSelected = false
        muteSelection = nil
    }

    var hasTimelineSelection: Bool { selectedAudioId != nil || originalSelected || selectedMaskId != nil || selectedOverlayId != nil }

    /// [position]부터 [length]. 끝을 넘으면 앞으로 당긴다.
    func defaultRange(_ length: Micros = VideoEditorModel.defaultItemUs) -> TimeRangeUs {
        let total = max(1, duration)
        let len = min(length, total)
        let start = min(max(0, position), total - len)
        return TimeRangeUs(start: start, end: start + len)
    }

    /// 지금 시각에 켜진 요소·워터마크를 미리 보기 크기로 다시 그린다. 켜진 조합이 같으면 그대로 둔다.
    func refreshOverlayImage(force: Bool = false) {
        let edit = displayed
        let active = edit.overlays.filter { $0.range.contains(position) || $0.overlay.id == selectedOverlayId && activeTool != nil }
        let key = active.map { "\($0.overlay)" }.joined(separator: "|") + "\(String(describing: edit.watermark))" + "\(renderSize)"
        guard force || key != overlayKey else { return }
        overlayKey = key
        guard !active.isEmpty || edit.watermark?.isValid == true else { if overlayImage != nil { overlayImage = nil }; return }
        let size = renderSize
        guard let context = BitmapContext.make(width: Int(size.width), height: Int(size.height)) else { return }
        context.translateBy(x: 0, y: size.height)
        context.scaleBy(x: 1, y: -1)
        OverlayRenderer.draw(in: context, canvas: size, overlays: active.map(\.overlay), drawing: [])
        if let watermark = edit.watermark, watermark.isValid {
            UIGraphicsPushContext(context)
            WatermarkRenderer.draw(watermark, canvas: size, context: context)
            UIGraphicsPopContext()
        }
        overlayImage = context.makeImage()
    }

    // MARK: - LayerToolHost

    var renderedCanvas: CGSize { renderSize }
    var canManipulateOverlays: Bool { activeTool == .text || activeTool == .sticker }
    var isDrawingMode: Bool { activeTool == .privacy }
    var liveStrokeIsShape: Bool { true }
    var liveStrokeStyle: (widthRatio: Double, color: Color, square: Bool) { (0.01, Color.white.opacity(0.5), false) }
    var privacyModes: [PrivacySettings.Mode] { [.rectangle, .ellipse] }
    var hasCommittedWatermark: Bool { history.current.watermark != nil }
    var watermarkSuggestions: [String] { request.config.watermark?.suggestions ?? [] }
    var customStickerIds: [String] { StickerAssets.ids }
    var selectedOverlay: Overlay? { displayed.overlays.first { $0.overlay.id == selectedOverlayId }?.overlay }

    func toggleChrome() { if activeTool == nil { togglePlay() } }

    func selectOverlay(at point: CGPoint) {
        let canvas = renderSize
        let active = displayed.overlays.filter { $0.range.contains(position) }.map(\.overlay)
        selectedOverlayId = OverlayRenderer.hitTest(active, point: CGPoint(x: point.x * canvas.width, y: point.y * canvas.height),
                                                    canvas: canvas, slop: 8)
        refreshOverlayImage(force: true)
    }

    func editSelectedText() {
        if activeTool == nil || activeTool == .sticker {
            if activeTool == .sticker { closeAndOpen(.text) } else { selectTool(.text) }
        }
    }

    private func closeAndOpen(_ tool: VideoTool) {
        let keep = selectedOverlayId
        applyTool()
        selectTool(tool)
        selectedOverlayId = keep
    }

    var editingText: (text: String, style: TextStyle)? {
        guard let overlay = selectedOverlay, case .text(let text, let style) = overlay.content else { return nil }
        return (text, style)
    }

    func beginText() {
        if let overlay = selectedOverlay, overlay.isText { return }
        let id = "text-\(UUID().uuidString.prefix(8))"
        let range = defaultRange()
        change(commit: false) { $0.overlays.append(TimedOverlay(overlay: Overlay(id: id, content: .text("", TextStyle())), range: range)) }
        selectedOverlayId = id
    }

    func setText(_ text: String) {
        mutateSelected { overlay in
            if case .text(_, let style) = overlay.content { overlay.content = .text(text, style) }
        }
    }

    func setTextStyle(_ change: (inout TextStyle) -> Void) {
        mutateSelected { overlay in
            if case .text(let text, var style) = overlay.content {
                change(&style)
                overlay.content = .text(text, style)
            }
        }
    }

    func mutateSelected(_ body: (inout Overlay) -> Void) {
        guard let id = selectedOverlayId else { return }
        change(commit: false) { edit in
            guard let index = edit.overlays.firstIndex(where: { $0.overlay.id == id }) else { return }
            body(&edit.overlays[index].overlay)
            let scale = edit.overlays[index].overlay.transform.scale
            edit.overlays[index].overlay.transform.scale = min(OverlayTransform.scaleRange.upperBound, max(OverlayTransform.scaleRange.lowerBound, scale))
        }
    }

    func addSticker(_ assetId: String) {
        let id = "sticker-\(UUID().uuidString.prefix(8))"
        let range = defaultRange()
        change { $0.overlays.append(TimedOverlay(overlay: Overlay(id: id, content: .sticker(assetId: assetId, widthRatio: 0.25)), range: range)) }
        selectedOverlayId = id
    }

    func transformSelected(from start: OverlayTransform, move: CGSize, scale: Double, rotation: Double) {
        let canvas = renderSize
        mutateSelected { overlay in
            var t = start
            t.center.x = min(1, max(0, start.center.x + move.width / canvas.width))
            t.center.y = min(1, max(0, start.center.y + move.height / canvas.height))
            t.scale = start.scale * scale
            var degrees = start.rotationDegrees + rotation
            let snapped = (degrees / 90).rounded() * 90
            if abs(degrees - snapped) < 3 { degrees = snapped }
            t.rotationDegrees = degrees.truncatingRemainder(dividingBy: 360)
            overlay.transform = t
        }
    }

    func deleteSelected() {
        if let id = selectedOverlayId {
            change { $0.overlays.removeAll { $0.overlay.id == id } }
            selectedOverlayId = nil
        } else if let id = selectedMaskId {
            change { $0.masks.removeAll { $0.mask.id == id } }
            selectedMaskId = nil
        }
    }

    func duplicateSelected() {
        guard let id = selectedOverlayId, let timed = displayed.overlays.first(where: { $0.overlay.id == id }) else { return }
        var copy = timed
        copy.overlay.id = "copy-\(UUID().uuidString.prefix(8))"
        copy.overlay.transform.center.x = min(1, copy.overlay.transform.center.x + 0.04)
        copy.overlay.transform.center.y = min(1, copy.overlay.transform.center.y + 0.04)
        change { $0.overlays.append(copy) }
        selectedOverlayId = copy.overlay.id
    }

    /// 고른 요소·가리기의 시작이나 끝을 재생 위치로 옮긴다. 한 프레임 이상은 남긴다.
    func setSelectedEdge(start: Bool) {
        let t = min(max(0, position), duration)
        func moved(_ r: TimeRangeUs) -> TimeRangeUs {
            start ? TimeRangeUs(start: min(t, r.end - Self.frameUs), end: r.end) : TimeRangeUs(start: r.start, end: max(t, r.start + Self.frameUs))
        }
        if let id = selectedOverlayId {
            change { edit in if let i = edit.overlays.firstIndex(where: { $0.overlay.id == id }) { edit.overlays[i].range = moved(edit.overlays[i].range) } }
        } else if let id = selectedMaskId {
            change { edit in if let i = edit.masks.firstIndex(where: { $0.mask.id == id }) { edit.masks[i].range = moved(edit.masks[i].range) } }
        }
    }

    var selectedRange: TimeRangeUs? {
        if let id = selectedOverlayId { return displayed.overlays.first { $0.overlay.id == id }?.range }
        if let id = selectedMaskId { return displayed.masks.first { $0.mask.id == id }?.range }
        return nil
    }

    // MARK: - 가리기

    func strokeBegan(_ point: NPoint) { liveStroke = [point] }

    func strokeMoved(_ point: NPoint) {
        guard var stroke = liveStroke else { return }
        stroke.append(point)
        liveStroke = [stroke.first!, point]
    }

    func strokeEnded() {
        guard let points = liveStroke, let a = points.first, let b = points.last else { return }
        liveStroke = nil
        let rect = NRect(left: min(a.x, b.x), top: min(a.y, b.y), right: max(a.x, b.x), bottom: max(a.y, b.y))
        guard rect.width >= 0.01, rect.height >= 0.01 else { return }
        guard displayed.masks.count < VideoEdit.maxPrivacyMasks else { showNotice("mask_limit"); return }
        let id = "mask-\(UUID().uuidString.prefix(8))"
        let shape: PrivacyMask.Shape = privacy.mode == .ellipse ? .ellipse(rect) : .rectangle(rect)
        let range = defaultRange()
        change { $0.masks.append(TimedMask(mask: PrivacyMask(id: id, shape: shape, effect: privacy.effect), range: range)) }
        selectedMaskId = id
    }

    /// 고른 가리기 상자를 [start]에서 옮기거나(corner `nil`) 모서리로 크기를 바꾼다. 값은 화면 비율(0...1) 단위다.
    func reshapeSelectedMask(from start: NRect, corner: Int?, dx: Double, dy: Double) {
        guard let id = selectedMaskId else { return }
        let minSide = 0.02
        var r = start
        if let corner {
            // 0 왼쪽 위, 1 오른쪽 위, 2 오른쪽 아래, 3 왼쪽 아래.
            if corner == 0 || corner == 3 { r.left = min(max(0, start.left + dx), start.right - minSide) }
            else { r.right = max(min(1, start.right + dx), start.left + minSide) }
            if corner == 0 || corner == 1 { r.top = min(max(0, start.top + dy), start.bottom - minSide) }
            else { r.bottom = max(min(1, start.bottom + dy), start.top + minSide) }
        } else {
            let mx = min(max(dx, -start.left), 1 - start.right), my = min(max(dy, -start.top), 1 - start.bottom)
            r = NRect(left: start.left + mx, top: start.top + my, right: start.right + mx, bottom: start.bottom + my)
        }
        change(commit: false) { edit in
            guard let i = edit.masks.firstIndex(where: { $0.mask.id == id }) else { return }
            switch edit.masks[i].mask.shape {
            case .rectangle: edit.masks[i].mask.shape = .rectangle(r)
            case .ellipse: edit.masks[i].mask.shape = .ellipse(r)
            case .brush: break
            }
        }
    }

    // MARK: - 워터마크

    var watermarkDraft: WatermarkSpec? { displayed.watermark }

    func setWatermark(_ body: (inout WatermarkSpec) -> Void) {
        change(commit: false) { edit in
            var spec = edit.watermark ?? WatermarkSpec(text: "")
            body(&spec)
            spec.text = String(spec.text.replacingOccurrences(of: "\n", with: " ").prefix(WatermarkSpec.maxTextLength))
            edit.watermark = spec
        }
        refreshOverlayImage(force: true)
    }

    func removeWatermark() {
        change(commit: false) { $0.watermark = nil }
        refreshOverlayImage(force: true)
    }

    // MARK: - 음악·음성

    var micDeclared: Bool { Bundle.main.object(forInfoDictionaryKey: "NSMicrophoneUsageDescription") != nil }

    /// 소리 파일을 재생 위치부터 넣는다. 음악은 반복한다.
    func addAudio(url: URL, role: AudioRole, copy: Bool = true) {
        busy = true
        Task {
            do {
                let local = copy ? try await OutputStore.copyToTemporaryInBackground(url, fallbackExtension: "m4a") : url
                let source: AudioSource
                do {
                    source = try await AudioSource.open(url: local, name: url.deletingPathExtension().lastPathComponent)
                } catch {
                    OutputStore.discardTemporary(local)
                    throw error
                }
                audioSources.append(source)
                loadWave(audioSources.count - 1)
                let id = "\(role.rawValue)-\(UUID().uuidString.prefix(8))"
                let start = min(position, max(0, duration - 1_000_000))
                change { edit in
                    edit.audio.append(AudioClipState(id: id, source: self.audioSources.count - 1, sourceRange: TimeRangeUs(start: 0, end: source.duration),
                                                     timelineStart: start, loop: role == .music, role: role))
                }
                busy = false
                clearSelection()
                selectedAudioId = id
            } catch {
                busy = false
                showNotice("add_failed")
            }
        }
    }

    func loadWave(_ index: Int) {
        guard audioSources.indices.contains(index) else { return }
        let source = audioSources[index]
        Task { audioWaves[index] = await Waveform.extract(source) }
    }

    var selectedAudio: AudioClipState? { displayed.audio.first { $0.id == selectedAudioId } }

    func changeSelectedAudio(commit: Bool = true, _ body: (inout AudioClipState) -> Void) {
        guard let id = selectedAudioId else { return }
        change(commit: commit) { edit in if let i = edit.audio.firstIndex(where: { $0.id == id }) { body(&edit.audio[i]) } }
    }

    func deleteSelectedAudio() {
        guard let id = selectedAudioId else { return }
        change { $0.audio.removeAll { $0.id == id } }
        selectedAudioId = nil
    }

    /// 재생 위치에서 소리 조각을 둘로 나눈다(Android AudioEditing.split와 같다).
    func splitSelectedAudio() {
        guard let clip = selectedAudio else { return }
        let range = clip.outputRange(videoDuration: duration)
        let at = position
        guard at - range.start >= 200_000, range.end - at >= 200_000 else { showNotice("split_unavailable"); return }
        let length = clip.sourceRange.duration
        let elapsed = at - clip.timelineStart
        let firstPass = length - clip.startOffset
        let offset: Micros = elapsed < firstPass ? clip.startOffset + elapsed
            : (clip.loop ? (elapsed - firstPass) % length : length - 1)
        var first = clip, second = clip
        first.timelineEnd = at
        first.fadeOut = 0
        second.id = "\(clip.role.rawValue)-\(UUID().uuidString.prefix(8))"
        second.timelineStart = at
        second.startOffset = min(max(0, offset), length - 1)
        second.fadeIn = 0
        change { edit in
            guard let i = edit.audio.firstIndex(where: { $0.id == clip.id }) else { return }
            edit.audio.replaceSubrange(i...i, with: [first, second])
        }
        selectedAudioId = second.id
    }

    /// 고른 소리 조각의 시작이나 끝을 [original]에서 [delta]만큼 옮긴다. 0.2초보다 짧아지지 않는다.
    func trimSelectedAudio(from original: AudioClipState, start: Bool, by delta: Micros) {
        let total = duration
        changeSelectedAudio(commit: false) { clip in
            let range = original.outputRange(videoDuration: total)
            clip = start ? AudioClipEditing.trimStart(original, to: range.start + delta, total: total, min: 200_000)
                : AudioClipEditing.trimEnd(original, to: range.end + delta, total: total, min: 200_000)
        }
    }

    /// 소리 조각을 [delta]만큼 옮긴다(영상 안으로 제한).
    func moveSelectedAudio(from start: Micros, by delta: Micros) {
        changeSelectedAudio(commit: false) { clip in
            let length = clip.outputRange(videoDuration: duration).duration
            let end = clip.timelineEnd.map { $0 - clip.timelineStart }
            clip.timelineStart = min(max(0, start + delta), max(0, duration - min(length, 200_000)))
            if let end { clip.timelineEnd = clip.timelineStart + end }
        }
    }

    // MARK: - 원본 소리 구간

    /// 재생 위치부터 2초(그 클립 안)를 소리 끌 구간으로 고른다.
    func beginMuteSelection() {
        guard let found = displayed.locate(position) else { return }
        let clipEnd = found.clipStart + displayed.clips[found.index].outputDuration
        muteSelection = TimeRangeUs(start: position, end: min(clipEnd, position + 2_000_000))
    }

    func setMuteEdge(start: Bool) {
        guard var selection = muteSelection, let found = displayed.locate(selection.start) else { return }
        let clipStart = found.clipStart, clipEnd = clipStart + displayed.clips[found.index].outputDuration
        let t = min(max(position, clipStart), clipEnd)
        if start { selection.start = min(t, selection.end - 200_000) } else { selection.end = max(t, selection.start + 200_000) }
        muteSelection = selection
    }

    /// 고른 구간을 원본 시간으로 바꿔 클립 음소거 구간에 더한다.
    func applyMuteSelection() {
        guard let selection = muteSelection, let found = displayed.locate(selection.start) else { return }
        let clip = displayed.clips[found.index]
        func source(_ t: Micros) -> Micros { clip.sourceRange.start + Micros((Double(t - found.clipStart) * clip.speed).rounded()) }
        let range = TimeRangeUs(start: source(selection.start), end: source(selection.end))
        change { edit in edit.clips[found.index].mutedRanges = TimeRangeUs.normalize(edit.clips[found.index].mutedRanges + [range]) }
        muteSelection = nil
    }

    var playheadMuted: Bool {
        guard let found = displayed.locate(position) else { return false }
        return displayed.clips[found.index].mutedRanges.contains { $0.contains(found.sourceTime) }
    }

    func restoreMutedAtPlayhead() {
        guard let found = displayed.locate(position) else { return }
        change { edit in edit.clips[found.index].mutedRanges.removeAll { $0.contains(found.sourceTime) } }
    }
}
