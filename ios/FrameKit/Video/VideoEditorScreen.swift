import AVFoundation
import PhotosUI
import SwiftUI
import UniformTypeIdentifiers
internal import FrameKitCore

/// 영상 편집 화면 뿌리.
struct VideoEditorRoot: View {
    let model: VideoEditorModel
    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        let ui = model.request.ui
        let theme = EditorTheme.make(ui, systemDark: colorScheme == .dark)
        Group {
            switch model.phase {
            case .awaitingSource:
                if case .capture = model.request.input {
                    VideoCameraPicker { result in
                        var files = PickedFiles()
                        if let result { files.add(result) }
                        model.picked(files)
                    }
                    .ignoresSafeArea()
                } else {
                    VideoPicker(limit: model.initialPickLimit) { model.picked($0) }.ignoresSafeArea()
                }
            case .loading:
                ProgressView().controlSize(.large).tint(theme.accent).frame(maxWidth: .infinity, maxHeight: .infinity)
                    .background(theme.background.ignoresSafeArea())
            case .failed(let error):
                VStack(spacing: 16) {
                    Image(systemName: "exclamationmark.triangle").font(.system(size: 36)).foregroundStyle(theme.error)
                    Text(Strings(localeIdentifier: ui.localeIdentifier)(errorKey(error.code))).multilineTextAlignment(.center).padding(.horizontal, 32)
                        .foregroundStyle(theme.foreground)
                    EditorButton(title: Strings(localeIdentifier: ui.localeIdentifier)("action_close"), kind: .tonal) { model.finish(.failure(error)) }
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .background(theme.background.ignoresSafeArea())
            case .ready:
                VideoEditorScreen(model: model)
            }
        }
        .environment(\.editorTheme, theme)
        .environment(\.strings, Strings(localeIdentifier: ui.localeIdentifier))
        .environment(\.haptics, Haptics(enabled: ui.enableHaptics))
        .environment(\.editorFontId, ui.uiFontId)
        .preferredColorScheme(ui.themeMode == .system ? nil : (theme.isDark ? .dark : .light))
        .tint(theme.accent)
        .dynamicTypeSize(...DynamicTypeSize.accessibility1)
        .onAppear { model.start() }
    }
}

