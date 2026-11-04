import SwiftUI

/// 움직이는 이미지 편집 화면 뿌리.
struct MotionEditorRoot: View {
    @ObservedObject var model: MotionEditorModel
    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        let ui = model.request.ui
        let theme = EditorTheme.make(ui, systemDark: colorScheme == .dark)
        let strings = Strings(localeIdentifier: ui.localeIdentifier)
        Group {
            switch model.phase {
            case .awaitingSource:
                PhotoPicker(limit: model.initialPickLimit) { model.picked($0) }.ignoresSafeArea()
            case .loading:
                ProgressView().controlSize(.large).tint(theme.accent).frame(maxWidth: .infinity, maxHeight: .infinity)
                    .background(theme.background.ignoresSafeArea())
            case .failed(let error):
                VStack(spacing: 16) {
                    Image(systemName: "exclamationmark.triangle").font(.system(size: 36)).foregroundStyle(theme.error)
                    Text(strings(errorKey(error.code))).multilineTextAlignment(.center).padding(.horizontal, 32).foregroundStyle(theme.foreground)
                    EditorButton(title: strings("action_close"), kind: .tonal) { model.finish(.failure(error)) }
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .background(theme.background.ignoresSafeArea())
            case .ready:
                MotionEditorScreen(model: model)
            }
        }
        .environment(\.editorTheme, theme)
        .environment(\.strings, strings)
        .environment(\.haptics, Haptics(enabled: ui.enableHaptics))
        .environment(\.editorFontId, ui.uiFontId)
        .preferredColorScheme(ui.themeMode == .system ? nil : (theme.isDark ? .dark : .light))
        .tint(theme.accent)
        .dynamicTypeSize(...DynamicTypeSize.accessibility1)
        .onAppear { model.start() }
    }
}

