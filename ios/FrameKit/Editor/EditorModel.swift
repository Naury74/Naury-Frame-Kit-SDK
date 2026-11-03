import CoreGraphics
import Foundation
import Observation
import AVFoundation
import UIKit
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
    case running(ExportStage?, Double?)
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
    /// 편집 중인 사진들. 순서가 저장 순서다.
    var pages: [EditorPage] = []
    private(set) var pageIndex = 0
    /// 사진 추가 선택기를 띄운다.
    var showAddPicker = false

    /// 현재 장의 실행 취소 기록.
    var history: EditHistory {
        get { pages.indices.contains(pageIndex) ? pages[pageIndex].history : EditHistory(PhotoEdit()) }
        set { if pages.indices.contains(pageIndex) { pages[pageIndex].history = newValue } }
    }
    /// 적용 전 도구 편집이나 끌기 중인 값. 있으면 화면은 이것을 그린다.
    var working: PhotoEdit?
    private(set) var activeTool: ImageTool?
    /// 마지막으로 연 도구(도구 줄 위치 기억용).
    private(set) var lastTool: ImageTool?
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
    /// 문서 도구의 모서리(원본 업라이트 기준).
    var documentQuad: DocQuad?
    var scanStyle = ScanStyle.color
    var documentBusy = false
    var documentSuggestionDismissed = false
    /// 배경 제거 상태.
    var cutoutBusy = false
    /// 이 기기에서 배경 제거를 할 수 있으면 `true`. 확인이 끝나기 전에는 도구를 보이지 않는다.
    var cutoutAvailable = false
    var cutoutError: EditorError?
    /// 문서를 편 뒤 인식한 글자.
    var documentText: String?
    var showDocumentText = false

    /// 미리 보기 결과(현재 편집, 출력 화면 크기).
    var rendered: CGImage?
    /// 자르기·회전 도구에서 보이는 자르기 전 이미지.
    private(set) var boundsImage: CGImage?
    var filterThumbnails: [String: CGImage] = [:]

    /// 고른 요소(글자·스티커·도형).
    var selectedOverlayId: String?
    /// 그리는 중인 획(캔버스 기준 0...1). 손을 떼면 편집에 들어간다.
    var liveStroke: [NPoint]?
    var brush = BrushSettings()
    var privacy = PrivacySettings()
    var stickerCategory = 0
    /// 미리 보기 결과의 캔버스 크기(px). 요소 배치 계산에 쓴다.
    private(set) var renderedCanvas = CGSize(width: 1, height: 1)
    private let previewCache = PreviewCache()
    private var nextId = 0

    var source: SourceImage? { pages.indices.contains(pageIndex) ? pages[pageIndex].source : nil }
    var preview: UprightImage? { pages.indices.contains(pageIndex) ? pages[pageIndex].preview : nil }
    var pageLimit: Int { request.config.maxImageCount }
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
        let locked = request.config.watermark?.locked == true
        tools = request.config.enabledTools.filter { seen.insert($0).inserted && !($0 == .watermark && locked) }
        if case .pick = request.input { phase = .awaitingSource } else { phase = .loading }
    }

    // MARK: - 표시 값

    var displayed: PhotoEdit { working ?? history.current }
    var isDirty: Bool {
        pages.contains { $0.history.isDirty } || (working.map { !$0.sameContent(as: history.current) } ?? false)
    }
    var canUndo: Bool { request.config.allowUndo && history.canUndo && export == nil && working == nil }
    var canRedo: Bool { request.config.allowRedo && history.canRedo && export == nil && working == nil }
    var uprightSize: PixelSizeValue { source?.uprightSize ?? PixelSizeValue(width: 1, height: 1) }
    var toolHasChanges: Bool {
        guard let tool = activeTool else { return false }
        if tool.isDraft {
            guard var working else { return false }
            if let watermark = working.watermark, !watermark.isValid { working.watermark = history.current.watermark }
            working.overlays.removeAll { overlay in
                if case .text(let text, _) = overlay.content { return text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
                return false
            }
            return !working.sameContent(as: history.current)
        }
        return toolEntry.map { !$0.sameContent(as: displayed) } ?? false
    }

    // MARK: - 원본 열기

    func start() {
        OutputStore.removeStalePartials()
        if tools.contains(.cutout) {
            Task.detached(priority: .utility) {
                let supported = SubjectCutout.isSupported
                await MainActor.run { self.cutoutAvailable = supported }
            }
        }
        let issues = request.validate()
        guard issues.isEmpty else {
            frameKitLog.error("invalid configuration: \(issues.map(\.description).joined(separator: ", "), privacy: .public)")
            finish(.failure(EditorError(code: .invalidConfiguration)))
            return
        }
        do {
            try CatalogInstaller.install(request.catalog)
        } catch {
            finish(.failure(error))
            return
        }
        switch request.input {
        case .file(let url): load([{ try Self.copyToTemporary(url) }], append: false)
        case .files(let urls): load(urls.map { url in { try Self.copyToTemporary(url) } }, append: false)
        case .data(let data): load([{ try Self.writeTemporary(data) }], append: false)
        case .pick, .pickMultiple: phase = .awaitingSource
        case .capture: startCapture()
        }
    }

    /// 카메라를 쓸 수 있는지 확인하고 촬영 화면으로 간다.
    private func startCapture() {
        guard Bundle.main.object(forInfoDictionaryKey: "NSCameraUsageDescription") != nil else {
            // 설명이 없으면 iOS가 앱을 종료시키므로 카메라를 열지 않는다.
            frameKitLog.error("NSCameraUsageDescription is missing in the host Info.plist")
            finish(.failure(EditorError(code: .invalidConfiguration)))
            return
        }
        guard UIImagePickerController.isSourceTypeAvailable(.camera) else {
            finish(.failure(EditorError(code: .cameraUnavailable)))
            return
        }
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .denied, .restricted:
            finish(.failure(EditorError(code: .permissionDenied)))
        default:
            phase = .awaitingSource
        }
    }

    /// 카메라 결과. `nil`이면 닫은 것이다.
    func captured(_ image: UIImage?) {
        guard let image else { finish(.cancelled); return }
        guard let data = image.jpegData(compressionQuality: 0.95) else {
            phase = .failed(.logged(.encodeFailed, "capture jpeg"))
            return
        }
        load([{ try Self.writeTemporary(data) }], append: false)
    }

    /// 처음 선택기에서 고를 수 있는 장 수.
    var initialPickLimit: Int {
        if case .pickMultiple(let max) = request.input { return min(max, pageLimit) }
        return 1
    }

    /// 사진 선택기 결과. 비어 있으면 닫은 것이다.
    func picked(_ providers: [NSItemProvider], append: Bool = false) {
        showAddPicker = false
        guard !providers.isEmpty else {
            if !append { finish(.cancelled) }
            return
        }
        let room = append ? max(0, pageLimit - pages.count) : pageLimit
        let chosen = Array(providers.prefix(room))
        if chosen.count < providers.count { showNotice("page_limit") }
        guard !chosen.isEmpty else { return }
        if !append { phase = .loading }
        Task { @MainActor in
            var prepares: [@Sendable () throws -> URL] = []
            for provider in chosen {
                let copied = await Self.copy(from: ItemProviderBox(provider: provider))
                prepares.append { try copied.get() }
            }
            self.load(prepares, append: append)
        }
    }

    /// 선택기가 넘긴 항목. 한 번만 읽고 버린다.
    struct ItemProviderBox: @unchecked Sendable { let provider: NSItemProvider }

    nonisolated private static func copy(from box: ItemProviderBox) async -> Result<URL, EditorError> {
        let provider = box.provider
        let type = provider.registeredTypeIdentifiers.compactMap(UTType.init).first { t in
            SourceImage.supportedTypes.contains { t.conforms(to: $0) }
        } ?? .image
        return await withCheckedContinuation { continuation in
            provider.loadFileRepresentation(forTypeIdentifier: type.identifier) { url, error in
                // 이 콜백이 끝나면 url은 지워지므로 그 전에 복사한다.
                continuation.resume(returning: Result { () throws(EditorError) -> URL in
                    guard let url else { throw .logged(.sourceUnavailable, "picker: \(String(describing: error))") }
                    return try copyToTemporary(url)
                })
            }
        }
    }

    private func load(_ prepares: [@Sendable () throws -> URL], append: Bool) {
        if !append { phase = .loading }
        Task.detached(priority: .userInitiated) {
            var opened: [(SourceImage, UprightImage)] = []
            do {
                for prepare in prepares {
                    let source = try SourceImage(url: try prepare(), removeOnClose: true)
                    // 미리 보기는 긴 변 2048px로 디코딩한다.
                    opened.append((source, try source.decode(maxLongEdge: 2048)))
                }
                let result = opened
                await MainActor.run { self.opened(result, append: append) }
            } catch {
                opened.forEach { $0.0.close() }
                let editorError = (error as? EditorError) ?? .logged(.unknown, "load: \(error)")
                await MainActor.run {
                    if append { self.showNotice(errorKey(editorError.code)) } else { self.phase = .failed(editorError) }
                }
            }
        }
    }

    private func opened(_ items: [(SourceImage, UprightImage)], append: Bool) {
        if !append {
            pages.forEach { page in
            page.source.close()
            page.original?.source.close()
        }
            pages = []
        }
        var initial = PhotoEdit()
        if let config = request.config.watermark, config.applyOnOpen, config.spec.isValid { initial.watermark = config.spec }
        // 이미 있는 장에 워터마크가 있으면 새 장에도 같게 넣는다.
        if append, let shared = pages.first?.history.current.watermark { initial.watermark = shared }
        for (source, preview) in items {
            var edit = initial
            edit.grainSeed = Int64.random(in: Int64.min...Int64.max)
            pages.append(EditorPage(source: source, preview: preview, history: EditHistory(edit), thumbnail: preview.thumbnail(maxSide: 160)))
        }
        if !append {
            pageIndex = 0
            working = nil
            activeTool = nil
            phase = .ready
        } else {
            selectPage(pages.count - 1)
        }
        requestRender()
        detectDocument()
    }

    // MARK: - 여러 장

    func selectPage(_ index: Int) {
        guard pages.indices.contains(index), index != pageIndex || working == nil, activeTool == nil, export == nil else { return }
        pageIndex = index
        selectedOverlayId = nil
        filterThumbnails = [:]
        rendered = nil
        requestRender()
        detectDocument()
    }

    func removePage(_ index: Int) {
        guard pages.count > 1, pages.indices.contains(index), activeTool == nil, export == nil else { return }
        pages[index].source.close()
        pages.remove(at: index)
        pageIndex = min(pageIndex, pages.count - 1)
        rendered = nil
        requestRender()
    }

    func movePage(_ index: Int, by offset: Int) {
        let target = index + offset
        guard pages.indices.contains(index), pages.indices.contains(target), activeTool == nil else { return }
        pages.swapAt(index, target)
        if pageIndex == index { pageIndex = target } else if pageIndex == target { pageIndex = index }
    }

    func requestAddPages() {
        guard pages.count < pageLimit else { showNotice("page_limit"); return }
        showAddPicker = true
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
        let cache = previewCache
        renderTask = Task.detached(priority: .userInitiated) {
            let image: CGImage?
            do {
                let math = GeometryMath(source: upright, geometry: edit.geometry)
                let crop = bounds ? NRect.full : edit.geometry.crop
                let output = try math.outputSize(crop: crop, maxPixels: Int(Int32.max), maxWidth: maxW, maxHeight: maxH)
                if bounds {
                    image = try PhotoRenderer.render(image: preview, uprightSize: upright, edit: edit, crop: crop, output: output,
                                                     background: nil, includeCanvasEffects: false)
                } else {
                    // 기하·색이 같으면 앞서 만든 바탕을 다시 쓰고 겹쳐 그리기만 새로 한다.
                    let key = PreviewCache.Key(edit: edit, output: output, source: ObjectIdentifier(preview.cgImage))
                    let base: CGImage
                    if let cached = cache.image(for: key) {
                        base = cached
                    } else {
                        var colorOnly = edit
                        colorOnly.overlays = []; colorOnly.drawing = []; colorOnly.privacyMasks = []; colorOnly.watermark = nil
                        base = try PhotoRenderer.render(image: preview, uprightSize: upright, edit: colorOnly, crop: crop, output: output,
                                                        background: nil, includeCanvasEffects: true)
                        cache.store(base, for: key)
                    }
                    image = try CanvasLayersRenderer.apply(base, edit: edit)
                }
            } catch {
                image = nil
            }
            guard !Task.isCancelled else { return }
            await MainActor.run {
                guard generation == self.renderGeneration, let image else { return }
                if bounds {
                    self.boundsImage = image
                } else {
                    self.rendered = image
                    self.renderedCanvas = CGSize(width: image.width, height: image.height)
                    if self.pages.indices.contains(self.pageIndex) {
                        self.pages[self.pageIndex].thumbnail = UprightImage(cgImage: image).thumbnail(maxSide: 160)
                    }
                }
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
            guard let output = try? math.outputSize(crop: base.geometry.crop, maxPixels: 160 * 160, maxWidth: 200, maxHeight: 200),
                  let small = try? PhotoRenderer.render(image: preview, uprightSize: upright, edit: thumbEdit, crop: base.geometry.crop,
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
        lastTool = tool
        if tool.isDraft {
            working = history.current
        } else {
            toolEntry = history.current
        }
        if tool == .crop { cropAspect = .free }
        if tool == .filter { makeFilterThumbnails() }
        if tool == .text { beginText() }
        if tool == .watermark, working?.watermark == nil {
            working?.watermark = request.config.watermark.map { config in
                var spec = config.spec
                spec.text = ""
                return spec
            } ?? WatermarkSpec(text: "")
        }
        if tool == .draw || tool == .privacy { selectedOverlayId = nil }
        if tool == .document {
            let page = pages[pageIndex]
            documentQuad = page.appliedQuad ?? page.detection?.quad ?? .inset(0.08)
        }
        requestRender()
    }

    /// 적용 단추.
    func applyTool() {
        guard let tool = activeTool else { return }
        if tool == .document { applyDocument(); return }
        if tool.isDraft, var working {
            // 빈 글자와 빈 워터마크는 넣지 않는다.
            working.overlays.removeAll { overlay in
                if case .text(let text, _) = overlay.content { return text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
                return false
            }
            if let watermark = working.watermark, !watermark.isValid { working.watermark = nil }
            history.commit(working)
            if tool == .watermark {
                // 워터마크는 모든 장에 같게 넣는다.
                for index in pages.indices where index != pageIndex {
                    var edit = pages[index].history.current
                    edit.watermark = working.watermark
                    pages[index].history.commit(edit)
                }
            }
        }
        closeTool()
    }

    func closeToolAfterDocument() { closeTool() }

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
        selectedOverlayId = nil
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

    func updateWorking(_ change: (inout PhotoEdit) -> Void) {
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
        guard activeTool?.isDraft != true, let working else { return }
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
        default:
            break
        }
    }

    // MARK: - 저장

    func save() {
        guard phase == .ready, export == nil, !pages.isEmpty else { return }
        if let tool = activeTool {
            if tool.isDraft { applyTool() } else if working != nil { showNotice("apply_first"); return } else { closeTool() }
        }
        let items = pages.map { ($0.source, $0.history.current) }
        let config = request.export
        export = .running(nil, nil)
        exportTask = Task.detached(priority: .userInitiated) {
            let report: @Sendable (ExportStage, Double?) -> Void = { stage, fraction in
                Task { @MainActor in if case .running = self.export { self.export = .running(stage, fraction) } }
            }
            do {
                let outputs: [EditedMedia]
                if config.format == .pdf {
                    outputs = try PdfExporter.export(pages: items, config: config) { report($0, $1) }
                } else if items.count > 1 {
                    outputs = try ImageExporter.exportAll(pages: items, config: config) { report($0, $1) }
                } else {
                    outputs = [try ImageExporter.export(source: items[0].0, edit: items[0].1, config: config) { report($0, nil) }]
                }
                await MainActor.run { self.finish(.success(output: outputs[0], outputs: outputs)) }
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

    func showNotice(_ key: String) {
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
        pages.forEach { $0.source.close() }
        onFinish(result)
    }

    /// 화면이 결과 없이 사라질 때(호스트가 직접 닫는 등) 부른다.
    func dismissedWithoutResult() {
        finish(.cancelled)
    }
}
