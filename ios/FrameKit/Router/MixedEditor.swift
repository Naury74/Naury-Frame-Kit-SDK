import Observation
import SwiftUI

/// 사진·영상을 섞어 고른 것을 하나씩 편집한다. 아래 줄에서 고른 항목의 종류에 맞는 편집 화면을 보이고, 저장은 한 번에 한다.
@MainActor
@Observable
final class MixedEditorModel {
    /// 한 번에 편집할 수 있는 최대 수.
    static let maxItems = 20

    final class Item: Identifiable {
        let id: UUID
        let url: URL
        let kind: SourceKind
        let editor: RouterModel.Editor
        /// 저장을 마친 결과. 있으면 다시 편집할 수 없다.
        var outputs: [EditedMedia]?

        init(id: UUID, url: URL, kind: SourceKind, editor: RouterModel.Editor) {
            self.id = id
            self.url = url
            self.kind = kind
            self.editor = editor
        }
    }

    enum SaveState: Equatable {
        case running(index: Int, total: Int)
        case cancelling
        case failed(EditorError)
    }

    let request: FrameKitRequest
    private let onFinish: (FrameKitResult) -> Void
    private var delivered = false
    private(set) var items: [Item] = []
    private(set) var thumbnails: [UUID: CGImage] = [:]
    var selected = 0
    private(set) var save: SaveState?
    var showDiscardDialog = false
    private var saveTask: Task<Void, Never>?

    init(request: FrameKitRequest, urls: [URL], kinds: [SourceKind], onFinish: @escaping (FrameKitResult) -> Void) {
        self.request = request
        self.onFinish = onFinish
        for (url, kind) in zip(urls, kinds).prefix(Self.maxItems) {
            // 항목마다 편집기 하나. 사진은 한 장으로 열고, 영상은 일반 영상 편집기처럼 나누고 클립을 더할 수 있다. 결과는 항목마다 하나다.
            let id = UUID()
            // 항목 편집기가 스스로 끝나면(열기 실패 화면의 닫기) 그 항목을 뺀다.
            let itemFinished: (FrameKitResult) -> Void = { [weak self] result in self?.itemFinished(id, result) }
            let item: Item
            if kind.mediaKind == .video {
                item = Item(id: id, url: url, kind: kind, editor: .video(VideoEditorModel(request: request.videoRequest(.file(url)), onFinish: itemFinished)))
            } else {
                var image = request.imageRequest(.file(url))
                image.config.maxImageCount = 1
                item = Item(id: id, url: url, kind: kind, editor: .image(EditorModel(request: image, onFinish: itemFinished)))
            }
            items.append(item)
        }
        for item in items {
            // 보이지 않는 항목도 미리 열어 둬야 한 번에 저장할 수 있다.
            switch item.editor {
            case .image(let model): model.start()
            case .video(let model): model.start()
            default: break
            }
            let (id, url, kind) = (item.id, item.url, item.kind)
            Task { [weak self] in
                let image = await SourceInspector.thumbnail(url, kind: kind, maxSide: 160)
                self?.thumbnails[id] = image
            }
        }
    }

    /// 항목 편집기가 결과를 냈다. 저장 성공은 `saveOne`이 받고, 실패·취소는 그 항목을 뺀다.
    private func itemFinished(_ id: UUID, _ result: FrameKitResult) {
        guard !delivered, save == nil, let index = items.firstIndex(where: { $0.id == id }) else { return }
        switch result {
        case .success: return
        case .failure, .cancelled: remove(at: index, result: result)
        }
    }

    /// 항목을 뺀다. 남은 항목이 없으면 그 결과로 끝낸다.
    private func remove(at index: Int, result: FrameKitResult) {
        let item = items.remove(at: index)
        thumbnails[item.id] = nil
        item.outputs?.forEach { FrameKitOutputs.delete($0.url) }
        guard !items.isEmpty else { finish(result); return }
        selected = min(selected > index ? selected - 1 : selected, items.count - 1)
    }