struct MotionEditorScreen: View {
    @ObservedObject var model: MotionEditorModel
    @Environment(\.editorTheme) private var theme
    @Environment(\.strings) private var strings
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        GeometryReader { geo in
            let size = geo.size
            let side = size.width >= 840 || (size.width > size.height && size.width >= 600)
            Group {
                if side {
                    HStack(spacing: 0) {
                        VStack(spacing: 0) { canvas; controls }
                        VStack(spacing: 0) {
                            topBar
                            toolArea(wide: true).frame(maxHeight: .infinity, alignment: .top)
                        }
                        .frame(width: 360)
                    }
                } else {
                    VStack(spacing: 0) {
                        topBar.frame(maxWidth: size.width >= 600 ? 640 : .infinity)
                        canvas
                        VStack(spacing: 0) {
                            controls
                            toolArea(wide: false)
                        }
                        .frame(maxWidth: size.width >= 600 ? 640 : .infinity)
                        .frame(maxHeight: size.height * 0.55, alignment: .bottom)
                        .fixedSize(horizontal: false, vertical: true)
                    }
                }
            }
            .frame(width: size.width, height: size.height)
        }
        .background(theme.background.ignoresSafeArea())
        .overlay { exportOverlay }
        .alert(strings("discard_title"), isPresented: $model.showDiscardDialog) {
            Button(strings("action_keep_editing"), role: .cancel) {}
            Button(strings("action_discard"), role: .destructive) { model.discardAndClose() }
        } message: { Text(strings("discard_message")) }
        .alert(strings("draft_title", toolName), isPresented: $model.showDraftDialog) {
            Button(strings("draft_apply", toolName)) { model.applyTool() }
            Button(strings("draft_discard", toolName), role: .destructive) { model.discardTool() }
            Button(strings("action_keep_editing"), role: .cancel) {}
        } message: { Text(strings("draft_message", toolName)) }
        .alert(strings("export_failed_title"), isPresented: exportFailed) {
            if exportError?.recoverable == true { Button(strings("action_retry")) { model.retryExport() } }
            Button(strings("action_keep_editing"), role: .cancel) { model.dismissExportError() }
        } message: { Text(exportError.map { strings(errorKey($0.code)) } ?? "") }
        .sheet(isPresented: $model.showSaveSheet) { MotionSaveSheet(model: model) }
        .sheet(isPresented: $model.showAddPicker) {
            PhotoPicker(limit: max(1, model.maxImageCount - model.displayed.slides.count)) { model.picked($0, append: true) }.ignoresSafeArea()
        }
        .sheet(isPresented: $model.showMusicPicker) {
            AudioFilePicker { url in
                model.showMusicPicker = false
                if let url { model.addAudio(url: url, role: model.musicPickerRole) }
                model.musicPickerRole = .music
            }
            .ignoresSafeArea()
        }
        .sheet(isPresented: $model.showRecorder) {
            VoiceRecorderSheet { url in
                model.showRecorder = false
                if let url { model.addAudio(url: url, role: .voice, copy: false) }
            }
        }
    }

    private var toolName: String { model.activeTool.map { strings($0.labelKey) } ?? "" }
    private var exportError: EditorError? { if case .failed(let e) = model.export { e } else { nil } }
    private var exportFailed: Binding<Bool> {
        Binding(get: { exportError != nil }, set: { if !$0 { model.dismissExportError() } })
    }

    // MARK: - 상단 바

    private var topBar: some View {
        ZStack {
            if let tool = model.activeTool {
                Text(strings(tool.labelKey)).scaledFont(17, weight: .semibold).foregroundStyle(theme.foreground)
                    .lineLimit(1).padding(.horizontal, 100).accessibilityAddTraits(.isHeader)
            }
            HStack {
                Button { model.activeTool == nil ? model.requestClose() : model.onBack() } label: {
                    Image(systemName: model.activeTool == nil ? "xmark" : "chevron.left")
                        .font(.system(size: model.activeTool == nil ? 17 : 20, weight: .semibold)).foregroundStyle(theme.foreground)
                        .frame(width: 42, height: 42).editorGlass(Circle(), theme: theme)
                        .frame(minWidth: 48, minHeight: 48).contentShape(Rectangle())
                }
                .buttonStyle(PressFadeStyle())
                .accessibilityLabel(strings(model.activeTool == nil ? "action_close" : "action_back"))
                Spacer()
                // 도구 화면에는 적용 버튼이 있어 저장은 숨긴다.
                if model.activeTool == nil {
                Button { model.requestSave() } label: {
                    Text(strings("action_save")).scaledFont(16, weight: .semibold).foregroundStyle(theme.onAccent)
                        .padding(.horizontal, 18).frame(height: 42)
                        .editorGlass(Capsule(), theme: theme, tint: theme.accent)
                        .frame(minHeight: 48).contentShape(Rectangle())
                }
                .buttonStyle(PressFadeStyle())
                .disabled(model.export != nil)
                }
            }
        }
        .padding(.horizontal, 12)
        .frame(height: 60)
    }

    // MARK: - 화면

    private var canvas: some View {
        ZStack {
            theme.canvas
            GeometryReader { geo in
                let rect = fit(model.renderSize, in: CGRect(origin: .zero, size: geo.size).insetBy(dx: 16, dy: 16))
                ZStack {
                    PlaybackReader(clock: model.playback) {
                        if let frame = model.frame {
                            Image(decorative: frame, scale: 1).resizable().interpolation(.high)
                                .frame(width: rect.width, height: rect.height).position(x: rect.midX, y: rect.midY)
                                .accessibilityHidden(true)
                        }
                    }
                    if model.activeTool == .text || model.activeTool == .sticker {
                        CanvasInteraction(model: model, rect: rect, zoom: 1)
                    } else {
                        Color.clear.contentShape(Rectangle())
                            .onLocatedTap { location in
                                guard model.activeTool == nil else { return }
                                model.selectOverlayFromIdle(at: CGPoint(x: (location.x - rect.minX) / max(1, rect.width),
                                                                        y: (location.y - rect.minY) / max(1, rect.height)))
                            }
                            .accessibilityLabel(strings(model.isPlaying ? "motion_pause" : "motion_play"))
                            .accessibilityAddTraits(.isButton)
                            .accessibilityAction { model.togglePlay() }
                    }
                }
            }
        }
        .overlay(alignment: .top) {
            if model.activeTool != .text && model.request.config.allowUndo {
                HStack(spacing: 0) {
                    IconButton(icon: Icons.image("undo"), label: strings("action_undo"), enabled: model.canUndo) { model.undo() }
                    if model.request.config.allowRedo {
                        IconButton(icon: Icons.image("redo"), label: strings("action_redo"), enabled: model.canRedo) { model.redo() }
                    }
                }
                .padding(.horizontal, 4).editorGlass(Capsule(), theme: theme).padding(.top, 12)
            }
        }
        .overlay(alignment: .bottom) {
            if let notice = model.notice {
                Text(noticeText(notice)).scaledFont(14).foregroundStyle(theme.foreground)
                    .padding(.horizontal, 16).padding(.vertical, 10)
                    .background(theme.raised.opacity(0.94), in: RoundedRectangle(cornerRadius: theme.radius)).padding(12)
                    .accessibilityAddTraits(.updatesFrequently)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .clipped()
    }

    private func noticeText(_ key: String) -> String { strings(key) }

    // MARK: - 재생 줄·소리 줄

    @ViewBuilder
    private var controls: some View {
        HStack(spacing: 4) {
            Button { model.togglePlay() } label: {
                Icons.image(model.isPlaying ? "pause" : "play").resizable().frame(width: 24, height: 24)
                    .foregroundStyle(theme.foreground).frame(width: 48, height: 48).contentShape(Rectangle())
            }
            .buttonStyle(PressFadeStyle())
            .accessibilityLabel(strings(model.isPlaying ? "motion_pause" : "motion_play"))
            PlaybackReader(clock: model.playback) {
                Slider(value: Binding(get: { Double(model.position) }, set: { model.seek(to: Int64($0)) }), in: 0...Double(max(1, model.duration - 1)))
                    .tint(theme.accent)
                    .accessibilityLabel(strings("motion_time", seconds(model.position), seconds(model.duration)))
                Text(strings("motion_time", seconds(model.position), seconds(model.duration)))
                    .scaledFont(13, weight: .medium, monospacedDigit: true).foregroundStyle(theme.foregroundMuted)
                    .fixedSize()
                    .accessibilityHidden(true)
            }
        }
        .padding(.horizontal, 8)
        if model.tools.contains(.audio) && (model.hasAudio || model.activeTool == .audio) {
            MotionAudioLanes(model: model).padding(.horizontal, 16).padding(.vertical, 4)
        }
    }

    private func seconds(_ ms: Int64) -> String { String(format: "%.1fs", Double(ms) / 1000) }

    // MARK: - 도구 영역

    @ViewBuilder
    private func toolArea(wide: Bool) -> some View {
        ZStack {
            if let tool = model.activeTool {
                VStack(spacing: 0) {
                    ScrollView(.vertical, showsIndicators: false) {
                        panel(tool).padding(.vertical, 8)
                    }
                    .bounceWhenScrollable()
                    HStack {
                        if model.resettable {
                            EditorButton(title: strings("action_reset"), systemImage: "arrow.counterclockwise", kind: .tonal) { model.resetTool() }
                        }
                        Spacer(minLength: 12)
                        EditorButton(title: strings("action_apply"), systemImage: "checkmark") { model.applyTool() }
                    }
                    .padding(.horizontal, 16).padding(.vertical, 8)
                }
                .transition(.opacity)
            } else {
                VStack(spacing: 0) {
                    if model.isSlideshow { SlideStrip(model: model) }
                    if !model.railTools.isEmpty { rail(wide: wide) }
                }
                .transition(.opacity)
            }
        }
        .animation(reduceMotion ? nil : .easeInOut(duration: 0.2), value: model.activeTool)
    }

    @ViewBuilder
    private func panel(_ tool: MotionTool) -> some View {
        switch tool {
        case .motion: CameraMotionPanel(model: model)
        case .transition: TransitionPanel(model: model)
        case .timing: TimingPanel(model: model)
        case .loop: LoopPanel(model: model)
        case .canvas: MotionCanvasPanel(model: model)
        case .adjust: AdjustPanel(model: model)
        case .filter: FilterPanel(model: model)
        case .text:
            VStack(spacing: 8) {
                OverlayAnimationChips(model: model)
                TextPanel(model: model)
            }
        case .sticker:
            VStack(alignment: .leading, spacing: 8) {
                if model.selectedOverlayId == nil {
                    helpText(strings("motion_sticker_help"))
                } else {
                    OverlayAnimationChips(model: model)
                    EditorButton(title: strings("motion_delete_item"), systemImage: "trash", kind: .tonal) { model.deleteSelected() }
                        .padding(.horizontal, 16)
                }
                StickerPanel(model: model)
            }
        case .audio: MotionAudioPanel(model: model)
        }
    }

    private func helpText(_ text: String) -> some View {
        Text(text).scaledFont(13).foregroundStyle(theme.foregroundMuted).padding(.horizontal, 16)
            .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func rail(wide: Bool) -> some View {
        Group {
            if wide {
                LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 8), count: 3), spacing: 8) {
                    ForEach(model.railTools, id: \.self) { railItem($0, card: true) }
                }
                .padding(16)
            } else {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 4) { ForEach(model.railTools, id: \.self) { railItem($0, card: false) } }.padding(.horizontal, 8)
                }
                .padding(.vertical, 10)
            }
        }
    }

    private func railItem(_ tool: MotionTool, card: Bool) -> some View {
        Button { model.selectTool(tool) } label: {
            VStack(spacing: card ? 6 : 4) {
                tool.icon.resizable().scaledToFit().frame(width: 24, height: 24)
                    .padding(card ? 0 : 8)
                    .background(card ? Color.clear : theme.raised, in: RoundedRectangle(cornerRadius: theme.radius / 2))
                Text(strings(tool.labelKey)).scaledFont(12, weight: .medium).lineLimit(1)
            }
            .foregroundStyle(theme.foreground)
            .frame(minWidth: 72, minHeight: card ? 76 : 48)
            .frame(maxWidth: card ? .infinity : nil)
            .background(card ? theme.surface : Color.clear, in: RoundedRectangle(cornerRadius: theme.radius))
            .contentShape(Rectangle())
        }
        .buttonStyle(PressFadeStyle())
    }

    // MARK: - 저장 중

    @ViewBuilder
    private var exportOverlay: some View {
        if let export = model.export, exportError == nil {
            let fraction: Double? = if case .running(_, let f) = export { f } else { nil }
            ZStack {
                theme.background.opacity(0.82).ignoresSafeArea().contentShape(Rectangle()).onTapGesture {}
                VStack(spacing: 16) {
                    ProgressRing(progress: model.request.ui.showExportProgress ? fraction : nil)
                    Text(strings(export == .cancelling ? "export_cancelling" : (model.request.ui.showExportProgress ? "export_encoding" : "export_exporting")))
                        .scaledFont(15, weight: .medium).foregroundStyle(theme.foreground)
                    if case .running = export {
                        EditorButton(title: strings("action_cancel"), kind: .tonal) { model.cancelExport() }
                    }
                }
                .padding(24).frame(minWidth: 240, maxWidth: 320)
                .background(theme.surface, in: RoundedRectangle(cornerRadius: theme.radius))
            }
        }
    }
}

