import PhotosUI
import SwiftUI

/// 편집 화면 뿌리. 상태에 따라 사진 선택·불러오기·오류·편집 화면을 보인다.
struct EditorRoot: View {
    let model: EditorModel
    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        let ui = model.request.ui
        let theme = EditorTheme.make(ui, systemDark: colorScheme == .dark)
        content
            .environment(\.editorTheme, theme)
            .environment(\.strings, Strings(localeIdentifier: ui.localeIdentifier))
            .environment(\.haptics, Haptics(enabled: ui.enableHaptics))
            .environment(\.locale, ui.localeIdentifier.map(Locale.init(identifier:)) ?? .current)
            .preferredColorScheme(ui.themeMode == .system ? nil : (theme.isDark ? .dark : .light))
            .tint(theme.accent)
            .onAppear { if model.phase == .loading || model.phase == .awaitingSource { model.start() } }
    }

    @ViewBuilder
    private var content: some View {
        switch model.phase {
        case .awaitingSource:
            PhotoPicker(limit: model.initialPickLimit) { model.picked($0) }.ignoresSafeArea()
        case .loading:
            StatusView(model: model, error: nil)
        case .failed(let error):
            StatusView(model: model, error: error)
        case .ready:
            EditorScreen(model: model)
        }
    }
}

/// 불러오는 중 또는 열기 실패 화면.
private struct StatusView: View {
    let model: EditorModel
    let error: EditorError?
    @Environment(\.editorTheme) private var theme
    @Environment(\.strings) private var strings

    var body: some View {
        VStack(spacing: 16) {
            if let error {
                Image(systemName: "exclamationmark.triangle").font(.system(size: 36)).foregroundStyle(theme.error)
                Text(strings(errorKey(error.code))).font(.system(size: 16)).foregroundStyle(theme.foreground)
                    .multilineTextAlignment(.center).padding(.horizontal, 32)
                if case .pick = model.request.input, error.suggestedAction == .chooseAnotherSource {
                    EditorButton(title: strings("action_choose_another")) { model.start() }
                }
                EditorButton(title: strings("action_close"), kind: .tonal) { model.finish(.failure(error)) }
            } else {
                ProgressView().controlSize(.large).tint(theme.accent)
                    .accessibilityLabel(strings("loading"))
                Text(strings("loading")).font(.system(size: 15)).foregroundStyle(theme.foregroundMuted).accessibilityHidden(true)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(theme.background.ignoresSafeArea())
    }
}

func errorKey(_ code: EditorErrorCode) -> String {
    let snake = code.rawValue.replacingOccurrences(of: "([a-z])([A-Z])", with: "$1_$2", options: .regularExpression).lowercased()
    return "error_\(snake)"
}

/// 편집 화면. 폭이 넓거나 가로면 도구를 오른쪽 360pt 열에, 아니면 아래에 둔다.
struct EditorScreen: View {
    @Bindable var model: EditorModel
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
                        canvas
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
                        toolArea(wide: false)
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
        } message: {
            Text(strings("discard_message"))
        }
        .alert(strings("draft_title", toolName), isPresented: $model.showDraftDialog) {
            Button(strings("draft_apply", toolName)) { model.applyTool() }
            Button(strings("draft_discard", toolName), role: .destructive) { model.discardTool() }
            Button(strings("action_keep_editing"), role: .cancel) {}
        } message: {
            Text(strings("draft_message", toolName))
        }
        .alert(strings("export_failed_title"), isPresented: exportFailed) {
            if exportError?.recoverable == true {
                Button(strings("action_retry")) { model.retryExport() }
            }
            Button(strings("action_keep_editing"), role: .cancel) { model.dismissExportError() }
        } message: {
            Text(exportError.map { strings(errorKey($0.code)) } ?? "")
        }
        .sheet(isPresented: $model.showAddPicker) {
            PhotoPicker(limit: max(1, model.pageLimit - model.pages.count)) { model.picked($0, append: true) }
                .ignoresSafeArea()
        }
        .onChange(of: model.export) { _, new in
            UIApplication.shared.isIdleTimerDisabled = new != nil
        }
    }

    private var exportFraction: Double? {
        if case .running(_, let fraction) = model.export { return fraction }
        return nil
    }