struct VideoEditorScreen: View {
    @Bindable var model: VideoEditorModel
    @Environment(\.editorTheme) private var theme
    @Environment(\.strings) private var strings
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.editorEmbedded) private var embedded

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
                        .frame(maxHeight: size.height * 0.6, alignment: .bottom)
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
        .sheet(isPresented: $model.showAddPicker) {
            VideoPicker(limit: max(1, model.request.config.maxClipCount - model.displayed.clips.count)) { model.picked($0, append: true) }
                .ignoresSafeArea()
        }
    }

    private var toolName: String { model.activeTool.map { strings($0.labelKey) } ?? "" }
    private var exportError: EditorError? { if case .failed(let e) = model.export { e } else { nil } }
    private var exportFailed: Binding<Bool> {
        Binding(get: { exportError != nil }, set: { if !$0 { model.dismissExportError() } })
    }

    // MARK: - 상단 바

    @ViewBuilder
    private var topBar: some View {
        if embedded && model.activeTool == nil {
            EmptyView()
        } else {
            fullTopBar
        }
    }

    private var fullTopBar: some View {
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
                if !embedded && model.activeTool == nil {
                Button { model.save() } label: {
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
            if model.activeTool?.isGeometry == true {
                VideoCropCanvas(model: model)
            } else {
                GeometryReader { geo in
                    let rect = fit(model.renderSize, in: CGRect(origin: .zero, size: geo.size).insetBy(dx: 16, dy: 16))
                    ZStack {
                        PlayerSurface(player: model.player)
                            .frame(width: rect.width, height: rect.height).position(x: rect.midX, y: rect.midY)
                            .accessibilityHidden(true)
                        if let overlay = model.overlayImage {
                            Image(decorative: overlay, scale: 1).resizable()
                                .frame(width: rect.width, height: rect.height).position(x: rect.midX, y: rect.midY)
                                .allowsHitTesting(false).accessibilityHidden(true)
                        }
                        if model.activeTool == .privacy { maskOutlines(rect) }
                        if [VideoTool.text, .sticker, .privacy].contains(model.activeTool) {
                            CanvasInteraction(model: model, rect: rect, zoom: 1)
                            if model.activeTool == .privacy { MaskHandles(model: model, rect: rect) }
                        } else {
                            Color.clear.contentShape(Rectangle())
                                .onTapGesture { if model.activeTool == nil { model.togglePlay() } }
                                .accessibilityLabel(strings(model.isPlaying ? "pause" : "play"))
                                .accessibilityAddTraits(.isButton)
                        }
                    }
                }
            }
            if model.previewFailed {
                Text(strings("preview_unavailable")).scaledFont(14).foregroundStyle(theme.foreground)
                    .padding(12).background(theme.raised.opacity(0.94), in: RoundedRectangle(cornerRadius: theme.radius))
            }
        }
        .overlay(alignment: .top) {
            if model.activeTool?.isDraft != true && model.request.config.allowUndo {
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
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .clipped()
    }

    private func noticeText(_ key: String) -> String {
        switch key {
        case "clip_limit": String.localizedStringWithFormat(strings(key), model.request.config.maxClipCount)
        case "mask_limit": String.localizedStringWithFormat(strings(key), VideoEdit.maxPrivacyMasks)
        default: strings(key)
        }
    }

    /// 지금 켜진 가리기와 고른 가리기의 테두리.
    private func maskOutlines(_ rect: CGRect) -> some View {
        let masks = model.displayed.masks.filter { $0.range.contains(model.position) || $0.mask.id == model.selectedMaskId }
        return ForEach(masks, id: \.mask.id) { timed in
            let r: NRect? = switch timed.mask.shape {
            case .rectangle(let r), .ellipse(let r): r
            case .brush: nil
            }
            if let r {
                let box = CGRect(x: rect.minX + r.left * rect.width, y: rect.minY + r.top * rect.height, width: r.width * rect.width, height: r.height * rect.height)
                Group {
                    if case .ellipse = timed.mask.shape { Ellipse().stroke(timed.mask.id == model.selectedMaskId ? theme.accent : .white, lineWidth: 2) }
                    else { Rectangle().stroke(timed.mask.id == model.selectedMaskId ? theme.accent : .white, lineWidth: 2) }
                }
                .frame(width: box.width, height: box.height).position(x: box.midX, y: box.midY).allowsHitTesting(false)
            }
        }
    }

    // MARK: - 재생 줄·타임라인·클립 동작

    @ViewBuilder
    private var controls: some View {
        if model.activeTool?.isGeometry != true {
            HStack {
                Button { model.togglePlay() } label: {
                    Icons.image(model.isPlaying ? "pause" : "play").resizable().frame(width: 24, height: 24)
                        .foregroundStyle(theme.foreground).frame(width: 48, height: 48).contentShape(Rectangle())
                }
                .buttonStyle(PressFadeStyle())
                .accessibilityLabel(strings(model.isPlaying ? "pause" : "play"))
                Text(strings("time_position", formatTime(model.position), formatTime(model.duration)))
                    .scaledFont(13, weight: .medium, monospacedDigit: true).foregroundStyle(theme.foregroundMuted)
                Spacer()
                if model.selectedHasAudio && model.tools.contains(.audio) && model.export == nil {
                    let muted = model.selectedClip?.muted == true
                    Button { model.toggleMute() } label: {
                        Icons.image(muted ? "volume_off" : "volume").resizable().frame(width: 22, height: 22)
                            .foregroundStyle(muted ? theme.accent : theme.foreground).frame(width: 48, height: 48).contentShape(Rectangle())
                    }
                    .buttonStyle(PressFadeStyle())
                    .accessibilityLabel(strings(muted ? "audio_unmute" : "audio_mute"))
                    .disabled(model.working != nil)
                }
            }
            .padding(.horizontal, 8)
            if model.activeTool == .trim {
                TrimStrip(model: model).padding(.vertical, 4)
                Text(strings("trim_help")).scaledFont(13).foregroundStyle(theme.foregroundMuted)
            } else {
                TrackTimeline(model: model).padding(.vertical, 4)
            }
            if model.activeTool == nil && !model.hasTimelineSelection && model.request.config.maxClipCount > 1 {
                clipActions
            }
        }
    }

    private var clipActions: some View {
        HStack {
            let count = model.displayed.clips.count
            let index = model.selectedIndex ?? 0
            Spacer()
            IconButton(icon: Image(systemName: "plus.rectangle.on.rectangle"), label: strings("clip_add"),
                       enabled: count < model.request.config.maxClipCount && !model.busy) { model.requestAddClips() }
            Spacer()
            IconButton(icon: Icons.image("trim"), label: strings("clip_split")) { model.splitAtPlayhead() }
            Spacer()
            IconButton(icon: Image(systemName: "arrow.left.to.line"), label: strings("clip_move_left"), enabled: index > 0) { model.moveSelectedClip(by: -1) }
            Spacer()
            IconButton(icon: Image(systemName: "arrow.right.to.line"), label: strings("clip_move_right"),
                       enabled: index < count - 1) { model.moveSelectedClip(by: 1) }
            Spacer()
            IconButton(icon: Image(systemName: "trash"), label: strings("clip_delete"), enabled: count > 1) { model.deleteSelectedClip() }
            Spacer()
            if model.busy { ProgressView().tint(theme.accent) }
        }
        .padding(.vertical, 2)
    }

    // MARK: - 도구 영역

    @ViewBuilder
    private func toolArea(wide: Bool) -> some View {
        ZStack {
            if let tool = model.activeTool {
                VStack(spacing: 0) {
                    ScrollView(.vertical, showsIndicators: false) {
                        panel(tool).padding(.vertical, 8)
                    }
                    .scrollBounceBehavior(.basedOnSize)
                    HStack {
                        if tool != .trim || true {
                            EditorButton(title: strings("action_reset"), systemImage: "arrow.counterclockwise", kind: .tonal) { model.resetTool() }
                        }
                        Spacer(minLength: 12)
                        EditorButton(title: strings("action_apply"), systemImage: "checkmark") { model.applyTool() }
                    }
                    .padding(.horizontal, 16).padding(.vertical, 8)
                }
                .transition(.opacity)
            } else if model.hasTimelineSelection {
                ScrollView(.vertical, showsIndicators: false) { TimelineSelectionPanel(model: model) }
                    .scrollBounceBehavior(.basedOnSize)
                    .transition(.opacity)
            } else if !model.railTools.isEmpty {
                rail(wide: wide).transition(.opacity)
            }
        }
        .animation(reduceMotion ? nil : .easeInOut(duration: 0.2), value: model.activeTool)
    }

    @ViewBuilder
    private func panel(_ tool: VideoTool) -> some View {
        switch tool {
        case .trim: EmptyView()
        case .crop: CropPanel(model: model)
        case .rotate: RotatePanel(model: model)
        case .adjust: AdjustPanel(model: model)
        case .filter: FilterPanel(model: model)
        case .canvas: CanvasPanel(model: model)
        case .speed: SpeedPanel(model: model)
        case .audio: VideoAudioPanel(model: model)
        case .text: TextPanel(model: model)
        case .sticker:
            VStack(spacing: 8) {
                TimedRangeRow(model: model, help: strings("sticker_help"))
                StickerPanel(model: model)
            }
        case .privacy:
            VStack(spacing: 8) {
                TimedRangeRow(model: model, help: strings("mask_help"))
                PrivacyPanel(model: model)
            }
        case .watermark: WatermarkPanel(model: model)
        }
    }

    private func rail(wide: Bool) -> some View {
        Group {
            if wide {
                LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 8), count: 3), spacing: 8) {
                    ForEach(model.railTools, id: \.self) { tool in railItem(tool, card: true) }
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

    private func railItem(_ tool: VideoTool, card: Bool) -> some View {
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

extension VideoTool {
    var labelKey: String { "tool_\(rawValue)" }

    var icon: Image {
        switch self {
        case .trim: Icons.image("trim")
        case .crop: Icons.image("crop")
        case .rotate: Icons.image("rotate_right")
        case .canvas: Icons.image("aspect")
        case .adjust: Icons.image("adjust")
        case .filter: Icons.image("filter")
        case .speed: Icons.image("speed")
        case .audio: Icons.image("volume")
        case .text: Icons.image("text")
        case .sticker: Icons.image("sticker")
        case .privacy: Icons.image("privacy")
        case .watermark: Icons.image("watermark")
        }
    }
}

/// AVPlayer를 그리는 층.
struct PlayerSurface: UIViewRepresentable {
    let player: AVPlayer

    final class PlayerView: UIView {
        override static var layerClass: AnyClass { AVPlayerLayer.self }
        var playerLayer: AVPlayerLayer { layer as! AVPlayerLayer }
    }

    func makeUIView(context _: Context) -> PlayerView {
        let view = PlayerView()
        view.playerLayer.player = player
        view.playerLayer.videoGravity = .resizeAspect
        view.backgroundColor = .clear
        return view
    }

    func updateUIView(_ view: PlayerView, context _: Context) { view.playerLayer.player = player }
}

/// 자르기·회전: 고른 클립의 지금 프레임 위에 자르기 틀을 그린다.
struct VideoCropCanvas: View {
    let model: VideoEditorModel
    @Environment(\.editorTheme) private var theme
    @State private var bounds: CGImage?
    @State private var handle: CropHandle?

    var body: some View {
        GeometryReader { geo in
            let area = CGRect(origin: .zero, size: geo.size).insetBy(dx: 28, dy: 28)
            if let source = model.selectedSource {
                let geometry = model.currentGeometry
                let math = GeometryMath(source: source.uprightSize, geometry: geometry)
                let rect = fit(math.bounds, in: area)
                let crop = geometry.crop
                let cropRect = CGRect(x: rect.minX + crop.left * rect.width, y: rect.minY + crop.top * rect.height,
                                      width: crop.width * rect.width, height: crop.height * rect.height)
                ZStack {
                    if let bounds {
                        Image(decorative: bounds, scale: 1).resizable().frame(width: rect.width, height: rect.height).position(x: rect.midX, y: rect.midY)
                    }
                    CropFrameShape(outer: CGRect(x: -2000, y: -2000, width: 6000, height: 6000), inner: cropRect)
                        .fill(Color.black.opacity(0.55), style: FillStyle(eoFill: true)).allowsHitTesting(false)
                    Path(cropRect).stroke(Color.white, lineWidth: 1).allowsHitTesting(false)
                    if model.activeTool == .crop {
                        ForEach(0..<4, id: \.self) { i in
                            let p = [CGPoint(x: cropRect.minX, y: cropRect.minY), CGPoint(x: cropRect.maxX, y: cropRect.minY),
                                     CGPoint(x: cropRect.maxX, y: cropRect.maxY), CGPoint(x: cropRect.minX, y: cropRect.maxY)][i]
                            Circle().fill(Color.white).frame(width: 14, height: 14).position(p).allowsHitTesting(false)
                        }
                    }
                }
                .contentShape(Rectangle())
                .gesture(model.activeTool == .crop ? DragGesture(minimumDistance: 0)
                    .onChanged { g in
                        if handle == nil {
                            handle = hit(g.startLocation, cropRect)
                            if let handle { model.beginCropDrag(handle) }
                        }
                        if handle != nil { model.dragCrop(dx: g.translation.width / rect.width, dy: g.translation.height / rect.height) }
                    }
                    .onEnded { _ in if handle != nil { model.endCropDrag() }; handle = nil } : nil)
                .task(id: RenderKey(frame: model.cropFrame.map(ObjectIdentifier.init), geometry: geometry, width: Int(rect.width))) {
                    guard let frame = model.cropFrame, rect.width > 0 else { return }
                    let edit = { var e = PhotoEdit(grainSeed: 0); e.geometry = geometry; return e }()
                    let upright = source.uprightSize
                    let output = PixelSizeValue(width: max(1, Int(rect.width * 2)), height: max(1, Int(rect.height * 2)))
                    let image = UprightImage(cgImage: frame)
                    bounds = await Task.detached {
                        try? PhotoRenderer.render(image: image, uprightSize: upright, edit: edit, crop: .full, output: output,
                                                  background: nil, includeCanvasEffects: false, applyColor: false)
                    }.value
                }
            }
        }
    }

    private struct RenderKey: Hashable {
        let frame: ObjectIdentifier?
        let geometry: GeometryState
        let width: Int
    }

    private func hit(_ p: CGPoint, _ r: CGRect) -> CropHandle? {
        let reach: CGFloat = 24
        func near(_ a: CGFloat, _ b: CGFloat) -> Bool { abs(a - b) <= reach }
        let l = near(p.x, r.minX), rt = near(p.x, r.maxX), t = near(p.y, r.minY), b = near(p.y, r.maxY)
        if t && l { return .topLeft }
        if t && rt { return .topRight }
        if b && l { return .bottomLeft }
        if b && rt { return .bottomRight }
        if t { return .top }
        if b { return .bottom }
        if l { return .left }
        if rt { return .right }
        return r.contains(p) ? .move : nil
    }
}

// MARK: - 패널

struct CanvasPanel: View {
    let model: VideoEditorModel
    @Environment(\.strings) private var strings
    @Environment(\.editorTheme) private var theme
    private let ratios: [(Int, Int)?] = [nil, (9, 16), (16, 9), (1, 1), (4, 5), (3, 4)]

    var body: some View {
        let canvas = model.displayed.canvas
        let current = canvas.aspectWidth.map { ($0, canvas.aspectHeight ?? 1) }
        VStack(alignment: .leading, spacing: 8) {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(ratios.indices, id: \.self) { i in
                        let ratio = ratios[i]
                        ChoiceChip(title: ratio.map { "\($0.0):\($0.1)" } ?? strings("canvas_original"),
                                   selected: ratio?.0 == current?.0 && ratio?.1 == current?.1) { model.setCanvas(aspect: ratio, fit: canvas.fit) }
                    }
                }
                .padding(.horizontal, 16)
            }
            chips(CanvasFit.allCases.map(\.rawValue), selected: canvas.fit.rawValue, title: { strings("canvas_\($0)") }) {
                model.setCanvas(aspect: current, fit: CanvasFit(rawValue: $0) ?? .fit)
            }
            Text(strings("canvas_help")).scaledFont(13).foregroundStyle(theme.foregroundMuted).padding(.horizontal, 16)
        }
    }
}

struct SpeedPanel: View {
    let model: VideoEditorModel
    @Environment(\.strings) private var strings
    @Environment(\.editorTheme) private var theme

    var body: some View {
        let speed = model.selectedClip?.speed ?? 1
        let committed = model.history.current.clips.first { $0.id == model.selectedClipId }?.outputDuration ?? 0
        VStack(alignment: .leading, spacing: 8) {
            Text(strings("speed_result", formatTime(committed), formatTime(model.selectedClip?.outputDuration ?? 0)))
                .scaledFont(14, weight: .medium).foregroundStyle(theme.foreground).padding(.horizontal, 16)
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(VideoClipState.speedPresets, id: \.self) { value in
                        let label = String(format: "%g", value)
                        ChoiceChip(title: strings("speed_value", label), selected: value == speed) { model.selectSpeed(value) }
                    }
                }
                .padding(.horizontal, 16)
            }
        }
    }
}

struct AudioPanel: View {
    let model: VideoEditorModel
    @Environment(\.strings) private var strings
    @Environment(\.editorTheme) private var theme

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(strings("original_sound")).scaledFont(14, weight: .semibold).foregroundStyle(theme.foreground).padding(.horizontal, 16)
            if model.selectedHasAudio, let clip = model.selectedClip {
                Toggle(strings("audio_mute"), isOn: Binding(get: { clip.muted }, set: { _ in model.toggleMute() }))
                    .tint(theme.accent).padding(.horizontal, 16).frame(minHeight: 44)
                ValueSlider(label: strings("audio_volume"), value: clip.volume * 100, range: 0...200, format: { "\(Int($0.rounded()))%" },
                            onChange: { v in model.setVolume(abs(v - 100) < 4 ? 1 : v / 100, commit: false) },
                            onFinish: { model.commitGesture() })
            } else {
                Text(strings("audio_none")).scaledFont(13).foregroundStyle(theme.foregroundMuted).padding(.horizontal, 16)
            }
        }
    }
}