extension MotionTool {
    var labelKey: String {
        switch self {
        case .motion: "motion_tool_motion"
        case .transition: "motion_tool_transition"
        case .timing: "motion_tool_timing"
        case .loop: "motion_tool_loop"
        case .canvas: "motion_tool_ratio"
        case .adjust: "tool_adjust"
        case .filter: "tool_filter"
        case .text: "tool_text"
        case .sticker: "tool_sticker"
        case .audio: "motion_tool_audio"
        }
    }

    var icon: Image {
        switch self {
        case .motion: Icons.image("motion")
        case .transition: Icons.image("transition")
        case .timing: Icons.image("timer")
        case .loop: Icons.image("loop")
        case .canvas: Icons.image("aspect")
        case .adjust: Icons.image("adjust")
        case .filter: Icons.image("filter")
        case .text: Icons.image("text")
        case .sticker: Icons.image("sticker")
        case .audio: Icons.image("volume")
        }
    }
}

// MARK: - 사진 줄

/// 슬라이드쇼 사진 목록. 누르면 그 사진으로 가고, 단추로 순서를 바꾸거나 뺀다.
struct SlideStrip: View {
    @ObservedObject var model: MotionEditorModel
    @Environment(\.editorTheme) private var theme
    @Environment(\.strings) private var strings

