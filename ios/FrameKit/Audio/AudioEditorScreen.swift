import SwiftUI
import UniformTypeIdentifiers

/// 음성 편집 화면 뿌리.
struct AudioEditorRoot: View {
    @ObservedObject var model: AudioEditorModel
    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        let ui = model.request.ui
        let theme = EditorTheme.make(ui, systemDark: colorScheme == .dark)
        let strings = Strings(localeIdentifier: ui.localeIdentifier)
        Group {
            switch model.phase {
            case .awaitingSource:
                AudioFilesPicker(limit: model.pickLimit) { model.picked($0) }.ignoresSafeArea()
            case .recording:
                FirstRecordingScreen(model: model)
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
                AudioEditorScreen(model: model)
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

/// `.capture`로 열었을 때 첫 녹음 화면.
struct FirstRecordingScreen: View {
    @ObservedObject var model: AudioEditorModel
    @Environment(\.editorTheme) private var theme
    @Environment(\.strings) private var strings

    var body: some View {
        VStack(spacing: 0) {
            ZStack {
                Text(strings("audio_new_recording")).scaledFont(17, weight: .semibold).foregroundStyle(theme.foreground)
                    .accessibilityAddTraits(.isHeader)
                HStack {
                    Button { model.finish(.cancelled) } label: {
                        Image(systemName: "xmark").font(.system(size: 17, weight: .semibold)).foregroundStyle(theme.foreground)
                            .frame(width: 42, height: 42).editorGlass(Circle(), theme: theme).frame(minWidth: 48, minHeight: 48)
                    }
                    .buttonStyle(PressFadeStyle())
                    .accessibilityLabel(strings("action_close"))
                    Spacer()
                }
            }
            .padding(.horizontal, 12).frame(height: 60)
            Spacer()
            RecorderPanel(recorder: model.recorder, mode: model.request.config.recordMode) { model.recorded($0) }
                .frame(maxWidth: 520).padding(.horizontal, 24)
            if let notice = model.notice {
                Text(strings(notice)).scaledFont(14).foregroundStyle(theme.error).padding(.top, 12)
            }
            Spacer()
        }
        .background(theme.background.ignoresSafeArea())
    }
}

struct AudioEditorScreen: View {
    @ObservedObject var model: AudioEditorModel
    @Environment(\.editorTheme) private var theme
    @Environment(\.strings) private var strings
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        GeometryReader { geo in
            let tall = geo.size.height >= 800
            VStack(spacing: 0) {
                topBar
                header.padding(.top, 8)
                AudioTimelineView(model: model, tall: tall).padding(.top, 12)
                playbackRow.padding(.top, 8)
                Spacer(minLength: 8)
                bottomPanel
            }
            .frame(maxWidth: 840)
            .frame(maxWidth: .infinity)
        }
        .background(theme.background.ignoresSafeArea())
        .overlay(alignment: .bottom) {
            if let notice = model.notice {
                Text(strings(notice)).scaledFont(14).foregroundStyle(theme.foreground)
                    .padding(.horizontal, 16).padding(.vertical, 10)
                    .background(theme.raised.opacity(0.94), in: RoundedRectangle(cornerRadius: theme.radius))
                    .padding(.bottom, 96)
                    .accessibilityAddTraits(.updatesFrequently)
            }
        }
        .overlay { exportOverlay }
        .alert(strings("discard_title"), isPresented: $model.showDiscardDialog) {
            Button(strings("action_keep_editing"), role: .cancel) {}
            Button(strings("action_discard"), role: .destructive) { model.discardAndClose() }
        } message: { Text(strings("discard_message")) }
        .alert(strings("export_failed_title"), isPresented: exportFailed) {
            if exportError?.recoverable == true { Button(strings("action_retry")) { model.retryExport() } }
            Button(strings("action_keep_editing"), role: .cancel) { model.dismissExportError() }
        } message: { Text(exportError.map { strings(errorKey($0.code)) } ?? "") }
        .sheet(isPresented: $model.showFilePicker) {
            AudioFilesPicker(limit: max(1, model.request.config.maxSourceCount - model.sources.count)) { model.importFiles($0) }
                .ignoresSafeArea()
        }
        .sheet(isPresented: Binding(get: { model.recordTarget != nil }, set: { if !$0 { model.closeRecordSheet() } })) {
            RecordSheet(model: model)
        }
    }

    private var exportError: EditorError? { if case .failed(let e) = model.export { e } else { nil } }
    private var exportFailed: Binding<Bool> {
        Binding(get: { exportError != nil }, set: { if !$0 { model.dismissExportError() } })
    }

    private var topBar: some View {
        HStack {
            Button { model.onBack() } label: {
                Image(systemName: "xmark").font(.system(size: 17, weight: .semibold)).foregroundStyle(theme.foreground)
                    .frame(width: 42, height: 42).editorGlass(Circle(), theme: theme)
                    .frame(minWidth: 48, minHeight: 48).contentShape(Rectangle())
            }
            .buttonStyle(PressFadeStyle())
            .accessibilityLabel(strings("action_close"))
            Spacer()
            Button { model.save() } label: {
                Text(strings("action_save")).scaledFont(16, weight: .semibold).foregroundStyle(theme.onAccent)
                    .padding(.horizontal, 18).frame(height: 42)
                    .editorGlass(Capsule(), theme: theme, tint: theme.accent)
                    .frame(minHeight: 48).contentShape(Rectangle())
            }
            .buttonStyle(PressFadeStyle())
            .disabled(model.export != nil || model.duration <= 0)
        }
        .padding(.horizontal, 12)
        .frame(height: 60)
    }

    private var header: some View {
        VStack(spacing: 2) {
            PlaybackReader(clock: model.playback) {
                Text(clock(Double(model.position) / 1_000_000)).scaledFont(40, weight: .light, monospacedDigit: true)
                    .foregroundStyle(theme.foreground)
            }
            Text(strings("audio_total", clock(Double(model.duration) / 1_000_000))).scaledFont(14, monospacedDigit: true)
                .foregroundStyle(theme.foregroundMuted)
        }
        .accessibilityElement(children: .combine)
    }

    private var playbackRow: some View {
        HStack(spacing: 12) {
            if model.request.config.allowUndo {
                IconButton(icon: Icons.image("undo"), label: strings("audio_undo"), enabled: model.canUndo) { model.undo() }
            }
            Spacer()
            skipButton(forward: false)
            Button { model.togglePlay() } label: {
                Icons.image(model.isPlaying ? "pause" : "play").resizable().frame(width: 26, height: 26).foregroundStyle(theme.onAccent)
                    .frame(width: 64, height: 64).background(theme.accent, in: Circle())
            }
            .buttonStyle(PressFadeStyle())
            .accessibilityLabel(strings(model.isPlaying ? "audio_pause_playback" : "audio_play"))
            skipButton(forward: true)
            Spacer()
            if model.request.config.allowRedo {
                IconButton(icon: Icons.image("redo"), label: strings("audio_redo"), enabled: model.canRedo) { model.redo() }
            }
        }
        .padding(.horizontal, 16)
    }

    private func skipButton(forward: Bool) -> some View {
        Button { model.skip(forward: forward) } label: {
            Image(systemName: forward ? "goforward.5" : "gobackward.5").font(.system(size: 22)).foregroundStyle(theme.foreground)
                .frame(width: 48, height: 48).contentShape(Rectangle())
        }
        .buttonStyle(PressFadeStyle())
    }

    @ViewBuilder
    private var bottomPanel: some View {
        ZStack {
            if model.range != nil {
                RangePanel(model: model).transition(.opacity)
            } else if model.selectedClip != nil {
                ClipPanel(model: model).transition(.opacity)
            } else {
                toolRail.transition(.opacity)
            }
        }
        .animation(reduceMotion ? nil : .easeInOut(duration: 0.18), value: model.range != nil)
        .animation(reduceMotion ? nil : .easeInOut(duration: 0.18), value: model.selectedClipId)
        .padding(.bottom, 8)
    }

    private var toolRail: some View {
        VStack(spacing: 6) {
            Text(strings("audio_tools_hint")).scaledFont(13).foregroundStyle(theme.foregroundMuted)
                .multilineTextAlignment(.center).padding(.horizontal, 16)
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 4) {
                    ForEach(model.tools, id: \.self) { tool in
                        Button { run(tool) } label: {
                            VStack(spacing: 4) {
                                tool.icon.resizable().scaledToFit().frame(width: 24, height: 24).padding(8)
                                    .background(theme.raised, in: RoundedRectangle(cornerRadius: theme.radius / 2))
                                Text(strings(tool.labelKey)).scaledFont(12, weight: .medium).lineLimit(1)
                            }
                            .foregroundStyle(theme.foreground)
                            .frame(minWidth: 72, minHeight: 48)
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(PressFadeStyle())
                        .disabled(model.busy)
                    }
                    if model.busy { ProgressView().tint(theme.accent).padding(.horizontal, 12) }
                }
                .padding(.horizontal, 8)
            }
            .fixedSize(horizontal: false, vertical: true)
        }
    }

    private func run(_ tool: AudioTool) {
        switch tool {
        case .record: model.openRecordSheet(.insert)
        case .importFile: model.pause(); model.showFilePicker = true
        case .split: model.splitAtPlayhead()
        case .range: model.beginRange()
        case .volume: model.selectClipAtPlayhead()
        }
    }

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

extension AudioTool {
    var labelKey: String { self == .importFile ? "audio_tool_import" : "audio_tool_\(rawValue)" }

    var icon: Image {
        switch self {
        case .record: Icons.image("mic")
        case .importFile: Icons.image("audio_file")
        case .split: Icons.image("trim")
        case .range: Icons.image("range")
        case .volume: Icons.image("volume")
        }
    }
}

// MARK: - 패널

struct RangePanel: View {
    @ObservedObject var model: AudioEditorModel
    @Environment(\.strings) private var strings
    @Environment(\.editorTheme) private var theme

    var body: some View {
        VStack(spacing: 10) {
            Text(strings("audio_range_hint")).scaledFont(13).foregroundStyle(theme.foregroundMuted)
            HStack(spacing: 8) {
                pill(strings("action_cancel"), "xmark", fill: theme.raised, tint: theme.foreground) { model.cancelRange() }
                    .layoutPriority(0)
                pill(strings("audio_keep_range"), "arrow.right.and.line.vertical.and.arrow.left", fill: theme.raised, tint: theme.foreground) {
                    model.keepRange()
                }
                .layoutPriority(1)
                pill(strings("audio_delete_range"), "trash", fill: theme.error, tint: .white) { model.deleteRange() }
                    .layoutPriority(1)
            }
        }
        .padding(.horizontal, 16)
    }

    private func pill(_ title: String, _ symbol: String, fill: Color, tint: Color, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Label(title, systemImage: symbol).scaledFont(15, weight: .semibold).lineLimit(1).minimumScaleFactor(0.75)
                .foregroundStyle(tint).padding(.horizontal, 12).frame(maxWidth: .infinity, minHeight: 48)
                .background(fill, in: Capsule()).contentShape(Capsule())
        }
        .buttonStyle(PressFadeStyle())
    }
}

struct ClipPanel: View {
    @ObservedObject var model: AudioEditorModel
    @Environment(\.strings) private var strings
    @Environment(\.editorTheme) private var theme

