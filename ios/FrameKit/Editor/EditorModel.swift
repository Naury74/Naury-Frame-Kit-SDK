import CoreGraphics
import Foundation
import Observation
import UniformTypeIdentifiers
internal import FrameKitCore

/// 자르기 비율 선택.
enum CropAspect: Hashable {
    case free
    case original
    case fixed(Int, Int)

    static let presets: [CropAspect] = [.free, .original, .fixed(1, 1), .fixed(4, 3), .fixed(3, 4), .fixed(16, 9), .fixed(9, 16), .fixed(3, 2), .fixed(2, 3)]

    var kotlin: CropAspectRatio {
        switch self {
        case .free: CropAspectRatioFree.shared
        case .original: CropAspectRatioOriginal.shared
        case .fixed(let w, let h): CropAspectRatioFixed(width: Int32(w), height: Int32(h))
        }
    }
}

/// 저장 진행 상태.
enum ExportUI: Equatable {
    case running(ExportStage?)
    case cancelling
    case failed(EditorError)
}

/// 편집기 상태와 동작. 화면은 이 값만 그린다.
@MainActor
@Observable
final class EditorModel {
    enum Phase: Equatable {
        case awaitingSource
        case loading
        case failed(EditorError)
        case ready
    }

    let request: ImageEditorRequest
    let tools: [ImageTool]
    private let onFinish: (FrameKitResult) -> Void
    private var delivered = false

    private(set) var phase: Phase
    private(set) var history = EditHistory(PhotoEdit())
    /// 적용 전 도구 편집이나 끌기 중인 값. 있으면 화면은 이것을 그린다.
    private(set) var working: PhotoEdit?
    private(set) var activeTool: ImageTool?
    private var toolEntry: PhotoEdit?
    private(set) var cropAspect: CropAspect = .free
    var adjustKind: AdjustKind = .brightness
    private(set) var chromeHidden = false
    var showingOriginal = false
    private(set) var export: ExportUI?
    var showDiscardDialog = false
    var showDraftDialog = false
    private(set) var notice: String?
    private(set) var draggingCrop = false

    /// 미리 보기 결과(현재 편집, 출력 화면 크기).
    private(set) var rendered: CGImage?
    /// 자르기·회전 도구에서 보이는 자르기 전 이미지.
    private(set) var boundsImage: CGImage?
    private(set) var filterThumbnails: [String: CGImage] = [:]

    private(set) var source: SourceImage?
    private(set) var preview: UprightImage?
    private var viewport = CGSize(width: 1000, height: 1000)
    private var renderTask: Task<Void, Never>?
    private var renderGeneration = 0
    private var exportTask: Task<Void, Never>?
    private var cropDragStart: (rect: NRect, handle: CropHandle)?
    private var straightenStart: GeometryState?
    private var noticeTask: Task<Void, Never>?

    init(request: ImageEditorRequest, onFinish: @escaping (FrameKitResult) -> Void) {
        self.request = request
        self.onFinish = onFinish
        var seen = Set<ImageTool>()
        tools = request.config.enabledTools.filter { seen.insert($0).inserted }
        if case .pick = request.input { phase = .awaitingSource } else { phase = .loading }
    }

    // MARK: - 표시 값

    var displayed: PhotoEdit { working ?? history.current }
    var isDirty: Bool { history.isDirty || (working.map { !$0.sameContent(as: history.current) } ?? false) }
    var canUndo: Bool { request.config.allowUndo && history.canUndo && export == nil && working == nil }
    var canRedo: Bool { request.config.allowRedo && history.canRedo && export == nil && working == nil }
    var uprightSize: PixelSizeValue { source?.uprightSize ?? PixelSizeValue(width: 1, height: 1) }
    var toolHasChanges: Bool {
        guard let tool = activeTool else { return false }
        if tool.isDraft { return working.map { !$0.sameContent(as: history.current) } ?? false }
        return toolEntry.map { !$0.sameContent(as: displayed) } ?? false
    }

    // MARK: - 원본 열기

    func start() {
        OutputStore.removeStalePartials()
        let issues = request.validate()
        guard issues.isEmpty else {
            frameKitLog.error("invalid configuration: \(issues.map(\.description).joined(separator: ", "), privacy: .public)")
            finish(.failure(EditorError(code: .invalidConfiguration)))
            return
        }
        switch request.input {
        case .file(let url): load { try Self.copyToTemporary(url) }
        case .data(let data): load { try Self.writeTemporary(data) }
        case .pick: phase = .awaitingSource
        }
    }