    var body: some View {
        let slides = model.displayed.slides
        let index = model.selectedSlide
        VStack(spacing: 4) {
            HStack(spacing: 0) {
                Text(strings("motion_slide_position", index + 1, slides.count)).scaledFont(13, weight: .medium)
                    .foregroundStyle(theme.foregroundMuted)
                Spacer()
                IconButton(icon: Image(systemName: "arrow.left"), label: strings("motion_slide_earlier"), enabled: index > 0) { model.moveSlide(by: -1) }
                IconButton(icon: Image(systemName: "arrow.right"), label: strings("motion_slide_later"),
                           enabled: index < slides.count - 1) { model.moveSlide(by: 1) }
                IconButton(icon: Image(systemName: "trash"), label: strings("motion_slide_remove"), enabled: slides.count > 1) { model.removeSlide() }
            }
            .padding(.horizontal, 12)
            ScrollViewReader { proxy in
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        ForEach(Array(slides.enumerated()), id: \.offset) { position, source in
                            thumbnail(position, source).id(position)
                        }
                        if slides.count < model.maxImageCount {
                            Button { model.requestAddSlides() } label: {
                                Group {
                                    if model.busy { ProgressView().tint(theme.accent) } else {
                                        Image(systemName: "plus").font(.system(size: 20, weight: .semibold)).foregroundStyle(theme.foreground)
                                    }
                                }
                                .frame(width: 56, height: 56)
                                .background(theme.raised, in: RoundedRectangle(cornerRadius: theme.radius / 2))
                                .contentShape(Rectangle())
                            }
                            .buttonStyle(PressFadeStyle())
                            .disabled(model.busy)
                            .accessibilityLabel(strings("motion_slide_add"))
                        }
                    }
                    .padding(.horizontal, 16)
                    .padding(.vertical, 2)
                }
                .onValueChange(of: model.selectedSlide) { _, new in proxy.scrollTo(new, anchor: .center) }
            }
        }
        .padding(.top, 4)
    }

    private func thumbnail(_ position: Int, _ source: Int) -> some View {
        let selected = position == model.selectedSlide
        return Button { model.selectSlide(position) } label: {
            ZStack(alignment: .bottomTrailing) {
                Group {
                    if let image = model.thumbnails[source] {
                        Image(decorative: image, scale: 1).resizable().scaledToFill()
                    } else {
                        theme.raised
                    }
                }
                .frame(width: 56, height: 56)
                .clipShape(RoundedRectangle(cornerRadius: theme.radius / 2))
                .overlay { RoundedRectangle(cornerRadius: theme.radius / 2).strokeBorder(theme.accent, lineWidth: selected ? 2.5 : 0) }
                Text("\(position + 1)").scaledFont(11, weight: .bold, monospacedDigit: true)
                    .foregroundStyle(theme.onAccent)
                    .padding(.horizontal, 5).padding(.vertical, 1)
                    .background(selected ? theme.accent : Color.black.opacity(0.6), in: Capsule())
                    .offset(x: 3, y: 3)
            }
        }
        .buttonStyle(PressFadeStyle())
        .accessibilityLabel(strings("motion_slide_position", position + 1, model.displayed.slides.count))
        .accessibilityAddTraits(selected ? [.isSelected] : [])
    }
}