    private var toolName: String { model.activeTool.map { strings($0.labelKey) } ?? "" }

    private var exportError: EditorError? {
        if case .failed(let error) = model.export { return error }
        return nil
    }

    private var exportFailed: Binding<Bool> {
        Binding(get: { exportError != nil }, set: { if !$0 { model.dismissExportError() } })
    }

    // MARK: - 상단 바

    private var topBar: some View {
        ZStack {
            if let tool = model.activeTool {
                Text(strings(tool.labelKey)).font(.system(size: 17, weight: .semibold)).foregroundStyle(theme.foreground)
                    .lineLimit(1).padding(.horizontal, 100)
                    .accessibilityAddTraits(.isHeader)
            }
            HStack {
                Button { model.activeTool == nil ? model.requestClose() : model.onBack() } label: {
                    Image(systemName: model.activeTool == nil ? "xmark" : "chevron.left")
                        .font(.system(size: model.activeTool == nil ? 17 : 20, weight: .semibold))
                        .foregroundStyle(theme.foreground)
                        .frame(width: 42, height: 42)
                        .editorGlass(Circle(), theme: theme)
                        .frame(minWidth: 48, minHeight: 48)
                        .contentShape(Rectangle())
                }
                .buttonStyle(PressFadeStyle())
                .accessibilityLabel(strings(model.activeTool == nil ? "action_close" : "action_back"))
                Spacer()
                Button { model.save() } label: {
                    Text(strings("action_save")).font(.system(size: 16, weight: .semibold))
                        .foregroundStyle(theme.onAccent)
                        .padding(.horizontal, 18)
                        .frame(height: 42)
                        .editorGlass(Capsule(), theme: theme, tint: theme.accent)
                        .frame(minHeight: 48)
                        .contentShape(Rectangle())
                }
                .buttonStyle(PressFadeStyle())
                .disabled(model.export != nil)
            }
        }
        .padding(.horizontal, 12)
        .frame(height: 60)
        .editorChrome(hidden: model.chromeHidden, reduceMotion: reduceMotion) { model.showChrome() }
    }

    // MARK: - 사진 영역