// MARK: - 선택기

/// 시스템 영상 선택기. 고른 파일을 임시 폴더에 복사해 넘긴다. 닫으면 빈 결과.
struct VideoPicker: UIViewControllerRepresentable {
    var limit = 1
    let onPick: (PickedFiles) -> Void

    func makeUIViewController(context: Context) -> PHPickerViewController {
        var config = PHPickerConfiguration()
        config.filter = .videos
        config.selectionLimit = max(1, limit)
        config.selection = .ordered
        config.preferredAssetRepresentationMode = .current
        let picker = PHPickerViewController(configuration: config)
        picker.delegate = context.coordinator
        return picker
    }

    func updateUIViewController(_: PHPickerViewController, context _: Context) {}
    func makeCoordinator() -> Coordinator { Coordinator(onPick: onPick) }

    final class Coordinator: NSObject, PHPickerViewControllerDelegate {
        let onPick: (PickedFiles) -> Void
        private var done = false
        init(onPick: @escaping (PickedFiles) -> Void) { self.onPick = onPick }

        func picker(_: PHPickerViewController, didFinishPicking results: [PHPickerResult]) {
            guard !done else { return }
            done = true
            let providers = results.map(\.itemProvider)
            guard !providers.isEmpty else { onPick(PickedFiles()); return }
            let onPick = onPick
            Task { @MainActor in
                var files = PickedFiles()
                for provider in providers {
                    files.add(await Self.copy(VideoEditorModel.ItemBox(provider: provider)))
                }
                onPick(files)
            }
        }