// MARK: - 패널

struct CameraMotionPanel: View {
    @ObservedObject var model: MotionEditorModel
    @Environment(\.strings) private var strings
    @Environment(\.editorTheme) private var theme

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(strings("motion_motion_help")).scaledFont(13).foregroundStyle(theme.foregroundMuted).padding(.horizontal, 16)
            chips(CameraMotion.allCases.map(\.rawValue), selected: model.displayed.cameraMotion.rawValue, title: { strings(Self.key($0)) }) { id in
                model.change { $0.cameraMotion = CameraMotion(rawValue: id) ?? .auto }
            }
        }
    }

    static func key(_ id: String) -> String {
        switch CameraMotion(rawValue: id) {
        case .auto: "motion_auto"
        case .zoomIn: "motion_zoom_in"
        case .zoomOut: "motion_zoom_out"
        case .panLeft: "motion_pan_left"
        case .panRight: "motion_pan_right"
        default: "motion_none"
        }
    }
}

struct TransitionPanel: View {
    @ObservedObject var model: MotionEditorModel
    @Environment(\.strings) private var strings

    var body: some View {
        let project = model.displayed
        VStack(alignment: .leading, spacing: 8) {
            chips(MotionTransition.allCases.map(\.rawValue), selected: project.transition.rawValue,
                  title: { $0 == "none" ? strings("motion_none") : strings("motion_transition_\($0)") }) { id in
                model.change { $0.transition = MotionTransition(rawValue: id) ?? .fade }
            }
            if project.transition != .none {
                let upper = Double(max(project.slideDurationMs / 2, 300))
                ValueSlider(label: strings("motion_transition_length"), value: Double(project.transitionMs), range: 200...upper,
                            format: { String(format: "%.2fs", $0 / 1000) },
                            onChange: { model.setTransitionLength(Int64(($0 / 50).rounded() * 50), commit: false) },
                            onFinish: { model.commitGesture() })
            }
        }
    }
}

struct TimingPanel: View {
    @ObservedObject var model: MotionEditorModel
    @Environment(\.strings) private var strings
    @Environment(\.editorTheme) private var theme

    var body: some View {
        let project = model.displayed
        VStack(alignment: .leading, spacing: 8) {
            if project.kind == .slideshow {
                ValueSlider(label: strings("motion_slide_duration"), value: Double(project.slideDurationMs),
                            range: Double(MotionProject.minSlideMs)...Double(MotionProject.maxSlideMs),
                            format: { String(format: "%.1fs", $0 / 1000) },
                            onChange: { model.setSlideDuration(Int64(($0 / 100).rounded() * 100), commit: false) },
                            onFinish: { model.commitGesture() })
            } else {
                Text(strings("motion_speed")).scaledFont(14, weight: .medium).foregroundStyle(theme.foregroundMuted).padding(.horizontal, 16)
                let nearest = MotionEditorModel.speedPresets.min { abs($0 - project.speed) < abs($1 - project.speed) } ?? 1
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        ForEach(MotionEditorModel.speedPresets, id: \.self) { value in
                            ChoiceChip(title: String(format: "%gx", value), selected: value == nearest) { model.setSpeed(value) }
                        }
                    }
                    .padding(.horizontal, 16)
                }
                let total = Double(max(1, model.animationDurationMs))
                let end = Double(project.trimEndMs ?? model.animationDurationMs)
                ValueSlider(label: strings("motion_trim_start"), value: Double(project.trimStartMs), range: 0...total,
                            format: { String(format: "%.1fs", $0 / 1000) },
                            onChange: { model.setTrim(start: true, ms: Int64(min($0, end - Double(MotionProject.minTrimMs))), commit: false) },
                            onFinish: { model.commitGesture() })
                ValueSlider(label: strings("motion_trim_end"), value: end, range: 0...total,
                            format: { String(format: "%.1fs", $0 / 1000) },
                            onChange: { model.setTrim(start: false, ms: Int64($0), commit: false) },
                            onFinish: { model.commitGesture() })
            }
        }
    }
}