    var current: Item? { items.indices.contains(selected) ? items[selected] : nil }

    /// 지금 편집기에서 도구를 쓰는 중이면 혼합 화면의 상단 바·항목 줄을 숨긴다.
    var toolActive: Bool {
        switch current?.editor {
        case .image(let model): model.activeTool != nil
        case .video(let model): model.activeTool != nil
        default: false
        }
    }

    func isDirty(_ item: Item) -> Bool {
        switch item.editor {
        case .image(let model): model.isDirty
        case .video(let model): model.isDirty
        default: false
        }
    }

    func select(_ index: Int) {
        guard items.indices.contains(index), save == nil else { return }
        if case .video(let model) = current?.editor { model.pause() }
        selected = index
    }

    // MARK: - 저장

    func saveAll() {
        guard save == nil, !toolActive else { return }
        if case .video(let model) = current?.editor { model.pause() }
        saveTask = Task { await runSave() }
    }

    private func runSave() async {
        let pending = items.indices.filter { items[$0].outputs == nil }
        for (step, index) in pending.enumerated() {
            save = .running(index: step + 1, total: pending.count)
            let item = items[index]
            let outcome = await saveOne(item)
            switch outcome {
            case .saved(let outputs):
                item.outputs = outputs
            case .cancelled:
                save = nil
                return
            case .failed(let error):
                selected = index
                save = .failed(error)
                return
            }
        }
        let outputs = items.flatMap { $0.outputs ?? [] }
        guard let first = outputs.first else { finish(.failure(.logged(.unknown, "mixed: no outputs"))); return }
        finish(.success(output: first, outputs: outputs))
    }

    private enum Outcome { case saved([EditedMedia]), cancelled, failed(EditorError) }

    /// 편집기의 저장을 부르고 끝날 때까지 상태를 지켜본다.
    private func saveOne(_ item: Item) async -> Outcome {
        // 아직 여는 중이면 기다린다. 열지 못한 항목은 다시 해도 안 되므로 그 항목을 빼도록 고른다.
        while !Task.isCancelled {
            switch Self.openState(item.editor) {
            case .loading:
                try? await Task.sleep(nanoseconds: 100_000_000)
                continue
            case .failed(let error):
                return .failed(EditorError(code: error.code, recoverable: false, suggestedAction: error.suggestedAction))
            case .ready:
                break
            }
            break
        }
        if Task.isCancelled { return .cancelled }
        var result: FrameKitResult?
        let started: Bool
        switch item.editor {
        case .image(let model):
            model.onExternalFinish = { result = $0 }
            started = model.phase == .ready
            model.save()
        case .video(let model):
            model.onExternalFinish = { result = $0 }
            started = model.phase == .ready
            model.save()
        default:
            started = false
        }
        guard started else { return .failed(.logged(.invalidSource, "mixed item not ready")) }
        while !Task.isCancelled {
            if case .success(_, let outputs) = result { return .saved(outputs) }
            if case .failure(let error) = result { return .failed(error) }
            switch item.editor {
            case .image(let model):
                if case .failed(let error) = model.export { model.dismissExportError(); return .failed(error) }
                if model.export == nil && result == nil { return .cancelled }
            case .video(let model):
                if case .failed(let error) = model.export { model.dismissExportError(); return .failed(error) }
                if model.export == nil && result == nil { return .cancelled }
            default: break
            }
            try? await Task.sleep(nanoseconds: 100_000_000)
        }
        return .cancelled
    }

    private enum OpenState { case loading, failed(EditorError), ready }

    private static func openState(_ editor: RouterModel.Editor) -> OpenState {
        switch editor {
        case .image(let model):
            switch model.phase {
            case .failed(let error): .failed(error)
            case .ready: .ready
            default: .loading
            }
        case .video(let model):
            switch model.phase {
            case .failed(let error): .failed(error)
            case .ready: .ready
            default: .loading
            }
        default: .ready
        }
    }