        nonisolated static func copy(_ box: VideoEditorModel.ItemBox) async -> Result<URL, EditorError> {
            await withCheckedContinuation { continuation in
                box.provider.loadFileRepresentation(forTypeIdentifier: UTType.movie.identifier) { url, error in
                    // 이 콜백이 끝나면 url은 지워지므로 그 전에 복사한다.
                    continuation.resume(returning: Result { () throws(EditorError) -> URL in
                        guard let url else { throw .logged(.sourceUnavailable, "video picker: \(String(describing: error))") }
                        return try VideoEditorModel.copyToTemporary(url)
                    })
                }
            }
        }
    }
}

/// 시스템 카메라(영상). 닫으면 `nil`.
struct VideoCameraPicker: UIViewControllerRepresentable {
    let onCapture: (Result<URL, EditorError>?) -> Void

    func makeUIViewController(context: Context) -> UIImagePickerController {
        let picker = UIImagePickerController()
        picker.sourceType = .camera
        picker.mediaTypes = [UTType.movie.identifier]
        picker.cameraCaptureMode = .video
        picker.delegate = context.coordinator
        return picker
    }

    func updateUIViewController(_: UIImagePickerController, context _: Context) {}
    func makeCoordinator() -> Coordinator { Coordinator(onCapture: onCapture) }