struct LoopPanel: View {
    @ObservedObject var model: MotionEditorModel
    @Environment(\.strings) private var strings
    @Environment(\.editorTheme) private var theme

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(strings("motion_loop_help")).scaledFont(13).foregroundStyle(theme.foregroundMuted).padding(.horizontal, 16)
            chips(LoopMode.allCases.map(\.rawValue), selected: model.displayed.loop.rawValue, title: { strings("motion_loop_\($0)") }) { id in
                model.change { $0.loop = LoopMode(rawValue: id) ?? .forward }
            }
        }
    }
}

struct MotionCanvasPanel: View {
    @ObservedObject var model: MotionEditorModel
    @Environment(\.strings) private var strings
    @Environment(\.editorTheme) private var theme

    var body: some View {
        let project = model.displayed
        VStack(alignment: .leading, spacing: 8) {
            chips(MotionAspect.allCases.map(\.rawValue), selected: project.aspect.rawValue, title: { Self.title($0, strings) }) { id in
                model.change { $0.aspect = MotionAspect(rawValue: id) ?? .original }
            }
            chips(MotionFit.allCases.map(\.rawValue), selected: project.fit.rawValue, title: { strings("motion_fit_\($0)") }) { id in
                model.change { $0.fit = MotionFit(rawValue: id) ?? .fill }
            }
            if project.fit == .fit {
                Text(strings("motion_background")).scaledFont(14, weight: .medium).foregroundStyle(theme.foregroundMuted).padding(.horizontal, 16)
                ColorPalette(selected: project.backgroundArgb) { argb in model.change { $0.backgroundArgb = argb } }
            }
        }
    }

    static func title(_ id: String, _ strings: Strings) -> String {
        switch MotionAspect(rawValue: id) {
        case .square: "1:1"
        case .portrait45: "4:5"
        case .story916: "9:16"
        case .wide169: "16:9"
        default: strings("motion_aspect_original")
        }
    }
}

/// 고른 텍스트·스티커의 움직임.
struct OverlayAnimationChips: View {
    @ObservedObject var model: MotionEditorModel
    @Environment(\.strings) private var strings
    @Environment(\.editorTheme) private var theme

    var body: some View {
        if let current = model.selectedAnimation {
            VStack(alignment: .leading, spacing: 4) {
                Text(strings("motion_overlay_animation")).scaledFont(14, weight: .medium).foregroundStyle(theme.foregroundMuted)
                    .padding(.horizontal, 16)
                chips(OverlayAnimation.allCases.map(\.rawValue), selected: current.rawValue, title: { Self.title($0, strings) }) { id in
                    model.setSelectedAnimation(OverlayAnimation(rawValue: id) ?? .none)
                }
            }
        }
    }

    static func title(_ id: String, _ strings: Strings) -> String {
        switch OverlayAnimation(rawValue: id) {
        case .fadeIn: strings("motion_anim_fade_in")
        case .pop: strings("motion_anim_pop")
        case .slideUp: strings("motion_anim_slide_up")
        case .bounce: strings("motion_anim_bounce")
        case .wiggle: strings("motion_anim_wiggle")
        case .pulse: strings("motion_anim_pulse")
        default: strings("motion_none")
        }
    }
}

// MARK: - 소리

/// 음악·음성 줄. 조각을 누르면 고르고, 고른 조각은 끌어서 옮긴다.
struct MotionAudioLanes: View {
    @ObservedObject var model: MotionEditorModel
    @Environment(\.editorTheme) private var theme
    @Environment(\.strings) private var strings
    @State private var drag: Drag?

    /// 고른 조각 끌기: 가운데는 옮기고, 양 끝은 줄이거나 늘린다.
    private enum Drag { case move, start(AudioClipState), end(AudioClipState) }