    /// 사진 선택기 결과. `nil`이면 닫은 것이다.
    func picked(_ provider: NSItemProvider?) {
        guard let provider else { finish(.cancelled); return }
        phase = .loading
        let type = provider.registeredTypeIdentifiers.compactMap(UTType.init).first { t in
            SourceImage.supportedTypes.contains { t.conforms(to: $0) }
        } ?? .image
        provider.loadFileRepresentation(forTypeIdentifier: type.identifier) { url, error in
            // 이 콜백이 끝나면 url은 지워지므로 그 전에 복사한다.
            let copied = Result<URL, EditorError> { () throws(EditorError) -> URL in
                guard let url else { throw .logged(.sourceUnavailable, "picker: \(String(describing: error))") }
                return try Self.copyToTemporary(url)
            }
            Task { @MainActor in self.load { try copied.get() } }
        }
    }

    private func load(_ prepare: @escaping @Sendable () throws -> URL) {
        phase = .loading
        Task.detached(priority: .userInitiated) {
            do {
                let url = try prepare()
                let source = try SourceImage(url: url, removeOnClose: true)
                // 미리 보기는 긴 변 2048px로 디코딩한다.
                let preview = try source.decode(maxLongEdge: 2048)
                await MainActor.run { self.opened(source, preview) }
            } catch let error as EditorError {
                await MainActor.run { self.phase = .failed(error) }
            } catch {
                await MainActor.run { self.phase = .failed(.logged(.unknown, "load: \(error)")) }
            }
        }
    }

    private func opened(_ source: SourceImage, _ preview: UprightImage) {
        self.source?.close()
        self.source = source
        self.preview = preview
        history = EditHistory(PhotoEdit())
        working = nil
        activeTool = nil
        phase = .ready
        requestRender()
    }

    nonisolated private static func copyToTemporary(_ url: URL) throws(EditorError) -> URL {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        let target = try OutputStore.temporaryDirectory.appendingPathComponent(UUID().uuidString)
            .appendingPathExtension(url.pathExtension.isEmpty ? "img" : url.pathExtension)
        do {
            try FileManager.default.copyItem(at: url, to: target)
            return target
        } catch let error as CocoaError where error.code == .fileReadNoPermission {
            throw .logged(.permissionDenied, "copy: \(error)")
        } catch let error as CocoaError where error.code == .fileReadNoSuchFile {
            throw .logged(.sourceUnavailable, "copy: \(error)")
        } catch let error as CocoaError where error.code == .fileWriteOutOfSpace {
            throw .logged(.insufficientStorage, "copy: \(error)")
        } catch {
            throw .logged(.sourceUnavailable, "copy: \(error)")
        }
    }

    nonisolated private static func writeTemporary(_ data: Data) throws(EditorError) -> URL {
        let target = try OutputStore.temporaryDirectory.appendingPathComponent(UUID().uuidString).appendingPathExtension("img")
        do {
            try data.write(to: target)
            return target
        } catch {
            throw .logged(.outputWriteFailed, "write data: \(error)")
        }
    }

    // MARK: - 미리 보기

    /// 사진 영역 크기(px)가 바뀌면 부른다.
    func setViewport(_ size: CGSize) {
        guard size.width > 1, size.height > 1, size != viewport else { return }
        viewport = size
        requestRender()
    }

    private var showsBounds: Bool { activeTool == .crop || activeTool == .rotate }

    func requestRender() {
        guard let preview, let source else { return }
        renderGeneration += 1
        let generation = renderGeneration
        let edit = displayed
        let bounds = showsBounds
        let upright = source.uprightSize
        // GPU가 없으면 CPU로 계산하므로 미리 보기를 작게 만든다.
        let limit = ColorPipeline.shared == nil ? 0.5 : 1.0
        let maxW = max(1, Int(viewport.width * limit))
        let maxH = max(1, Int(viewport.height * limit))
        renderTask?.cancel()
        renderTask = Task.detached(priority: .userInitiated) {
            let image: CGImage?
            do {
                let math = GeometryMath(source: upright, geometry: edit.geometry)
                let crop = bounds ? NRect.full : edit.geometry.crop
                let output = math.outputSize(crop: crop, maxPixels: Int(Int32.max), maxWidth: maxW, maxHeight: maxH)
                image = try PhotoRenderer.render(image: preview, uprightSize: upright, edit: edit, crop: crop, output: output,
                                                 background: nil, includeCanvasEffects: !bounds)
            } catch {
                image = nil
            }
            guard !Task.isCancelled else { return }
            await MainActor.run {
                guard generation == self.renderGeneration, let image else { return }
                if bounds { self.boundsImage = image } else { self.rendered = image }
            }
        }
    }