    final class Coordinator: NSObject, UIImagePickerControllerDelegate, UINavigationControllerDelegate {
        let onCapture: (Result<URL, EditorError>?) -> Void
        private var done = false
        init(onCapture: @escaping (Result<URL, EditorError>?) -> Void) { self.onCapture = onCapture }

        func imagePickerController(_: UIImagePickerController, didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey: Any]) {
            guard !done else { return }
            done = true
            guard let url = info[.mediaURL] as? URL else { onCapture(.failure(.logged(.sourceUnavailable, "capture: no media url"))); return }
            onCapture(Result { () throws(EditorError) -> URL in try VideoEditorModel.copyToTemporary(url) })
        }

        func imagePickerControllerDidCancel(_: UIImagePickerController) {
            guard !done else { return }
            done = true
            onCapture(nil)
        }
    }
}

/// 고른 가리기 상자 조작: 안을 끌면 옮기고, 모서리를 끌면 크기를 바꾼다. 밖을 끌면 새 가리기를 그린다.
struct MaskHandles: View {
    let model: VideoEditorModel
    let rect: CGRect
    @Environment(\.editorTheme) private var theme
    @Environment(\.strings) private var strings
    @State private var start: NRect?

    var body: some View {
        if let id = model.selectedMaskId, let timed = model.displayed.masks.first(where: { $0.mask.id == id }), let r = box(timed.mask.shape) {
            let frame = CGRect(x: rect.minX + r.left * rect.width, y: rect.minY + r.top * rect.height, width: r.width * rect.width, height: r.height * rect.height)
            ZStack {
                Color.clear.contentShape(Rectangle())
                    .frame(width: frame.width, height: frame.height).position(x: frame.midX, y: frame.midY)
                    .gesture(drag(r, corner: nil))
                    .accessibilityHidden(true)
                ForEach(0..<4, id: \.self) { corner in
                    let p = [CGPoint(x: frame.minX, y: frame.minY), CGPoint(x: frame.maxX, y: frame.minY),
                             CGPoint(x: frame.maxX, y: frame.maxY), CGPoint(x: frame.minX, y: frame.maxY)][corner]
                    Circle().fill(theme.accent).overlay { Circle().stroke(Color.white, lineWidth: 2) }
                        .frame(width: 18, height: 18)
                        .frame(width: 44, height: 44).contentShape(Rectangle())
                        .position(p)
                        .gesture(drag(r, corner: corner))
                        .accessibilityHidden(true)
                }
            }
        }
    }

    private func box(_ shape: PrivacyMask.Shape) -> NRect? {
        switch shape {
        case .rectangle(let r), .ellipse(let r): r
        case .brush: nil
        }
    }

    private func drag(_ current: NRect, corner: Int?) -> some Gesture {
        DragGesture(minimumDistance: 1)
            .onChanged { g in
                if start == nil { start = current }
                guard let start else { return }
                model.reshapeSelectedMask(from: start, corner: corner, dx: g.translation.width / max(1, rect.width),
                                          dy: g.translation.height / max(1, rect.height))
            }
            .onEnded { _ in
                start = nil
                model.commitGesture()
            }
    }
}