    var body: some View {
        GeometryReader { geo in
            let width = geo.size.width
            let total = Double(max(1, model.durationUs))
            ZStack(alignment: .topLeading) {
                VStack(spacing: 6) {
                    lane(.music, width: width, total: total)
                    lane(.voice, width: width, total: total)
                }
                PlaybackReader(clock: model.playback) {
                    Rectangle().fill(theme.foreground).frame(width: 2, height: 58)
                        .offset(x: Double(model.positionUs) / total * width - 1)
                        .allowsHitTesting(false)
                }
            }
        }
        .frame(height: 58)
    }

    private func lane(_ role: AudioRole, width: CGFloat, total: Double) -> some View {
        ZStack(alignment: .leading) {
            RoundedRectangle(cornerRadius: 6).fill(theme.raised.opacity(0.6))
            ForEach(model.displayed.audio.filter { $0.role == role }) { clip in
                let range = clip.outputRange(videoDuration: model.durationUs)
                let x = Double(range.start) / total * width
                let w = max(8, Double(range.duration) / total * width)
                let selected = clip.id == model.selectedAudioId
                RoundedRectangle(cornerRadius: 6)
                    .fill((role == .music ? theme.accent : Color.orange).opacity(selected ? 1 : 0.55))
                    .overlay {
                        Canvas { context, size in
                            guard let wave = model.audioWaves[clip.source], !wave.peaks.isEmpty else { return }
                            let path = wave.bars(for: clip, in: CGRect(origin: .zero, size: size), visible: 0...size.width) { bx in
                                range.start + Micros(Double(bx / max(1, size.width)) * Double(range.duration))
                            }
                            context.fill(path, with: .color(.white.opacity(0.4)))
                        }
                        .allowsHitTesting(false)
                    }
                    .overlay {
                        if selected {
                            RoundedRectangle(cornerRadius: 6).strokeBorder(theme.foreground, lineWidth: 2)
                            HStack {
                                Capsule().fill(theme.foreground).frame(width: 5, height: 16)
                                Spacer()
                                Capsule().fill(theme.foreground).frame(width: 5, height: 16)
                            }
                            .padding(.horizontal, 2)
                            .allowsHitTesting(false)
                        }
                    }
                    .frame(width: w, height: 26)
                    .offset(x: x)
                    .onTapGesture { model.selectAudio(clip.id) }
                    .gesture(selected ? DragGesture(minimumDistance: 4)
                        .onChanged { g in
                            if drag == nil {
                                let reach = min(14, w / 3)
                                drag = g.startLocation.x <= reach ? .start(clip) : g.startLocation.x >= w - reach ? .end(clip) : .move
                            }
                            let delta = Micros(Double(g.translation.width) / width * total)
                            switch drag {
                            case .start(let original): model.trimSelectedAudio(from: original, start: true, by: delta)
                            case .end(let original): model.trimSelectedAudio(from: original, start: false, by: delta)
                            default: model.dragSelectedAudio(by: delta)
                            }
                        }
                        .onEnded { _ in
                            drag = nil
                            model.endAudioDrag()
                        } : nil)
                    .accessibilityLabel(strings(role == .music ? "track_music" : "track_voice"))
                    .accessibilityAddTraits(selected ? [.isButton, .isSelected] : .isButton)
            }
        }
        .frame(height: 26)
    }
}