    var body: some View {
        if let clip = model.selectedClip {
            let length = model.project.range(of: clip).duration
            let limit = max(0.1, Double(min(length / 2, 5_000_000)) / 1_000_000)
            VStack(alignment: .leading, spacing: 4) {
                HStack(spacing: 0) {
                    Text(strings(clip.role == .voice ? "audio_lane_voice" : "audio_lane_layer")).scaledFont(15, weight: .semibold)
                        .foregroundStyle(theme.foreground)
                    Spacer()
                    IconButton(icon: Icons.image("trim"), label: strings("audio_tool_split")) { model.splitAtPlayhead() }
                    if clip.role != .voice {
                        IconButton(icon: Image(systemName: "repeat"), label: strings("audio_loop")) { model.toggleLoop() }
                            .foregroundStyle(clip.loop ? theme.accent : theme.foreground)
                            .accessibilityAddTraits(clip.loop ? .isSelected : [])
                    }
                    IconButton(icon: Image(systemName: "trash"), label: strings("audio_delete_clip")) { model.deleteSelectedClip() }
                    IconButton(icon: Image(systemName: "xmark"), label: strings("action_close")) { model.selectClip(nil) }
                }
                .padding(.horizontal, 16)
                ValueSlider(label: strings("audio_volume"), value: clip.volume * 100, range: 0...200, format: { "\(Int($0.rounded()))%" },
                            onChange: { v in model.changeSelected(live: true) { $0.volume = abs(v - 100) < 4 ? 1 : v / 100 } },
                            onFinish: { model.finishGesture() })
                ValueSlider(label: strings("audio_fade_in"), value: min(limit, Double(clip.fadeIn) / 1_000_000), range: 0...limit,
                            format: { String(format: "%.1fs", $0) },
                            onChange: { v in model.changeSelected(live: true) { $0.fadeIn = Micros(v * 1_000_000) } },
                            onFinish: { model.finishGesture() })
                ValueSlider(label: strings("audio_fade_out"), value: min(limit, Double(clip.fadeOut) / 1_000_000), range: 0...limit,
                            format: { String(format: "%.1fs", $0) },
                            onChange: { v in model.changeSelected(live: true) { $0.fadeOut = Micros(v * 1_000_000) } },
                            onFinish: { model.finishGesture() })
            }
        }
    }
}

/// 녹음해서 끼워 넣거나 겹친다.
struct RecordSheet: View {
    @ObservedObject var model: AudioEditorModel
    @ObservedObject private var recorder: VoiceRecorder