    private var canvas: some View {
        EditorCanvas(model: model)
            .overlay(alignment: .top) {
                if model.activeTool == nil || model.activeTool?.isDraft == false {
                    historyPill
                        .padding(.top, 12)
                        .editorChrome(hidden: model.chromeHidden, reduceMotion: reduceMotion) { model.showChrome() }
                }
            }
            .overlay(alignment: .bottom) {
                if let notice = model.notice {
                    Text(strings(notice)).font(.system(size: 14)).foregroundStyle(theme.foreground)
                        .padding(.horizontal, 16).padding(.vertical, 10)
                        .background(theme.raised.opacity(0.94), in: RoundedRectangle(cornerRadius: theme.radius))
                        .padding(12)
                        .accessibilityAddTraits(.updatesFrequently)
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    @ViewBuilder
    private var historyPill: some View {
        let ui = model.request.config
        let showCompare = model.activeTool == nil && model.isDirty
        if ui.allowUndo || showCompare {
            HStack(spacing: 0) {
                if ui.allowUndo {
                    IconButton(icon: Icons.image("undo"), label: strings("action_undo"), enabled: model.canUndo) { model.undo() }
                }
                if ui.allowRedo {
                    IconButton(icon: Icons.image("redo"), label: strings("action_redo"), enabled: model.canRedo) { model.redo() }
                }
                if showCompare {
                    CompareButton(model: model)
                }
            }
            .padding(.horizontal, 4)
            .editorGlass(Capsule(), theme: theme)
        }
    }

    // MARK: - 도구 영역

    @ViewBuilder
    private func toolArea(wide: Bool) -> some View {
        ZStack {
            if let tool = model.activeTool {
                ToolPanel(model: model, tool: tool)
                    .transition(.opacity)
            } else if !model.tools.isEmpty || model.pageLimit > 1 {
                VStack(spacing: 0) {
                    if model.pageLimit > 1 { PageStrip(model: model) }
                    if wide { toolGrid } else { toolRail }
                }
                .transition(.opacity)
                .editorChrome(hidden: model.chromeHidden, reduceMotion: reduceMotion) { model.showChrome() }
            }
        }
        .animation(reduceMotion ? nil : .easeInOut(duration: 0.2), value: model.activeTool)
    }

    private var toolRail: some View {
        Group {
            if model.tools.count <= 5 {
                HStack(spacing: 0) {
                    ForEach(model.tools, id: \.self) { tool in
                        railItem(tool).frame(maxWidth: .infinity)
                    }
                }
                .padding(.horizontal, 8)
            } else {
                // 도구를 닫고 돌아오면 마지막으로 연 도구가 보이게 한다.
                ScrollViewReader { proxy in
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 4) {
                            ForEach(model.tools, id: \.self) { railItem($0).id($0) }
                        }
                        .padding(.horizontal, 8)
                    }
                    .onAppear { if let last = model.lastTool { proxy.scrollTo(last, anchor: .center) } }
                }
            }
        }
        .padding(.vertical, 10)
    }

    private func railItem(_ tool: ImageTool) -> some View {
        Button { model.selectTool(tool) } label: {
            VStack(spacing: 4) {
                tool.icon.resizable().scaledToFit().frame(width: 24, height: 24)
                    .padding(8)
                    .background(theme.raised, in: RoundedRectangle(cornerRadius: theme.radius / 2))
                Text(strings(tool.labelKey)).font(.system(size: 12, weight: .medium)).lineLimit(1)
            }
            .foregroundStyle(theme.foreground)
            .frame(minWidth: 72, minHeight: 48)
            .contentShape(Rectangle())
        }
        .buttonStyle(PressFadeStyle())
    }

    private var toolGrid: some View {
        ScrollView {
            LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 8), count: 3), spacing: 8) {
                ForEach(model.tools, id: \.self) { tool in
                    Button { model.selectTool(tool) } label: {
                        VStack(spacing: 6) {
                            tool.icon.resizable().scaledToFit().frame(width: 24, height: 24)
                            Text(strings(tool.labelKey)).font(.system(size: 12, weight: .medium)).lineLimit(1)
                        }
                        .foregroundStyle(theme.foreground)
                        .frame(maxWidth: .infinity, minHeight: 76)
                        .background(theme.surface, in: RoundedRectangle(cornerRadius: theme.radius))
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(PressFadeStyle())
                }
            }
            .padding(16)
        }
    }

    // MARK: - 저장 중

    @ViewBuilder
    private var exportOverlay: some View {
        if let export = model.export, export != .failed(exportError ?? EditorError(code: .unknown)) {
            let stage: String = switch export {
            case .cancelling: strings("export_cancelling")
            case .running(let stage?, _) where model.request.ui.showExportProgress:
                switch stage {
                case .preparing: strings("export_preparing")
                case .rendering: strings("export_rendering")
                case .encoding: strings("export_encoding")
                case .finalizing: strings("export_finalizing")
                }
            default: strings("export_exporting")
            }
            ZStack {
                theme.background.opacity(0.82).ignoresSafeArea()
                    .contentShape(Rectangle())
                    .onTapGesture {}
                VStack(spacing: 16) {
                    ProgressRing(progress: exportFraction)
                    Text(stage).font(.system(size: 15, weight: .medium)).foregroundStyle(theme.foreground)
                        .accessibilityAddTraits(.updatesFrequently)
                    if case .running = export {
                        EditorButton(title: strings("action_cancel"), kind: .tonal) { model.cancelExport() }
                    }
                }
                .padding(24)
                .frame(minWidth: 240, maxWidth: 320)
                .background(theme.surface, in: RoundedRectangle(cornerRadius: theme.radius))
            }
        }
    }
}

/// 누르는 동안 원본을 보인다. VoiceOver에서는 눌러서 켜고 끈다.
private struct CompareButton: View {
    let model: EditorModel
    @Environment(\.strings) private var strings
    @Environment(\.editorTheme) private var theme
    @Environment(\.accessibilityVoiceOverEnabled) private var voiceOver