    /// 필터 미리 보기 작은 그림을 만든다.
    func makeFilterThumbnails() {
        guard let preview, let source, filterThumbnails.isEmpty else { return }
        let base = history.current
        let upright = source.uprightSize
        Task.detached(priority: .utility) {
            var result: [String: CGImage] = [:]
            var thumbEdit = base
            thumbEdit.adjustments = [:]
            thumbEdit.filterId = "original"
            thumbEdit.filterIntensity = 0
            let math = GeometryMath(source: upright, geometry: base.geometry)
            let output = math.outputSize(crop: base.geometry.crop, maxPixels: 160 * 160, maxWidth: 200, maxHeight: 200)
            guard let small = try? PhotoRenderer.render(image: preview, uprightSize: upright, edit: thumbEdit, crop: base.geometry.crop,
                                                        output: output, background: nil, includeCanvasEffects: true) else { return }
            let smallImage = UprightImage(cgImage: small)
            let size = PixelSizeValue(width: small.width, height: small.height)
            for id in FilterCatalog.shared.all.map(\.id) {
                var edit = PhotoEdit(grainSeed: base.grainSeed)
                edit.filterId = id
                edit.filterIntensity = id == "original" ? 0 : 1
                if let image = try? PhotoRenderer.render(image: smallImage, uprightSize: size, edit: edit, crop: .full, output: size,
                                                         background: nil, includeCanvasEffects: true) {
                    result[id] = image
                }
            }
            let thumbnails = result
            await MainActor.run { self.filterThumbnails = thumbnails }
        }
    }

    // MARK: - 도구

    func selectTool(_ tool: ImageTool) {
        guard phase == .ready, export == nil else { return }
        showChrome()
        activeTool = tool
        if tool.isDraft {
            working = history.current
        } else {
            toolEntry = history.current
        }
        if tool == .crop { cropAspect = .free }
        if tool == .filter { makeFilterThumbnails() }
        requestRender()
    }

    /// 적용 단추.
    func applyTool() {
        guard let tool = activeTool else { return }
        if tool.isDraft, let working { history.commit(working) }
        closeTool()
    }

    private func closeTool() {
        working = nil
        activeTool = nil
        toolEntry = nil
        draggingCrop = false
        requestRender()
    }

    /// 적용하지 않고 닫는다. 바로 반영되는 도구는 연 뒤 바꾼 것을 되돌린다.
    func discardTool() {
        guard let tool = activeTool else { return }
        if !tool.isDraft, let entry = toolEntry {
            history.commit(entry)
        }
        closeTool()
    }

    func onBack() {
        guard activeTool != nil else { requestClose(); return }
        if toolHasChanges { showDraftDialog = true } else { closeTool() }
    }

    func requestClose() {
        if export != nil {
            cancelExport()
        } else if isDirty {
            showDiscardDialog = true
        } else {
            finish(.cancelled)
        }
    }

    func discardAndClose() {
        showDiscardDialog = false
        finish(.cancelled)
    }

    func undo() {
        guard canUndo else { return }
        history.undo()
        filterThumbnails = [:]
        requestRender()
    }

    func redo() {
        guard canRedo else { return }
        history.redo()
        filterThumbnails = [:]
        requestRender()
    }

    func toggleChrome() {
        guard activeTool == nil, export == nil else { return }
        chromeHidden.toggle()
    }

    func showChrome() { chromeHidden = false }

    // MARK: - 자르기·회전

    private func updateWorking(_ change: (inout PhotoEdit) -> Void) {
        var edit = working ?? history.current
        change(&edit)
        working = edit
        requestRender()
    }

    func setAspect(_ aspect: CropAspect) {
        cropAspect = aspect
        let size = uprightSize.kotlin
        updateWorking { edit in
            edit.geometry = GeometryState(GeometryOperations.shared.withAspect(sourceSize: size, geometry: edit.geometry.kotlin, aspect: aspect.kotlin))
        }
    }

    func rotate(clockwise: Bool) {
        let size = uprightSize.kotlin
        let aspect = cropAspect
        updateWorking { edit in
            let ops = GeometryOperations.shared
            var geometry = clockwise ? ops.rotateClockwise(geometry: edit.geometry.kotlin) : ops.rotateCounterClockwise(geometry: edit.geometry.kotlin)
            if case .fixed = aspect { geometry = ops.withAspect(sourceSize: size, geometry: geometry, aspect: aspect.kotlin) }
            edit.geometry = GeometryState(geometry)
        }
    }

    func flip(horizontal: Bool) {
        updateWorking { edit in
            let ops = GeometryOperations.shared
            edit.geometry = GeometryState(horizontal ? ops.flipHorizontal(geometry: edit.geometry.kotlin) : ops.flipVertical(geometry: edit.geometry.kotlin))
        }
    }

    func setStraighten(_ degrees: Double) {
        let start = straightenStart ?? displayed.geometry
        straightenStart = start
        let size = uprightSize.kotlin
        updateWorking { edit in
            edit.geometry = GeometryState(GeometryOperations.shared.withStraighten(sourceSize: size, start: start.kotlin, degrees: degrees))
        }
    }