    func cancelSave() {
        guard case .running = save else { return }
        save = .cancelling
        switch current?.editor {
        case .image(let model): model.cancelExport()
        case .video(let model): model.cancelExport()
        default: break
        }
        for item in items where item.outputs == nil {
            switch item.editor {
            case .image(let model): model.cancelExport()
            case .video(let model): model.cancelExport()
            default: break
            }
        }
    }

    func dismissSaveError() { save = nil }

    // MARK: - 닫기

    func requestClose() {
        if case .running = save { cancelSave(); return }
        if items.contains(where: { isDirty($0) || $0.outputs != nil }) { showDiscardDialog = true } else { finish(.cancelled) }
    }

    func discardAndClose() {
        showDiscardDialog = false
        finish(.cancelled)
    }

    func finish(_ result: FrameKitResult) {
        guard !delivered else { return }
        delivered = true
        saveTask?.cancel()
        // 결과로 넘기지 않는 저장 파일은 지운다(호스트가 닫은 경우 포함).
        if case .success = result {} else { items.flatMap { $0.outputs ?? [] }.forEach { FrameKitOutputs.delete($0.url) } }
        for item in items {
            switch item.editor {
            case .image(let model): model.dismissedWithoutResult()
            case .video(let model): model.dismissedWithoutResult()
            default: break
            }
        }
        onFinish(result)
    }
}

/// 혼합 편집 화면.
struct MixedEditorScreen: View {
    @Bindable var model: MixedEditorModel
    @Environment(\.editorTheme) private var theme
    @Environment(\.strings) private var strings

    var body: some View {
        VStack(spacing: 0) {
            if !model.toolActive { topBar }
            ZStack {
                ForEach(Array(model.items.enumerated()), id: \.element.id) { index, item in
                    // 고른 항목만 보이되 편집기 화면은 살려 둬 상태를 지킨다.
                    if index == model.selected {
                        editor(item).transition(.opacity)
                    }
                }
            }
            .environment(\.editorEmbedded, true)
            .frame(maxHeight: .infinity)
            if !model.toolActive { strip }
        }
        .background(theme.background.ignoresSafeArea())
        .overlay { saveOverlay }
        .alert(strings("discard_title"), isPresented: $model.showDiscardDialog) {
            Button(strings("action_keep_editing"), role: .cancel) {}
            Button(strings("action_discard"), role: .destructive) { model.discardAndClose() }
        } message: { Text(strings("discard_message")) }
        .alert(strings("export_failed_title"), isPresented: Binding(get: { saveError != nil }, set: { if !$0 { model.dismissSaveError() } })) {
            if saveError?.recoverable == true { Button(strings("action_retry")) { model.dismissSaveError(); model.saveAll() } }
            Button(strings("action_keep_editing"), role: .cancel) { model.dismissSaveError() }
        } message: { Text(saveError.map { strings(errorKey($0.code)) } ?? "") }
    }

    private var saveError: EditorError? { if case .failed(let error) = model.save { error } else { nil } }

    @ViewBuilder
    private func editor(_ item: MixedEditorModel.Item) -> some View {
        switch item.editor {
        case .image(let editor): EditorRoot(model: editor)
        case .video(let editor): VideoEditorRoot(model: editor)
        case .motion(let editor): MotionEditorRoot(model: editor)
        case .audio(let editor): AudioEditorRoot(model: editor)
        case .mixed: EmptyView()
        }
    }