    var body: some View {
        Icons.image("compare").resizable().scaledToFit().frame(width: 24, height: 24)
            .foregroundStyle(model.showingOriginal ? theme.accent : theme.foreground)
            .frame(minWidth: 48, minHeight: 48)
            .contentShape(Rectangle())
            .onLongPressGesture(minimumDuration: 0, maximumDistance: 40, perform: {
                if voiceOver { model.showingOriginal.toggle() }
            }, onPressingChanged: { pressing in
                if !voiceOver { model.showingOriginal = pressing }
            })
            .accessibilityElement()
            .accessibilityLabel(strings("action_compare"))
            .accessibilityAddTraits(.isToggle)
            .accessibilityValue(model.showingOriginal ? "1" : "0")
            .accessibilityAction { model.showingOriginal.toggle() }
    }
}

/// 시스템 사진 선택기. 권한이 필요 없다. 닫으면 빈 목록을 넘긴다.
struct PhotoPicker: UIViewControllerRepresentable {
    var limit = 1
    let onPick: ([NSItemProvider]) -> Void

    func makeUIViewController(context: Context) -> PHPickerViewController {
        var config = PHPickerConfiguration()
        config.filter = .images
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
        let onPick: ([NSItemProvider]) -> Void
        private var done = false

        init(onPick: @escaping ([NSItemProvider]) -> Void) {
            self.onPick = onPick
        }

        func picker(_: PHPickerViewController, didFinishPicking results: [PHPickerResult]) {
            guard !done else { return }
            done = true
            onPick(results.map(\.itemProvider))
        }
    }
}

/// 여러 장 목록. 누르면 그 장을 편집하고, 길게 누르면 순서 바꾸기·빼기가 나온다.
struct PageStrip: View {
    let model: EditorModel
    @Environment(\.editorTheme) private var theme
    @Environment(\.strings) private var strings

    var body: some View {
        ScrollViewReader { proxy in
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(Array(model.pages.enumerated()), id: \.offset) { index, page in
                        thumbnail(index, page).id(index)
                    }
                    if model.pages.count < model.pageLimit {
                        Button { model.requestAddPages() } label: {
                            Image(systemName: "plus").font(.system(size: 20, weight: .semibold)).foregroundStyle(theme.foreground)
                                .frame(width: 52, height: 52)
                                .background(theme.raised, in: RoundedRectangle(cornerRadius: theme.radius / 2))
                                .frame(minWidth: 52, minHeight: 56)
                                .contentShape(Rectangle())
                        }
                        .buttonStyle(PressFadeStyle())
                        .accessibilityLabel(strings("page_add"))
                    }
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 6)
            }
            .onChange(of: model.pageIndex) { _, new in proxy.scrollTo(new, anchor: .center) }
        }
    }

    private func thumbnail(_ index: Int, _ page: EditorPage) -> some View {
        let selected = index == model.pageIndex
        return Button { model.selectPage(index) } label: {
            ZStack(alignment: .bottomTrailing) {
                Group {
                    if let image = page.thumbnail {
                        Image(decorative: image, scale: 1).resizable().scaledToFill()
                    } else {
                        theme.raised
                    }
                }
                .frame(width: 52, height: 52)
                .clipShape(RoundedRectangle(cornerRadius: theme.radius / 2))
                .overlay { RoundedRectangle(cornerRadius: theme.radius / 2).strokeBorder(theme.accent, lineWidth: selected ? 2.5 : 0) }
                Text("\(index + 1)").font(.system(size: 11, weight: .bold).monospacedDigit())
                    .foregroundStyle(theme.onAccent)
                    .padding(.horizontal, 5).padding(.vertical, 1)
                    .background(selected ? theme.accent : Color.black.opacity(0.6), in: Capsule())
                    .offset(x: 3, y: 3)
            }
            .frame(minHeight: 56)
        }
        .buttonStyle(PressFadeStyle())
        .contextMenu {
            if index > 0 { Button(strings("page_move_earlier"), systemImage: "arrow.left") { model.movePage(index, by: -1) } }
            if index < model.pages.count - 1 { Button(strings("page_move_later"), systemImage: "arrow.right") { model.movePage(index, by: 1) } }
            if model.pages.count > 1 { Button(strings("page_remove"), systemImage: "trash", role: .destructive) { model.removePage(index) } }
        }
        .accessibilityLabel(strings("page_label", index + 1))
        .accessibilityValue(strings("page_position", index + 1, model.pages.count))
        .accessibilityAddTraits(selected ? [.isSelected] : [])
    }
}