    func finishStraighten() { straightenStart = nil }

    func beginCropDrag(_ handle: CropHandle) {
        cropDragStart = (displayed.geometry.crop, handle)
        draggingCrop = true
    }

    /// - Parameters: dx, dy: 끌기 시작점부터 이동한 거리(자르기 전 이미지 기준 0...1).
    func dragCrop(dx: Double, dy: Double) {
        guard let start = cropDragStart else { return }
        let math = GeometryMath(source: uprightSize, geometry: displayed.geometry)
        let pixelAspect: KotlinDouble? = cropAspect == .free ? nil
            : KotlinDouble(double: CropBoundsCalculator.shared.resolvePixelAspect(frame: math.frame, aspect: cropAspect.kotlin))
        let rect = CropHandleDrag.shared.drag(frame: math.frame, start: start.rect.kotlin, handle: start.handle, dx: dx, dy: dy, pixelAspect: pixelAspect)
        updateWorking { $0.geometry.crop = NRect(rect) }
    }

    func endCropDrag() {
        cropDragStart = nil
        draggingCrop = false
    }

    // MARK: - 보정·필터

    /// 끄는 동안 부른다. 끝나면 `commitGesture()`.
    func setAdjustment(_ kind: AdjustKind, display: Double) {
        let value = kind.fromDisplay(display)
        updateWorking { $0.adjustments[kind] = value == 0 ? nil : value }
    }

    func setFilterIntensity(display: Double) {
        updateWorking { $0.filterIntensity = min(1, max(0, display / 100)) }
    }

    func commitGesture() {
        guard let tool = activeTool, !tool.isDraft, let working else { return }
        history.commit(working)
        self.working = nil
        requestRender()
    }

    func selectFilter(_ id: String) {
        var edit = history.current
        if id == "original" {
            edit.filterId = id
            edit.filterIntensity = 0
        } else if edit.filterId != id {
            edit.filterId = id
            edit.filterIntensity = 1
        }
        history.commit(edit)
        requestRender()
    }

    /// 초기화 단추.
    func resetTool() {
        switch activeTool {
        case .crop:
            cropAspect = .free
            let size = uprightSize.kotlin
            updateWorking { edit in
                edit.geometry = GeometryState(GeometryOperations.shared.withAspect(sourceSize: size, geometry: edit.geometry.kotlin, aspect: CropAspect.free.kotlin))
            }
        case .rotate:
            updateWorking { $0.geometry = GeometryState() }
        case .adjust:
            var edit = history.current
            edit.adjustments = [:]
            history.commit(edit)
            requestRender()
        case .filter:
            var edit = history.current
            edit.filterId = "original"
            edit.filterIntensity = 0
            history.commit(edit)
            requestRender()
        case nil:
            break
        }
    }

    // MARK: - 저장

    func save() {
        guard phase == .ready, export == nil, let source else { return }
        if let tool = activeTool {
            if tool.isDraft { applyTool() } else if working != nil { showNotice("apply_first"); return } else { closeTool() }
        }
        let edit = history.current
        let config = request.export
        export = .running(nil)
        exportTask = Task.detached(priority: .userInitiated) {
            do {
                let media = try ImageExporter.export(source: source, edit: edit, config: config) { stage in
                    Task { @MainActor in if case .running = self.export { self.export = .running(stage) } }
                }
                await MainActor.run { self.finish(.success(output: media, outputs: [media])) }
            } catch is CancellationError {
                await MainActor.run { self.export = nil }
            } catch let error as EditorError {
                await MainActor.run { self.export = Task.isCancelled ? nil : .failed(error) }
            } catch {
                await MainActor.run { self.export = .failed(.logged(.unknown, "export: \(error)")) }
            }
        }
    }

    func cancelExport() {
        guard case .running = export else { return }
        export = .cancelling
        exportTask?.cancel()
    }

    func dismissExportError() { export = nil }

    func retryExport() {
        export = nil
        save()
    }

    // MARK: - 끝내기

    private func showNotice(_ key: String) {
        notice = key
        noticeTask?.cancel()
        noticeTask = Task { @MainActor in
            try? await Task.sleep(for: .seconds(3))
            if !Task.isCancelled { self.notice = nil }
        }
    }

    func finish(_ result: FrameKitResult) {
        guard !delivered else { return }
        delivered = true
        renderTask?.cancel()
        exportTask?.cancel()
        source?.close()
        onFinish(result)
    }

    /// 화면이 결과 없이 사라질 때(호스트가 직접 닫는 등) 부른다.
    func dismissedWithoutResult() {
        finish(.cancelled)
    }
}