struct MotionAudioPanel: View {
    @ObservedObject var model: MotionEditorModel
    @Environment(\.strings) private var strings
    @Environment(\.editorTheme) private var theme

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(strings("motion_audio_help")).scaledFont(13).foregroundStyle(theme.foregroundMuted).padding(.horizontal, 16)
            if let audio = model.selectedAudio {
                Text(strings(audio.role == .music ? "track_music" : "track_voice")).scaledFont(15, weight: .semibold)
                    .foregroundStyle(theme.foreground).padding(.horizontal, 16)
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        EditorButton(title: strings("motion_audio_split"), systemImage: "scissors", kind: .tonal) { model.splitSelectedAudio() }
                        ToggleChip(title: strings("track_loop"), isOn: audio.loop) {
                            model.changeSelectedAudio { $0.loop.toggle(); if $0.loop { $0.timelineEnd = nil } }
                        }
                        EditorButton(title: strings("item_delete"), systemImage: "trash", kind: .tonal) { model.deleteSelectedAudio() }
                        EditorButton(title: strings("action_close"), kind: .tonal) { model.selectedAudioId = nil }
                    }
                    .padding(.horizontal, 16)
                }
                ValueSlider(label: strings("track_volume"), value: audio.volume * 100, range: 0...200, format: { "\(Int($0.rounded()))%" },
                            onChange: { v in model.changeSelectedAudio(commit: false) { $0.volume = abs(v - 100) < 4 ? 1 : v / 100 } },
                            onFinish: { model.commitGesture() })
                let limit = Double(min(audio.outputRange(videoDuration: model.durationUs).duration / 2, 5_000_000)) / 1_000_000
                if limit > 0.05 {
                    ValueSlider(label: strings("motion_audio_fade_in"), value: min(limit, Double(audio.fadeIn) / 1_000_000), range: 0...limit,
                                format: { String(format: "%.1fs", $0) },
                                onChange: { v in model.changeSelectedAudio(commit: false) { $0.fadeIn = Micros(v * 1_000_000) } },
                                onFinish: { model.commitGesture() })
                    ValueSlider(label: strings("motion_audio_fade_out"), value: min(limit, Double(audio.fadeOut) / 1_000_000), range: 0...limit,
                                format: { String(format: "%.1fs", $0) },
                                onChange: { v in model.changeSelectedAudio(commit: false) { $0.fadeOut = Micros(v * 1_000_000) } },
                                onFinish: { model.commitGesture() })
                }
            } else {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        EditorButton(title: strings("track_music_add"), systemImage: "music.note", kind: .tonal) {
                            model.musicPickerRole = .music
                            model.showMusicPicker = true
                        }
                        EditorButton(title: strings("track_voice_add"), systemImage: "waveform", kind: .tonal) {
                            model.musicPickerRole = .voice
                            model.showMusicPicker = true
                        }
                        if model.micDeclared {
                            EditorButton(title: strings("voice_record"), systemImage: "mic.fill", kind: .tonal) { model.showRecorder = true }
                        }
                        if model.busy { ProgressView().tint(theme.accent) }
                    }
                    .padding(.horizontal, 16)
                }
            }
            if model.hasAudio && model.request.export.formats != [.mp4] {
                Text(strings("track_sound_mp4_only")).scaledFont(13).foregroundStyle(theme.foregroundMuted).padding(.horizontal, 16)
            }
        }
    }
}

// MARK: - 저장 시트

struct MotionSaveSheet: View {
    @ObservedObject var model: MotionEditorModel
    @Environment(\.strings) private var strings
    @Environment(\.editorTheme) private var theme

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                Text(strings("motion_save_title")).scaledFont(20, weight: .bold).foregroundStyle(theme.foreground)
                    .accessibilityAddTraits(.isHeader)
                ForEach(model.request.export.formats, id: \.self) { format in
                    card(selected: model.saveFormat == format, title: strings("motion_format_\(format.rawValue)"),
                         detail: strings("motion_format_\(format.rawValue)_help")) { model.saveFormat = format }
                }
                if model.hasAudio && model.saveFormat != .mp4 {
                    Label(strings("track_sound_mp4_only"), systemImage: "speaker.slash").scaledFont(13).foregroundStyle(theme.error)
                }
                Text(strings("motion_size")).scaledFont(15, weight: .semibold).foregroundStyle(theme.foreground).padding(.top, 4)
                HStack(spacing: 8) {
                    ForEach(MotionSize.allCases, id: \.self) { size in
                        card(selected: model.saveSize == size, title: strings("motion_size_\(size.rawValue)"),
                             detail: strings("motion_size_px", size.longEdge(model.saveFormat))) { model.saveSize = size }
                    }
                }
                HStack {
                    EditorButton(title: strings("action_cancel"), kind: .tonal) { model.showSaveSheet = false }
                    Spacer()
                    EditorButton(title: strings("action_save"), systemImage: "square.and.arrow.down") { model.save() }
                }
                .padding(.top, 8)
            }
            .padding(20)
        }
        .background(theme.surface.ignoresSafeArea())
        .sheetSize(.mediumLarge)
        .environment(\.editorTheme, theme)
    }

    private func card(selected: Bool, title: String, detail: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            VStack(alignment: .leading, spacing: 4) {
                Text(title).scaledFont(15, weight: .semibold).foregroundStyle(theme.foreground)
                Text(detail).scaledFont(13).foregroundStyle(theme.foregroundMuted).multilineTextAlignment(.leading)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(12)
            .background(theme.raised, in: RoundedRectangle(cornerRadius: theme.radius))
            .overlay { RoundedRectangle(cornerRadius: theme.radius).strokeBorder(theme.accent, lineWidth: selected ? 2 : 0) }
            .contentShape(Rectangle())
        }
        .buttonStyle(PressFadeStyle())
        .accessibilityAddTraits(selected ? [.isSelected] : [])
    }
}