    init(model: AudioEditorModel) {
        _model = ObservedObject(wrappedValue: model)
        _recorder = ObservedObject(wrappedValue: model.recorder)
    }
    @Environment(\.strings) private var strings
    @Environment(\.editorTheme) private var theme

    var body: some View {
        let target = model.recordTarget ?? .insert
        VStack(spacing: 12) {
            Text(strings("audio_record_title")).scaledFont(20, weight: .bold).foregroundStyle(theme.foreground)
                .accessibilityAddTraits(.isHeader)
            Text(strings(target == .insert ? "audio_insert_hint" : "audio_overlay_hint")).scaledFont(14)
                .foregroundStyle(theme.foregroundMuted).multilineTextAlignment(.center)
            if recorder.state == .idle {
                HStack(spacing: 8) {
                    ChoiceChip(title: strings("audio_target_insert"), selected: target == .insert) { model.recordTarget = .insert }
                    ChoiceChip(title: strings("audio_target_overlay"), selected: target == .overlay) { model.recordTarget = .overlay }
                }
            }
            RecorderPanel(recorder: model.recorder, mode: model.request.config.recordMode, compact: true) { model.recorded($0) }
            EditorButton(title: strings("action_cancel"), kind: .tonal) { model.closeRecordSheet() }
        }
        .padding(20)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .sheetBackground(theme.surface)
        .sheetSize(.large)
        .interactiveDismissDisabled(recorder.state != .idle)
    }
}

/// 소리 파일 여러 개 고르기(파일 앱). 고른 순서대로 사본을 넘긴다. 닫으면 빈 목록.
struct AudioFilesPicker: UIViewControllerRepresentable {
    var limit = 1
    let onPick: (PickedFiles) -> Void

    func makeUIViewController(context: Context) -> UIDocumentPickerViewController {
        let picker = UIDocumentPickerViewController(forOpeningContentTypes: [.audio], asCopy: true)
        picker.delegate = context.coordinator
        picker.allowsMultipleSelection = limit > 1
        return picker
    }

    func updateUIViewController(_: UIDocumentPickerViewController, context _: Context) {}
    func makeCoordinator() -> Coordinator { Coordinator(limit: limit, onPick: onPick) }

    final class Coordinator: NSObject, UIDocumentPickerDelegate {
        let limit: Int
        let onPick: (PickedFiles) -> Void
        init(limit: Int, onPick: @escaping (PickedFiles) -> Void) {
            self.limit = limit
            self.onPick = onPick
        }

        func documentPicker(_: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
            // 복사본이므로 한도를 넘는 것은 지운다.
            let keep = max(1, limit)
            urls.dropFirst(keep).forEach { try? FileManager.default.removeItem(at: $0) }
            var files = PickedFiles(urls: Array(urls.prefix(keep)))
            files.truncated = urls.count > keep
            onPick(files)
        }

        func documentPickerWasCancelled(_: UIDocumentPickerViewController) { onPick(PickedFiles()) }
    }
}