    private var topBar: some View {
        HStack {
            Button { model.requestClose() } label: {
                Image(systemName: "xmark").font(.system(size: 17, weight: .semibold)).foregroundStyle(theme.foreground)
                    .frame(width: 42, height: 42).editorGlass(Circle(), theme: theme)
                    .frame(minWidth: 48, minHeight: 48).contentShape(Rectangle())
            }
            .buttonStyle(PressFadeStyle())
            .accessibilityLabel(strings("action_close"))
            Spacer()
            Text("\(model.selected + 1) / \(model.items.count)").scaledFont(15, weight: .semibold, monospacedDigit: true)
                .foregroundStyle(theme.foregroundMuted)
            Spacer()
            Button { model.saveAll() } label: {
                Text(strings("action_save")).scaledFont(16, weight: .semibold).foregroundStyle(theme.onAccent)
                    .padding(.horizontal, 18).frame(height: 42)
                    .editorGlass(Capsule(), theme: theme, tint: theme.accent)
                    .frame(minHeight: 48).contentShape(Rectangle())
            }
            .buttonStyle(PressFadeStyle())
            .disabled(model.save != nil)
        }
        .padding(.horizontal, 12)
        .frame(height: 60)
    }

    private var strip: some View {
        ScrollViewReader { proxy in
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(Array(model.items.enumerated()), id: \.element.id) { index, item in
                        tile(item, index: index).id(index)
                    }
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 8)
            }
            .onChange(of: model.selected) { _, new in proxy.scrollTo(new, anchor: .center) }
        }
        .background(theme.surface)
    }

    private func tile(_ item: MixedEditorModel.Item, index: Int) -> some View {
        let selected = index == model.selected
        return Button { model.select(index) } label: {
            ZStack(alignment: .bottomLeading) {
                Group {
                    if let image = model.thumbnails[item.id] { Image(decorative: image, scale: 1).resizable().scaledToFill() } else { theme.raised }
                }
                .frame(width: 60, height: 60)
                .clipShape(RoundedRectangle(cornerRadius: theme.radius / 2))
                .overlay { RoundedRectangle(cornerRadius: theme.radius / 2).strokeBorder(theme.accent, lineWidth: selected ? 2.5 : 0) }
                Image(systemName: item.kind.mediaKind == .video ? "video.fill" : "photo.fill").font(.system(size: 10, weight: .bold))
                    .foregroundStyle(.white).padding(4).background(Color.black.opacity(0.55), in: Circle()).padding(4)
                if item.outputs != nil {
                    Image(systemName: "checkmark.circle.fill").font(.system(size: 16)).foregroundStyle(theme.accent)
                        .background(Circle().fill(.white)).frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topTrailing).padding(3)
                } else if model.isDirty(item) {
                    Circle().fill(theme.accent).frame(width: 8, height: 8)
                        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topTrailing).padding(5)
                }
            }
            .frame(width: 60, height: 60)
        }
        .buttonStyle(PressFadeStyle())
        .accessibilityLabel(strings(item.kind.mediaKind == .video ? "chooser_video" : "chooser_photo"))
        .accessibilityValue("\(index + 1) / \(model.items.count)")
        .accessibilityAddTraits(selected ? [.isSelected] : [])
    }

    @ViewBuilder
    private var saveOverlay: some View {
        if let save = model.save, saveError == nil {
            ZStack {
                theme.background.opacity(0.82).ignoresSafeArea().contentShape(Rectangle()).onTapGesture {}
                VStack(spacing: 16) {
                    ProgressRing(progress: nil)
                    if case .running(let index, let total) = save {
                        Text(strings("export_exporting")).scaledFont(15, weight: .medium).foregroundStyle(theme.foreground)
                        Text("\(index) / \(total)").scaledFont(13, monospacedDigit: true).foregroundStyle(theme.foregroundMuted)
                        EditorButton(title: strings("action_cancel"), kind: .tonal) { model.cancelSave() }
                    } else {
                        Text(strings("export_cancelling")).scaledFont(15, weight: .medium).foregroundStyle(theme.foreground)
                    }
                }
                .padding(24).frame(minWidth: 240, maxWidth: 320)
                .background(theme.surface, in: RoundedRectangle(cornerRadius: theme.radius))
            }
        }
    }
}
