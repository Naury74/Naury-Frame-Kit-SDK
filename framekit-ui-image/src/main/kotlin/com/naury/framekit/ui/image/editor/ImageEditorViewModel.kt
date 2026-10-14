package com.naury.framekit.ui.image.editor

import com.naury.framekit.core.overlay.Watermark
import android.graphics.Canvas
import com.naury.framekit.core.model.PixelSize
import com.naury.framekit.core.model.SourceMetadata
import com.naury.framekit.image.render.ImagePreviewRenderer
import com.naury.framekit.image.overlay.OverlayRenderer
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.delay
import com.naury.framekit.image.ocr.TextRecognizer
import com.naury.framekit.image.ml.OnDeviceModelState
import com.naury.framekit.core.document.ScanMode
import com.naury.framekit.image.document.DocumentRectifier
import com.naury.framekit.core.document.DocumentQuad
import com.naury.framekit.core.document.DocumentOutline
import com.naury.framekit.core.document.DocumentDetector
import java.io.File
import com.naury.framekit.ui.image.editor.ImageSessionRecorder.Companion.toInput
import com.naury.framekit.image.export.PdfPage
import com.naury.framekit.image.export.ImageFormat
import com.naury.framekit.android.result.EditedMedia
import com.naury.framekit.android.capture.CaptureFiles
import com.naury.framekit.ui.tool.PrivacyShape
import com.naury.framekit.ui.tool.PrivacySettings
import android.content.ContentResolver
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.naury.framekit.android.input.EditorInput
import com.naury.framekit.android.result.EditorError
import com.naury.framekit.android.result.EditorErrorCode
import com.naury.framekit.android.result.FrameKitException
import com.naury.framekit.android.result.FrameKitResult
import com.naury.framekit.android.source.SessionSourceRegistry
import com.naury.framekit.android.session.EditorSessionStore
import com.naury.framekit.android.session.ProjectAssetStore
import com.naury.framekit.android.session.SessionRecord
import com.naury.framekit.core.history.EditHistory
import com.naury.framekit.core.model.SourceId
import com.naury.framekit.core.validation.ImageProjectValidator
import com.naury.framekit.image.decode.DecodedImage
import com.naury.framekit.image.decode.ImageSourceInfo
import kotlinx.coroutines.NonCancellable
import com.naury.framekit.core.effect.AdjustmentKind
import com.naury.framekit.core.effect.Adjustments
import com.naury.framekit.core.effect.FilterCatalog
import com.naury.framekit.core.effect.FilterSelection
import com.naury.framekit.core.geometry.CropAspectRatio
import com.naury.framekit.core.geometry.PointN
import com.naury.framekit.core.geometry.RectN
import com.naury.framekit.core.overlay.DrawingStroke
import com.naury.framekit.core.overlay.MaskShape
import com.naury.framekit.core.overlay.PrivacyMask
import com.naury.framekit.core.overlay.EmojiCatalog
import com.naury.framekit.core.overlay.ImageOverlay
import com.naury.framekit.core.overlay.OverlayTransform
import com.naury.framekit.core.overlay.StrokePoint
import com.naury.framekit.core.overlay.SubjectCutout
import com.naury.framekit.core.overlay.TextStyleSpec
import com.naury.framekit.core.geometry.CropBoundsCalculator
import com.naury.framekit.core.geometry.CropHandle
import com.naury.framekit.core.geometry.CropHandleDrag
import com.naury.framekit.core.geometry.GeometryEdit
import com.naury.framekit.core.geometry.GeometryFrame
import com.naury.framekit.core.geometry.GeometryOperations
import com.naury.framekit.core.history.HistoryTransaction
import com.naury.framekit.core.model.ImageProject
import com.naury.framekit.core.model.ProjectId
import com.naury.framekit.image.decode.BitmapDecoder
import com.naury.framekit.image.decode.ImageMetadataReader
import com.naury.framekit.image.decode.SampleSize
import com.naury.framekit.image.cutout.BackgroundRemover
import com.naury.framekit.image.cutout.CutoutMasks
import com.naury.framekit.image.effect.ColorEffectRenderer
import com.naury.framekit.image.effect.CpuColorEffectRenderer
import com.naury.framekit.image.export.ImageExportCoordinator
import com.naury.framekit.image.render.PreviewMode
import com.naury.framekit.image.export.ImageExportStage
import com.naury.framekit.ui.component.ExportStageUi
import com.naury.framekit.ui.image.contract.ImageEditorRequest
import com.naury.framekit.ui.image.contract.ImageTool
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.math.abs
import kotlin.random.Random

/**
 * 이미지 편집 세션 하나의 상태 홀더.
 *
 * 모든 편집은 [HistoryTransaction]을 거친다. 도구 세션은 `begin → update* → apply/cancel`이므로
 * 자르기나 회전 세션 하나가 정확히 실행 취소 한 단계가 된다. 내보내기는 초안이 아니라 항상
 * 커밋된 스냅샷을 사용한다.
 */
internal class ImageEditorViewModel(
    private val request: ImageEditorRequest,
    private val savedState: SavedStateHandle,
    private val registry: SessionSourceRegistry,
    private val exportCoordinator: ImageExportCoordinator,
    private val previewLongEdge: Int,
    private val ioDispatcher: CoroutineDispatcher,
    private val sessionStore: EditorSessionStore,
    private val snapshotDebounceMillis: Long = SNAPSHOT_DEBOUNCE_MILLIS,
    private val contentResolver: ContentResolver? = null,
    private val colorRenderer: ColorEffectRenderer = CpuColorEffectRenderer,
    renderDispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1),
    private val backgroundRemover: BackgroundRemover? = null,
    private val assetFallback: ProjectAssetStore? = null,
    private val captureFile: (() -> Pair<File, Uri>)? = null,
    private val documentDirectory: File? = null,
    private val memoryBudgetBytes: Long = DEFAULT_MEMORY_BUDGET,
    private val textRecognizer: TextRecognizer? = null,
) : ViewModel(), ImageCanvasActions {

    private val _state = MutableStateFlow<ImageEditorUiState>(ImageEditorUiState.Loading)
    val state: StateFlow<ImageEditorUiState> = _state.asStateFlow()

    private val _result = MutableStateFlow<FrameKitResult?>(null)

    /** 최종 결과. 이 값이 `null`이 아니게 되는 즉시 Activity가 종료된다. */
    val result: StateFlow<FrameKitResult?> = _result.asStateFlow()

    private var exportJob: Job? = null
    private var cropDragStart: Pair<CropHandle, RectN>? = null
    private var straightenStart: GeometryEdit? = null
    private var overlayGestureStart: ImageOverlay? = null
    private var maskAnchor: PointN? = null
    private var shapeAnchor: PointN? = null
    // 바로 기록되는 도구(보정·필터 등)를 열 때의 프로젝트. 뒤로 가기·취소 때 이 상태로 되돌린다.
    private var toolEntry: ImageProject? = null

    // 워터마크 도구를 다시 열 때 채울 마지막 값, 여러 장에서 모든 사진에 넣을 워터마크(사용자가 바꾼 적이 있을 때만).
    private var lastWatermark: Watermark? = null
    private var sharedWatermark: Shared? = null

    private data class Shared(val watermark: Watermark?)
    // 여러 장을 편집할 때 사진마다 따로 세션을 둔다. 첫 사진은 이전 버전과 같은 키를 쓴다.
    private val recorders = mutableMapOf<String, ImageSessionRecorder>()
    private var session: ImageSessionRecorder = recorderFor(MAIN_PAGE_ID)
    private val pages = mutableListOf<Page>()
    private var pageIndex = 0
    private var pagesChanged = false

    private val _pageThumbnails = MutableStateFlow<Map<String, Bitmap>>(emptyMap())

    /** 여러 장 편집에서 쪽 id별 작은 미리보기. 지금 쪽은 편집 내용(적용 전 초안 포함)을 반영해 다시 그린다. */
    val pageThumbnails: StateFlow<Map<String, Bitmap>> = _pageThumbnails.asStateFlow()

    // 지금 쪽 미리보기를 다시 그릴 요청. 최신 것만 그리고, 슬라이더를 끄는 동안에는 잠깐 기다렸다가 그린다.
    private val pageThumbnailRequests = MutableStateFlow<PageThumbnailRequest?>(null)
    private val pageThumbnailRenderer by lazy { ImagePreviewRenderer(colorRenderer) }
    private val pageOverlayRenderer by lazy { OverlayRenderer() }

    /** 화면 쪽 목록에 보일 순서대로의 쪽 id. */
    val pageIds: List<String> get() = pages.map { it.id }

    /** 이 요청으로 편집할 수 있는 최대 사진 수. 2 이상이면 쪽 목록과 사진 추가가 보인다. */
    val pageLimit: Int
        get() = when (val input = request.input) {
            is EditorInput.Pick -> minOf(input.maxItems, config.maxImageCount)
            is EditorInput.Multiple -> config.maxImageCount
            else -> 1
        }

    private val previewController = ImagePreviewController(viewModelScope, colorRenderer, renderDispatcher)

    /** 표시 중인 프로젝트의 색상 효과 미리보기. 캔버스가 직접 그리면 `null`. */
    val renderedPreview: StateFlow<RenderedPreview?> = previewController.rendered

    /** 프리셋 id별 필터 썸네일. */
    val filterThumbnails: StateFlow<Map<String, Bitmap>> = previewController.thumbnails

    private val _cutoutMasks = MutableStateFlow<Map<String, Bitmap>>(emptyMap())

    /** 에셋 id별로 로드된 배경 제거 마스크. */
    val cutoutMasks: StateFlow<Map<String, Bitmap>> = _cutoutMasks.asStateFlow()

    /**
     * 선택적 배경 제거기가 설치되어 있고 이 기기에서 쓸 수 있으며 도구가 활성화되어 있으면 `true`.
     * 배경 제거 중에 모델이 이 기기에서 동작하지 못한 것을 알게 되면 `false`가 되어 도구 목록에서 빠진다.
     */
    val cutoutAvailable: Boolean get() = backgroundRemover?.isAvailable == true && ImageTool.CUTOUT in config.enabledTools

    // 배경 제거 모델 확인·내려받기. 내려받기는 도구를 닫아도 계속한다.
    private var cutoutModelJob: Job? = null
    private var cutoutModelChecked = false
    private var removeAfterDownload = false

    // 권한을 잃은 원본을 다시 고를 때까지 이전 세션을 들고 있다가, 같은 이미지인지 확인한 뒤에만 복원한다.
    private var pendingRestore: SessionRecord? = null

    val config get() = request.config

    init {
        viewModelScope.launch(ioDispatcher) {
            exportCoordinator.deleteStalePartials()
            // 프로세스가 끝나 지우지 못한 오래된 문서 보정 파일을 정리한다.
            val now = System.currentTimeMillis()
            documentDirectory?.listFiles()?.forEach { if (now - it.lastModified() > DOCUMENT_STALE_MILLIS) it.delete() }
        }
        when {
            savedState.contains(KEY_PAGES) -> restorePages()
            savedState.contains(ImageSessionRecorder.KEY_SESSION_ID) -> restore()
            else -> start()
        }
        viewModelScope.launch {
            state.collect { current ->
                if (current is ImageEditorUiState.Ready) {
                    ensureCutoutMask(current.displayed.cutout?.maskAssetId)
                    requestPreview(current)
                    requestPageThumbnail(current)
                    ensureCutoutModelChecked(current)
                }
            }
        }
        viewModelScope.launch {
            pageThumbnailRequests.filterNotNull().distinctUntilChanged().collectLatest { request ->
                delay(PAGE_THUMBNAIL_DEBOUNCE_MILLIS)
                val thumbnail = withContext(renderDispatcher) { renderPageThumbnail(request) } ?: return@collectLatest
                if (pages.any { it.id == request.pageId }) _pageThumbnails.update { it + (request.pageId to thumbnail) }
            }
        }
        viewModelScope.launch { cutoutMasks.collect { (state.value as? ImageEditorUiState.Ready)?.let(::requestPreview) } }
    }

    private fun requestPreview(ready: ImageEditorUiState.Ready) {
        val project = if (ready.showingOriginal) {
            ready.displayed.copy(geometry = GeometryEdit(), cutout = null, adjustments = Adjustments(), filter = FilterSelection(), privacyMasks = emptyList())
        } else {
            ready.displayed
        }
        val mode = if (ready.activeTool?.isGeometry == true) PreviewMode.UNCROPPED else PreviewMode.RESULT
        val mask = project.cutout?.let { _cutoutMasks.value[it.maskAssetId] }
        previewController.request(ready.preview, project, ready.source.metadata, mode, mask)
        if (ready.activeTool == ImageTool.FILTER) previewController.ensureThumbnails(ready.preview)
    }

    private fun requestPageThumbnail(ready: ImageEditorUiState.Ready) {
        if (pageLimit <= 1) return
        val page = pages.getOrNull(pageIndex) ?: return
        // 쪽을 옮기는 사이에는 상태가 아직 이전 쪽일 수 있다. 원본이 같을 때만 지금 쪽 미리보기로 쓴다.
        if (page.sourceId != ready.displayed.source) return
        val project = ready.displayed
        pageThumbnailRequests.value = PageThumbnailRequest(page.id, ready.preview, project, ready.source.metadata, project.cutout?.let { _cutoutMasks.value[it.maskAssetId] })
    }

    // 미리보기와 같은 렌더러로 결과 화면을 작게 그리고, 텍스트·스티커·그리기를 그 위에 얹는다. 실패하면 이전 미리보기를 둔다.
    private fun renderPageThumbnail(request: PageThumbnailRequest): Bitmap? = try {
        pageThumbnailRenderer.render(request.source, request.project, request.metadata, PreviewMode.RESULT, PAGE_THUMBNAIL_PX, PAGE_THUMBNAIL_PX, request.cutoutMask).also { bitmap ->
            val project = request.project
            if (project.overlays.isNotEmpty() || project.drawing.isNotEmpty()) {
                pageOverlayRenderer.draw(Canvas(bitmap), PixelSize(bitmap.width, bitmap.height), project.overlays, project.drawing)
            }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    } catch (_: OutOfMemoryError) {
        null
    }

    /** 캔버스가 픽셀 단위 크기와 함께 호출한다. 미리보기는 이 크기로 렌더링된다. */
    override fun onViewportSize(width: Int, height: Int) = previewController.onViewport(width, height)

    private fun start() {
        when (val input = request.input) {
            is EditorInput.UriSource, is EditorInput.FileSource -> setPages(listOf(input))
            is EditorInput.Multiple -> setPages(input.items)
            is EditorInput.Pick -> {
                val picked = savedState.get<ArrayList<Uri>>(KEY_PICKED_URIS)
                if (picked.isNullOrEmpty()) {
                    _state.value = ImageEditorUiState.AwaitingSource(SourceMode.Pick(pageLimit))
                } else {
                    setPages(picked.map(EditorInput::UriSource))
                }
            }
            is EditorInput.Capture -> {
                val path = savedState.get<String>(KEY_CAPTURE_PATH)
                if (path != null && CaptureFiles.isFilled(File(path))) {
                    setPages(listOf(EditorInput.FileSource(path)))
                } else {
                    _state.value = ImageEditorUiState.AwaitingSource(SourceMode.Capture)
                }
            }
        }
    }

    // 이전 버전(쪽 목록 없음)에서 남은 세션을 복원한다.
    private fun restore() {
        viewModelScope.launch {
            val previous = withContext(ioDispatcher) { session.loadPrevious() }
            if (previous == null) {
                start()
            } else {
                pages.clear()
                pages += Page(MAIN_PAGE_ID, previous.source.toInput())
                persistPages()
                pendingRestore = previous
                load(pages.first())
            }
        }
    }

    private fun restorePages() {
        val inputs = savedState.get<ArrayList<EditorInput>>(KEY_PAGES).orEmpty()
        val ids = savedState.get<ArrayList<String>>(KEY_PAGE_IDS).orEmpty()
        if (inputs.isEmpty() || inputs.size != ids.size) return start()
        pages.clear()
        val originalIds = savedState.get<ArrayList<String>>(KEY_PAGE_ORIGINAL_IDS).orEmpty()
        val originals = savedState.get<ArrayList<EditorInput>>(KEY_PAGE_ORIGINALS).orEmpty()
        val originalById = if (originalIds.size == originals.size) originalIds.zip(originals).toMap() else emptyMap()
        inputs.forEachIndexed { index, input -> pages += Page(ids[index], input, originalById[ids[index]]) }
        pagesChanged = savedState.get<Boolean>(KEY_PAGES_CHANGED) == true
        loadPage(savedState.get<Int>(KEY_PAGE_INDEX)?.coerceIn(pages.indices) ?: 0, stash = false)
        loadPageThumbnails()
    }

    private fun setPages(inputs: List<EditorInput>) {
        pages.clear()
        inputs.forEachIndexed { index, input -> pages += Page(if (index == 0) MAIN_PAGE_ID else newId(), input) }
        persistPages()
        loadPage(0, stash = false)
        loadPageThumbnails()
    }

    private fun persistPages() {
        savedState[KEY_PAGES] = ArrayList(pages.map { it.input })
        savedState[KEY_PAGE_IDS] = ArrayList(pages.map { it.id })
        // 문서 보정한 쪽의 원래 원본. 프로세스가 끝나도 "원본으로"를 쓸 수 있게 둔다.
        savedState[KEY_PAGE_ORIGINAL_IDS] = ArrayList(pages.filter { it.original != null }.map { it.id })
        savedState[KEY_PAGE_ORIGINALS] = ArrayList(pages.mapNotNull { it.original })
        savedState[KEY_PAGE_INDEX] = pageIndex
        savedState[KEY_PAGES_CHANGED] = pagesChanged
    }

    /** 다른 쪽으로 옮기기 전에 지금 쪽의 편집을 메모리에 둔다. 디스크 세션에는 이미 기록돼 있다. */
    private fun stashCurrentPage() {
        val ready = _state.value as? ImageEditorUiState.Ready ?: return
        pages.getOrNull(pageIndex)?.let { page ->
            page.transaction = ready.transaction
            page.info = ready.source
        }
    }

    private fun loadPage(index: Int, stash: Boolean = true) {
        if (stash) stashCurrentPage()
        pageIndex = index
        savedState[KEY_PAGE_INDEX] = index
        val page = pages[index]
        session = recorderFor(page.id)
        if (page.transaction != null || session.isAttached) {
            load(page)
        } else {
            viewModelScope.launch {
                pendingRestore = withContext(ioDispatcher) { session.loadPrevious() }
                load(page)
            }
        }
    }

    private fun recorderFor(pageId: String): ImageSessionRecorder = recorders.getOrPut(pageId) {
        val key = if (pageId == MAIN_PAGE_ID) ImageSessionRecorder.KEY_SESSION_ID else "${ImageSessionRecorder.KEY_SESSION_ID}_$pageId"
        ImageSessionRecorder(sessionStore, savedState, viewModelScope, ioDispatcher, snapshotDebounceMillis, key)
    }

    /** picker 결과와 함께 호출된다. 빈 목록은 사용자가 picker를 닫았다는 뜻이다. */
    fun onPicked(uris: List<Uri>) {
        if (uris.isEmpty()) {
            if (_state.value is ImageEditorUiState.AwaitingSource) finish(FrameKitResult.Cancelled)
            return
        }
        val limited = uris.take(pageLimit)
        savedState[KEY_PICKED_URIS] = ArrayList(limited)
        setPages(limited.map(EditorInput::UriSource))
    }

    /** 한 장 picker 결과. `null`은 picker를 닫았다는 뜻이다. */
    fun onPicked(uri: Uri?) = onPicked(listOfNotNull(uri))

    /**
     * 촬영할 파일을 만들고 카메라 앱에 넘길 Uri를 돌려준다. 프로세스가 끝나도 이어 가도록 경로를 저장한다.
     *
     * @return 파일을 만들 수 없으면 `null`이고, 이때는 편집기를 실패로 닫는다.
     */
    fun prepareCapture(): Uri? {
        val factory = captureFile ?: return null.also { finish(FrameKitResult.Failure(EditorError(EditorErrorCode.CAMERA_UNAVAILABLE))) }
        return try {
            val (file, uri) = factory()
            savedState[KEY_CAPTURE_PATH] = file.absolutePath
            uri
        } catch (error: Exception) {
            logFailure("capture", FrameKitException(EditorErrorCode.OUTPUT_WRITE_FAILED, cause = error))
            finish(FrameKitResult.Failure(EditorError(EditorErrorCode.OUTPUT_WRITE_FAILED)))
            null
        }
    }

    /** 카메라 앱이 돌아왔다. 찍지 않고 돌아오면 취소로 닫는다. */
    fun onCaptured(success: Boolean) {
        val path = savedState.get<String>(KEY_CAPTURE_PATH)
        if (success && path != null && CaptureFiles.isFilled(File(path))) {
            setPages(listOf(EditorInput.FileSource(path)))
        } else {
            CaptureFiles.delete(path)
            savedState.remove<String>(KEY_CAPTURE_PATH)
            finish(FrameKitResult.Cancelled)
        }
    }

    /** 카메라를 쓸 수 없거나 권한이 거부됐다. */
    fun onCaptureFailed(code: EditorErrorCode) = finish(FrameKitResult.Failure(EditorError(code)))

    /** 원본을 열지 못한 뒤 picker로 돌아간다. */
    fun chooseAnother() {
        savedState.remove<ArrayList<Uri>>(KEY_PICKED_URIS)
        savedState.remove<ArrayList<EditorInput>>(KEY_PAGES)
        pages.clear()
        _state.value = ImageEditorUiState.AwaitingSource(SourceMode.Pick(pageLimit))
    }

    private fun load(page: Page) {
        _state.value = ImageEditorUiState.Loading
        viewModelScope.launch {
            try {
                val ready = withContext(ioDispatcher) {
                    val (sourceId, info) = openPage(page)
                    val sample = SampleSize.forMinimumLongEdge(info.encodedSize, previewLongEdge)
                    val preview = BitmapDecoder(registry, contentResolver).decode(info, sample)
                    val existing = page.transaction
                    if (existing != null) {
                        ImageEditorUiState.Ready(preview, info, existing)
                    } else {
                        val restored = session.attach(pendingRestore, page.input, info)
                        pendingRestore = null
                        val base = restoredState(restored, sourceId, info, preview)
                        val carry = page.carry
                        page.carry = null
                        if (carry == null) base else base.copy(transaction = HistoryTransaction.start(carry(base.transaction.history.current)))
                    }
                }
                _state.value = ready.copy(
                    pageIndex = pageIndex,
                    pageCount = pages.size,
                    documentRectified = page.original != null,
                    documentSuggestion = page.documentSuggestion,
                    documentSuggestionDismissed = page.documentSuggestionDismissed,
                    documentText = page.documentText,
                    notice = pendingNotice ?: ready.notice,
                )
                pendingNotice = null
                checkDocument(page, ready.preview.bitmap)
                if (page.recognizeAfterLoad) {
                    page.recognizeAfterLoad = false
                    recognizeDocument(page, ready.preview.bitmap)
                }
            } catch (error: FrameKitException) {
                logFailure("load", error)
                _state.value = ImageEditorUiState.LoadFailed(error.code, canChooseAnother = request.input is EditorInput.Pick)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                logFailure("load", FrameKitException(EditorErrorCode.UNKNOWN, cause = error))
                _state.value = ImageEditorUiState.LoadFailed(EditorErrorCode.UNKNOWN, canChooseAnother = request.input is EditorInput.Pick)
            } catch (error: OutOfMemoryError) {
                logFailure("load", FrameKitException(EditorErrorCode.INSUFFICIENT_MEMORY, cause = error))
                _state.value = ImageEditorUiState.LoadFailed(EditorErrorCode.INSUFFICIENT_MEMORY, canChooseAnother = request.input is EditorInput.Pick)
            }
        }
    }

    // IO 스레드에서 쪽 원본을 한 번만 등록하고 읽는다. 프로젝트의 SourceId가 바뀌지 않게 다시 쓰지 않는다.
    private fun openPage(page: Page): Pair<SourceId, ImageSourceInfo> {
        val sourceId = page.sourceId ?: registry.register(page.input).also { page.sourceId = it }
        val info = page.info ?: ImageMetadataReader(registry).read(sourceId).also { page.info = it }
        return sourceId to info
    }

    // ---- 여러 장 ----

    /** [index]번째 사진으로 옮긴다. 도구가 열려 있거나 저장 중이면 무시한다. */
    fun selectPage(index: Int) {
        val ready = _state.value as? ImageEditorUiState.Ready ?: return
        if (index == pageIndex || index !in pages.indices || ready.activeTool != null || ready.transaction.isActive || ready.export != null) return
        loadPage(index)
    }

    /** 사진을 끝에 더한다. 최대 개수를 넘는 사진은 버린다. */
    fun addPages(uris: List<Uri>) {
        val ready = _state.value as? ImageEditorUiState.Ready ?: return
        if (uris.isEmpty() || ready.export != null) return
        val room = pageLimit - pages.size
        if (room <= 0) return updateReady { it.copy(notice = SessionNotice.PAGE_LIMIT) }
        uris.take(room).forEach { pages += Page(newId(), EditorInput.UriSource(it)) }
        if (uris.size > room) updateReady { it.copy(notice = SessionNotice.PAGE_LIMIT) }
        pagesChanged = true
        persistPages()
        updateReady { it.copy(pageCount = pages.size) }
        loadPageThumbnails()
    }

    /** 지금 사진을 앞(-1)이나 뒤(+1)로 옮긴다. PDF와 결과 순서가 바뀐다. */
    fun moveCurrentPage(delta: Int) {
        val target = pageIndex + delta
        val ready = _state.value as? ImageEditorUiState.Ready ?: return
        if (target !in pages.indices || ready.export != null) return
        val page = pages.removeAt(pageIndex)
        pages.add(target, page)
        pageIndex = target
        pagesChanged = true
        persistPages()
        updateReady { it.copy(pageIndex = target) }
        _pageThumbnails.value = _pageThumbnails.value.toMap()
    }

    /** 지금 사진을 빼고 이웃 사진을 연다. 마지막 한 장은 뺄 수 없다. */
    fun removeCurrentPage() {
        val ready = _state.value as? ImageEditorUiState.Ready ?: return
        if (pages.size <= 1 || ready.export != null || ready.transaction.isActive) return
        val removed = pages.removeAt(pageIndex)
        recorders.remove(removed.id)?.end()
        _pageThumbnails.value = _pageThumbnails.value - removed.id
        pagesChanged = true
        val next = pageIndex.coerceAtMost(pages.lastIndex)
        persistPages()
        loadPage(next, stash = false)
    }

    private fun loadPageThumbnails() {
        if (pages.size <= 1 && pageLimit <= 1) return
        viewModelScope.launch {
            pages.toList().forEach { page ->
                if (page.id in _pageThumbnails.value) return@forEach
                val thumbnail = withContext(ioDispatcher) {
                    runCatching {
                        val (_, info) = openPage(page)
                        BitmapDecoder(registry, contentResolver).decode(info, SampleSize.forMinimumLongEdge(info.encodedSize, PAGE_THUMBNAIL_PX)).bitmap
                    }.getOrNull()
                } ?: return@forEach
                // 그사이 편집 내용으로 다시 그린 미리보기가 있으면 원본으로 덮지 않는다.
                _pageThumbnails.update { if (page.id in it) it else it + (page.id to thumbnail) }
            }
        }
    }

    /** 지금 사진 말고도 편집했거나 사진 목록을 바꿨으면 `true`. */
    private fun anyPageDirty(ready: ImageEditorUiState.Ready): Boolean =
        ready.isDirty || ready.hasDraftChanges || pagesChanged ||
            pages.withIndex().any { (index, page) -> index != pageIndex && page.transaction?.history?.isDirty == true }

    private fun restoredState(
        restored: SessionRecord?,
        sourceId: SourceId,
        info: ImageSourceInfo,
        preview: DecodedImage,
    ): ImageEditorUiState.Ready {
        val projectId = ProjectId(restored?.snapshot?.projectId ?: UUID.randomUUID().toString())
        val baseline = ImageProject(projectId, sourceId, watermark = config.watermark?.initialWatermark, grainSeed = restored?.snapshot?.grainSeed ?: Random.nextLong())
        // 저장된 값이 지금 원본에 맞지 않으면(예: crop 범위 밖) 복원하지 않고 처음부터 연다.
        val committed = restored?.snapshot?.toProject(sourceId)
            ?.takeIf { ImageProjectValidator.validate(it, info.metadata).isValid }
        var transaction = if (committed != null) {
            HistoryTransaction(EditHistory.restore(baseline, committed))
        } else {
            HistoryTransaction.start(baseline)
        }
        // 다른 사진에서 워터마크를 바꿨으면 처음 여는 사진에도 같은 워터마크를 넣는다.
        sharedWatermark?.let { (watermark) ->
            if (transaction.history.current.watermark != watermark) transaction = transaction.update(transaction.history.current.copy(watermark = watermark)).commit()
        }
        val notice = when {
            restored?.exportWasInterrupted == true -> SessionNotice.EXPORT_INTERRUPTED
            committed != null && !committed.sameContentAs(baseline) -> SessionNotice.RESTORED
            else -> null
        }
        if (restored?.exportWasInterrupted == true) session.saveCommitted(transaction.history.current)
        return ImageEditorUiState.Ready(preview, info, transaction, notice = notice)
    }

    fun dismissNotice() = updateReady { it.copy(notice = null) }

    fun selectTool(tool: ImageTool) {
        showChrome()
        updateReady { ready ->
            if (ready.activeTool != null || ready.export != null || ready.transaction.isActive || tool !in config.enabledTools) {
                return@updateReady ready
            }
            if (tool == ImageTool.WATERMARK && watermarkLocked) return@updateReady ready
            when {
                tool == ImageTool.TEXT -> openText(ready, existingId = null)
                tool == ImageTool.WATERMARK -> {
                    val begun = ready.transaction.begin()
                    val draft = checkNotNull(begun.draft)
                    val start = draft.watermark ?: lastWatermark ?: config.watermark?.template() ?: Watermark("")
                    ready.copy(activeTool = tool, transaction = begun.update(draft.copy(watermark = start)), selectedOverlayId = null)
                }
                tool == ImageTool.DOCUMENT -> ready.copy(
                    activeTool = tool,
                    documentQuad = ready.documentSuggestion ?: DocumentQuad.inset(),
                    selectedOverlayId = null,
                    documentSuggestionDismissed = true,
                )
                tool.isDraft -> ready.copy(activeTool = tool, cropAspect = CropAspectRatio.Free, transaction = ready.transaction.begin(), selectedOverlayId = null)
                else -> ready.copy(activeTool = tool)
            }
        }
        // 바로 기록되는 도구는 열 때의 상태를 기억해 두었다가, 취소하면 그 상태로 되돌린다.
        (_state.value as? ImageEditorUiState.Ready)?.takeIf { it.activeTool == tool && !tool.isDraft }?.let { toolEntry = it.transaction.history.current }
        // 상태가 바뀐 뒤에 감지를 시작해야 결과가 열린 도구에 반영된다.
        (_state.value as? ImageEditorUiState.Ready)
            ?.takeIf { it.activeTool == ImageTool.DOCUMENT && tool == ImageTool.DOCUMENT && it.documentSuggestion == null }
            ?.let(::detectDocumentLater)
    }


    /** 기존 텍스트 오버레이에 대해 텍스트 도구를 연다. */
    override fun editText(id: String) = updateReady { ready ->
        val canEdit = ready.activeTool == null && ready.export == null && !ready.transaction.isActive && ImageTool.TEXT in config.enabledTools
        if (!canEdit || ready.displayed.overlays.none { it.id == id && it is ImageOverlay.Text }) ready else openText(ready, id)
    }

    private fun openText(ready: ImageEditorUiState.Ready, existingId: String?): ImageEditorUiState.Ready {
        val begun = ready.transaction.begin()
        val draft = checkNotNull(begun.draft)
        val id = existingId ?: newId()
        val project = if (existingId != null) draft else draft.copy(overlays = draft.overlays + ImageOverlay.Text(id, ""))
        return ready.copy(activeTool = ImageTool.TEXT, transaction = begun.update(project), editingTextId = id, selectedOverlayId = id)
    }

    fun updateText(text: String) = updateEditingText { it.copy(text = text) }

    fun updateTextStyle(transform: (TextStyleSpec) -> TextStyleSpec) = updateEditingText { it.copy(style = transform(it.style)) }

    fun updateTextOpacity(opacity: Double) = updateEditingText { it.copy(transform = it.transform.copy(opacity = opacity.coerceIn(0.0, 1.0))) }

    private fun updateEditingText(transform: (ImageOverlay.Text) -> ImageOverlay.Text) = updateReady { ready ->
        val id = ready.editingTextId ?: return@updateReady ready
        val draft = ready.transaction.draft ?: return@updateReady ready
        val text = draft.overlays.firstOrNull { it.id == id } as? ImageOverlay.Text ?: return@updateReady ready
        ready.copy(transaction = ready.transaction.update(OverlayEditing.replace(draft, transform(text))))
    }

    override fun selectOverlay(id: String?) = updateReady { ready ->
        if (ready.activeTool?.isGeometry == true || ready.activeTool == ImageTool.TEXT) return@updateReady ready
        // 그리기 도구에서 도형을 고르면 패널이 그 도형의 모양을 보여 주고, 바꾸면 그 도형에 적용된다.
        val shape = ready.displayed.overlays.firstOrNull { it.id == id } as? ImageOverlay.Shape
        if (ready.activeTool == ImageTool.DRAW && shape != null) ready.copy(selectedOverlayId = id, brush = ready.brush.matching(shape)) else ready.copy(selectedOverlayId = id)
    }

    override fun deleteOverlay(id: String) {
        commitImmediate { OverlayEditing.remove(it, id) }
        updateReady { if (it.selectedOverlayId == id) it.copy(selectedOverlayId = null) else it }
    }

    override fun duplicateOverlay(id: String) {
        val copyId = newId()
        commitImmediate { OverlayEditing.duplicate(it, id, copyId) }
        updateReady { it.copy(selectedOverlayId = copyId) }
    }

    override fun beginOverlayGesture(id: String) {
        val ready = _state.value as? ImageEditorUiState.Ready ?: return
        overlayGestureStart = ready.displayed.overlays.firstOrNull { it.id == id }
        updateReady { it.copy(selectedOverlayId = id) }
    }

    /** 제스처 시작 이후의 이동(정규화 캔버스 단위), 확대(배율), 회전(도). */
    override fun updateOverlayGesture(panX: Double, panY: Double, zoom: Double, rotation: Double, snapX: Double, snapY: Double) {
        val start = overlayGestureStart ?: return
        updateGesture { _, project ->
            OverlayEditing.replace(project, start.withTransform(OverlayEditing.transform(start.transform, panX, panY, zoom, rotation, snapX, snapY)))
        }
    }

    override fun finishOverlayGesture() {
        overlayGestureStart = null
        finishGesture()
    }

    fun selectStickerCategory(category: EmojiCatalog.Category?) = updateReady { it.copy(stickerCategory = category) }

    /** 캔버스 중앙에 스티커(이모지나 호스트 이미지의 에셋 id)를 실행 취소 한 단계로 추가하고 선택한다. */
    fun addSticker(assetId: String) {
        val id = newId()
        commitImmediate { it.copy(overlays = it.overlays + ImageOverlay.Sticker(id, assetId)) }
        updateReady { it.copy(selectedOverlayId = id) }
    }

    /**
     * 그리기 설정을 바꾼다. 도형을 고른 상태면 그 도형에도 적용한다.
     *
     * @param commit `false`면 슬라이더를 끄는 중이라 커밋을 [finishBrushChange]로 미룬다.
     */
    fun updateBrush(commit: Boolean = true, transform: (BrushSettings) -> BrushSettings) {
        val ready = _state.value as? ImageEditorUiState.Ready ?: return
        val next = transform(ready.brush)
        val selected = ready.selectedOverlayId?.let { id -> ready.displayed.overlays.firstOrNull { it.id == id } } as? ImageOverlay.Shape
        // 선과 상자 도형은 모양을 서로 바꿀 수 없어, 다른 쪽 종류를 고르면 선택을 풀고 새로 그릴 종류로만 쓴다.
        val keepSelection = selected != null && next.shapes && next.shape.isLine == selected.kind.isLine
        updateReady { it.copy(brush = next, selectedOverlayId = if (keepSelection || selected == null) it.selectedOverlayId else null) }
        if (!keepSelection || selected == null) return
        val restyled = selected.restyled(next)
        if (restyled == selected) return
        updateGesture { _, project -> OverlayEditing.replace(project, restyled) }
        if (commit) finishGesture()
    }

    /** 슬라이더로 고른 도형 모양을 실행 취소 한 단계로 커밋한다. */
    fun finishBrushChange() = finishGesture()

    override fun beginShape(x: Double, y: Double) {
        val ready = _state.value as? ImageEditorUiState.Ready ?: return
        if (ready.activeTool != ImageTool.DRAW) return
        shapeAnchor = PointN(x, y)
        val brush = ready.brush
        val shape = ImageOverlay.Shape(
            id = newId(),
            kind = brush.shape,
            widthRatio = MIN_SHAPE_RATIO,
            heightRatio = if (brush.shape.isLine) 0.0 else MIN_SHAPE_RATIO,
            style = brush.shapeStyle(),
            transform = OverlayTransform(center = PointN(x, y), opacity = brush.opacity),
        )
        updateReady { it.copy(selectedOverlayId = null) }
        updateGesture { _, project -> project.copy(overlays = project.overlays + shape) }
    }

    override fun dragShape(x: Double, y: Double, aspect: Double) {
        val anchor = shapeAnchor ?: return
        updateGesture { _, project ->
            val last = project.overlays.lastOrNull() as? ImageOverlay.Shape ?: return@updateGesture project
            project.copy(overlays = project.overlays.dropLast(1) + ShapeDrawing.span(last, anchor, PointN(x, y), aspect))
        }
    }

    override fun beginShapeResize(id: String) {
        val ready = _state.value as? ImageEditorUiState.Ready ?: return
        overlayGestureStart = ready.displayed.overlays.firstOrNull { it.id == id } as? ImageOverlay.Shape
        updateReady { it.copy(selectedOverlayId = id) }
    }

    override fun resizeShape(dx: Double, dy: Double, aspect: Double) {
        val start = overlayGestureStart as? ImageOverlay.Shape ?: return
        // 선 굵기(짧은 변 대비)를 캔버스 높이 단위로 바꾼다.
        val strokeRatio = start.style.strokeWidthShortEdgeRatio * aspect.coerceAtMost(1.0)
        updateGesture { _, project -> OverlayEditing.replace(project, ShapeDrawing.resize(start, dx, dy, aspect, strokeRatio)) }
    }

    override fun finishShape() {
        shapeAnchor = null
        val ready = _state.value as? ImageEditorUiState.Ready ?: return
        val shape = ready.transaction.draft?.overlays?.lastOrNull() as? ImageOverlay.Shape
        if (shape == null || ShapeDrawing.isTooSmall(shape)) {
            updateReady { if (it.transaction.isActive) it.copy(transaction = it.transaction.cancel()) else it }
            return
        }
        finishGesture()
        updateReady { it.copy(selectedOverlayId = shape.id) }
    }

    /**
     * 정규화 캔버스 좌표에서 획을 시작한다. 커밋은 [finishStroke]가 한다. 가리기 도구가 열려 있으면
     * 획 대신 브러시 마스크를 칠하거나 사각형·타원 영역을 잡는다.
     */
    override fun beginStroke(x: Double, y: Double, pressure: Double) = updateGesture { ready, project ->
        if (ready.activeTool == ImageTool.PRIVACY) {
            maskAnchor = PointN(x, y)
            val settings = ready.privacy
            val shape = when (settings.shape) {
                PrivacyShape.BRUSH -> MaskShape.Brush(listOf(PointN(x, y)), settings.brushWidthShortEdgeRatio)
                PrivacyShape.RECTANGLE -> MaskShape.Rectangle(RectN.spanning(PointN(x, y), PointN(x, y)))
                PrivacyShape.ELLIPSE -> MaskShape.Ellipse(RectN.spanning(PointN(x, y), PointN(x, y)))
            }
            return@updateGesture project.copy(privacyMasks = project.privacyMasks + PrivacyMask(newId(), shape, settings.effect()))
        }
        val brush = ready.brush
        val stroke = DrawingStroke(newId(), listOf(StrokePoint(x, y, pressure)), brush.widthShortEdgeRatio, brush.colorArgb, brush.opacity, brush.kind)
        project.copy(drawing = project.drawing + stroke)
    }

    /** @param minDistanceX 샘플을 솎아내기 위해 기록하는 최소 이동 거리(정규화 단위). */
    override fun extendStroke(x: Double, y: Double, pressure: Double, minDistanceX: Double, minDistanceY: Double) = updateGesture { ready, project ->
        if (ready.activeTool == ImageTool.PRIVACY) return@updateGesture extendMask(project, x, y, minDistanceX, minDistanceY)
        val last = project.drawing.lastOrNull() ?: return@updateGesture project
        val extended = OverlayEditing.appendPoint(last, StrokePoint(x, y, pressure), minDistanceX, minDistanceY)
        if (extended === last) project else project.copy(drawing = project.drawing.dropLast(1) + extended)
    }

    override fun cancelStroke() {
        maskAnchor = null
        shapeAnchor = null
        updateReady { ready -> if (ready.activeTool?.isDraft == true || !ready.transaction.isActive) ready else ready.copy(transaction = ready.transaction.cancel()) }
    }

    override fun finishStroke() {
        val ready = _state.value as? ImageEditorUiState.Ready
        val mask = ready?.transaction?.draft?.privacyMasks?.lastOrNull()
        // 손가락을 거의 움직이지 않은 사각형·원은 의도한 마스크가 아니므로 추가하지 않는다.
        val degenerate = ready?.activeTool == ImageTool.PRIVACY && mask != null && when (val shape = mask.shape) {
            is MaskShape.Rectangle -> shape.rect.width < MIN_MASK_SIZE || shape.rect.height < MIN_MASK_SIZE
            is MaskShape.Ellipse -> shape.rect.width < MIN_MASK_SIZE || shape.rect.height < MIN_MASK_SIZE
            is MaskShape.Brush -> false
        }
        maskAnchor = null
        if (degenerate) updateReady { it.copy(transaction = it.transaction.cancel()) } else finishGesture()
    }

    private fun extendMask(project: ImageProject, x: Double, y: Double, minDistanceX: Double, minDistanceY: Double): ImageProject {
        val last = project.privacyMasks.lastOrNull() ?: return project
        val anchor = maskAnchor ?: return project
        // 한 축으로만 끌어도 비어 있는 사각형이 되지 않게 최소 크기를 보장한다(빈 사각형은 검증에서 거부된다).
        val rect = RectN.spanning(anchor, PointN(x, y))
        val shape = when (val current = last.shape) {
            is MaskShape.Brush -> {
                val previous = current.points.last()
                val farEnough = abs(previous.x - x) >= minDistanceX || abs(previous.y - y) >= minDistanceY
                if (!farEnough || current.points.size >= DrawingStroke.MAX_POINTS) return project
                current.copy(points = current.points + PointN(x, y))
            }
            is MaskShape.Rectangle -> MaskShape.Rectangle(rect)
            is MaskShape.Ellipse -> MaskShape.Ellipse(rect)
        }
        return project.copy(privacyMasks = project.privacyMasks.dropLast(1) + last.copy(shape = shape))
    }

    fun updatePrivacy(transform: (PrivacySettings) -> PrivacySettings) = updateReady { it.copy(privacy = transform(it.privacy)) }

    /** 초안 도구 적용. 도구 세션 전체가 실행 취소 한 단계가 된다. 빈 텍스트는 버린다. */
    fun applyTool() = updateReady { ready ->
        if (ready.activeTool?.isDraft != true) return@updateReady ready
        var transaction = ready.transaction
        val editing = ready.editingTextId
        val draft = transaction.draft
        if (editing != null && draft != null) {
            val text = draft.overlays.firstOrNull { it.id == editing } as? ImageOverlay.Text
            if (text != null && text.text.isBlank()) transaction = transaction.update(OverlayEditing.remove(draft, editing))
        }
        if (ready.activeTool == ImageTool.WATERMARK) {
            val current = transaction.draft
            val watermark = current?.watermark?.takeIf { it.text.isNotBlank() }?.normalized()
            if (current != null) transaction = transaction.update(current.copy(watermark = watermark))
            if (watermark != null) lastWatermark = watermark
            shareWatermark(watermark)
        }
        val committed = transaction.commit()
        val selected = ready.selectedOverlayId?.takeIf { id -> committed.history.current.overlays.any { it.id == id } }
        ready.copy(activeTool = null, transaction = committed, editingTextId = null, selectedOverlayId = selected)
    }

    // ---- 워터마크 ----

    /** 워터마크 도구에서 고친 값. 문구가 비어 있는 동안은 그려지지 않고, 그대로 적용하면 워터마크가 없어진다. */
    fun updateWatermark(transform: (Watermark) -> Watermark) = updateReady { ready ->
        val draft = ready.transaction.draft ?: return@updateReady ready
        if (ready.activeTool != ImageTool.WATERMARK) return@updateReady ready
        ready.copy(transaction = ready.transaction.update(draft.copy(watermark = transform(draft.watermark ?: Watermark("")))))
    }

    /** 워터마크를 없애고 도구를 닫는다. 실행 취소 한 단계다. */
    fun removeWatermark() {
        updateWatermark { it.copy(text = "") }
        applyTool()
    }

    /** 호스트가 워터마크를 잠갔으면 사용자가 바꿀 수 없다. */
    internal val watermarkLocked: Boolean get() = config.watermark?.locked == true

    internal val watermarkSuggestions: List<String> get() = config.watermark?.suggestions.orEmpty()

    // 여러 장이면 모든 사진에 같은 워터마크를 넣는다. 아직 열지 않은 사진은 열 때 넣는다.
    private fun shareWatermark(watermark: Watermark?) {
        if (pages.size <= 1) return
        sharedWatermark = Shared(watermark)
        pages.forEachIndexed { index, page ->
            if (index == pageIndex) return@forEachIndexed
            val transaction = page.transaction ?: return@forEachIndexed
            if (transaction.history.current.watermark != watermark) {
                page.transaction = transaction.update(transaction.history.current.copy(watermark = watermark)).commit()
            }
        }
        pagesChanged = true
    }

    // 잠긴 워터마크는 복원된 세션 등과 상관없이 저장할 때 항상 넣는다.
    private fun enforced(project: ImageProject): ImageProject =
        if (watermarkLocked) project.copy(watermark = config.watermark?.initialWatermark) else project

    /** 초안 도구 취소. 프로젝트는 도구를 열기 전 상태로 돌아간다. */
    fun cancelTool() = updateReady { ready ->
        if (ready.activeTool?.isDraft != true) return@updateReady ready
        val cancelled = ready.transaction.cancel()
        val selected = ready.selectedOverlayId?.takeIf { id -> cancelled.history.current.overlays.any { it.id == id } }
        ready.copy(activeTool = null, transaction = cancelled, editingTextId = null, selectedOverlayId = selected)
    }

    /** 변경이 이미 커밋된 도구(보정, 필터)를 닫는다. */
    fun closeTool() {
        closeToolState()
        if ((_state.value as? ImageEditorUiState.Ready)?.activeTool == null) toolEntry = null
    }

    private fun closeToolState() = updateReady { ready ->
        when {
            ready.documentBusy -> ready
            ready.activeTool?.isDraft != false || ready.transaction.isActive -> ready
            else -> ready.copy(activeTool = null, documentQuad = null)
        }
    }

    // ---- 문서 보정 ----

    // 사진을 열 때 한 번 문서인지 판별한다. 문서로 보일 때만 문서 보정 도구와 제안을 보여 준다.
    private fun checkDocument(page: Page, preview: Bitmap) {
        if (page.documentChecked || ImageTool.DOCUMENT !in config.enabledTools) return
        page.documentChecked = true
        // 이미 문서 보정으로 만든 쪽은 다시 제안하지 않는다.
        if (page.original != null) return
        viewModelScope.launch {
            val analysis = withContext(ioDispatcher) { runCatching { DocumentRectifier.analyze(preview) }.getOrNull() }
            // 사진 내용 없이 판별 결과만 남겨, 문서가 안 잡힐 때 이유를 기기 로그로 확인할 수 있게 한다.
            Log.d(TAG, "document check: ${analysis?.reason ?: "failed"}")
            // PDF로 묶을 때는 사용자가 문서를 넣었다고 보고 글자 확인 없이 감지 결과를 쓴다.
            val found = analysis?.quad?.let { quad -> if (documentFirst || confirmText(preview, quad)) quad else null }
            page.documentSuggestion = found
            if (found != null) page.documentOutline = analysis.outline
            if (found != null && pages.getOrNull(pageIndex) === page) updateReady { it.copy(documentSuggestion = found) }
        }
    }

    /**
     * 밝기로 찾은 문서 후보 안에 실제 글자가 있는지 글자 인식으로 확인한다. 벽·선반처럼 종이와 비슷하지만 글자가 없는
     * 영역을 걸러 낸다. 인식 모듈이 없거나 인식하지 못하면(모델 다운로드 중 등) 밝기 판별을 그대로 믿는다.
     */
    private suspend fun confirmText(preview: Bitmap, quad: DocumentQuad): Boolean {
        val recognizer = textRecognizer ?: return true
        val lines = try {
            recognizer.recognize(preview)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return true
        }
        val left = quad.corners.minOf { it.x } * preview.width
        val right = quad.corners.maxOf { it.x } * preview.width
        val top = quad.corners.minOf { it.y } * preview.height
        val bottom = quad.corners.maxOf { it.y } * preview.height
        val characters = lines
            .filter { line -> (line.left + line.right) / 2 in left..right && (line.top + line.bottom) / 2 in top..bottom }
            .sumOf { line -> line.text.count { it.isLetterOrDigit() } }
        Log.d(TAG, "document text check: $characters characters")
        return characters >= MIN_DOCUMENT_CHARACTERS
    }

    // 쪽을 다시 연 뒤 띄울 안내. 다시 여는 동안에는 Ready 상태가 아니라 바로 띄울 수 없다.
    private var pendingNotice: SessionNotice? = null

    /**
     * PDF로 저장하는 편집이면 `true`. 이때는 문서 보정 도구를 감지 결과와 상관없이 맨 앞에 두고, 여러 쪽을 한 번에
     * 스캔할 수 있게 한다.
     */
    val documentFirst: Boolean get() = request.export.format == ImageFormat.PDF

    /**
     * 아직 보정하지 않은 모든 쪽을 지금 고른 스캔 방식으로 한 번에 편다. 지금 쪽은 맞춰 둔 모서리를, 다른 쪽은 자동으로
     * 찾은 모서리를 쓴다. 문서를 찾지 못한 쪽은 그대로 두고 안내한다. 쪽마다 보정·필터는 이어진다.
     */
    fun scanAllPages() {
        val ready = _state.value as? ImageEditorUiState.Ready ?: return
        val directory = documentDirectory ?: return
        if (ready.activeTool != ImageTool.DOCUMENT || ready.documentBusy || ready.export != null || pages.size < 2) return
        stashCurrentPage()
        val current = pages.getOrNull(pageIndex) ?: return
        val currentQuad = ready.documentQuad?.takeIf { it.isUsable }
        val mode = ready.scanMode
        val targets = pages.filter { it.original == null }
        if (targets.isEmpty()) return
        updateReady { it.copy(documentBusy = true, documentBatch = DocumentBatch(0, targets.size)) }
        viewModelScope.launch {
            var skipped = 0
            var dropped = false
            targets.forEachIndexed { index, page ->
                val output = File(directory, "${UUID.randomUUID()}.jpg")
                val written = withContext(ioDispatcher) {
                    try {
                        val (_, info) = openPage(page)
                        val detected = if ((page === current && currentQuad != null) || page.documentSuggestion != null) null else detectPage(info)
                        val quad = (if (page === current) currentQuad else null) ?: page.documentSuggestion ?: detected?.quad
                        // 감지한 모서리를 그대로 쓰면 휜 테두리 곡선도 함께 써서 편다.
                        val outline = (detected?.outline ?: page.documentOutline)?.takeIf { it.quad == quad }
                        if (quad == null) {
                            false
                        } else {
                            DocumentRectifier(registry, contentResolver, memoryBudgetBytes).rectify(info, quad, output, mode, outline = outline)
                            true
                        }
                    } catch (error: FrameKitException) {
                        logFailure("document", error)
                        output.delete()
                        false
                    } catch (error: OutOfMemoryError) {
                        logFailure("document", FrameKitException(EditorErrorCode.INSUFFICIENT_MEMORY, cause = error))
                        output.delete()
                        false
                    }
                }
                if (written) {
                    val replacement = Page(newId(), EditorInput.FileSource(output.absolutePath), original = page.input)
                    page.transaction?.history?.current?.let { project ->
                        replacement.carry = { fresh -> fresh.copy(adjustments = project.adjustments, filter = project.filter) }
                        dropped = dropped || project.hasPlacedEdits
                    }
                    replacement.recognizeAfterLoad = page === current
                    swapPage(page, replacement)
                } else {
                    skipped++
                }
                updateReady { it.copy(documentBatch = DocumentBatch(index + 1, targets.size)) }
            }
            pagesChanged = true
            persistPages()
            updateReady { it.copy(documentBatch = null) }
            loadPage(pageIndex, stash = false)
            loadPageThumbnails()
            val notice = when {
                skipped > 0 -> SessionNotice.DOCUMENT_BATCH_SKIPPED
                dropped -> SessionNotice.DOCUMENT_APPLIED
                else -> null
            }
            if (notice != null) pendingNotice = notice
        }
    }

    // 화면에 띄우지 않은 쪽의 문서를 찾는다. 감지는 작은 이미지로 충분하므로 작게 읽는다.
    private fun detectPage(info: ImageSourceInfo): DocumentDetector.Analysis {
        val decoded = BitmapDecoder(registry, contentResolver).decode(info, SampleSize.forMinimumLongEdge(info.encodedSize, DETECT_LONG_EDGE))
        return try {
            DocumentRectifier.analyze(decoded.bitmap)
        } finally {
            decoded.recycle()
        }
    }

    /** 문서 감지 제안을 닫는다. 도구는 툴바에 그대로 남는다. */
    fun dismissDocumentSuggestion() {
        pages.getOrNull(pageIndex)?.documentSuggestionDismissed = true
        updateReady { it.copy(documentSuggestionDismissed = true) }
    }

    fun selectScanMode(mode: ScanMode) = updateReady { if (it.documentBusy) it else it.copy(scanMode = mode) }

    // 문서를 편 뒤 글자를 인식해, 스캔이 글자까지 읽었다는 것을 보여 주고 복사할 수 있게 한다.
    private fun recognizeDocument(page: Page, preview: Bitmap) {
        val recognizer = textRecognizer ?: return
        viewModelScope.launch {
            val text = try {
                recognizer.recognize(preview).joinToString("\n") { it.text }.trim().ifEmpty { null }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            page.documentText = text
            if (text != null && pages.getOrNull(pageIndex) === page) updateReady { it.copy(documentText = text) }
        }
    }

    fun showDocumentText(show: Boolean) = updateReady { it.copy(showDocumentText = show && it.documentText != null) }

    private fun detectDocumentLater(ready: ImageEditorUiState.Ready) {
        val preview = ready.preview.bitmap
        val page = pages.getOrNull(pageIndex)
        viewModelScope.launch {
            val analysis = withContext(ioDispatcher) { runCatching { DocumentRectifier.analyze(preview) }.getOrNull() }
            val found = analysis?.quad
            if (found != null) page?.documentOutline = analysis.outline
            // 그새 사용자가 모서리를 옮겼으면 덮어쓰지 않는다.
            updateReady { current ->
                if (current.activeTool == ImageTool.DOCUMENT && current.documentQuad == DocumentQuad.inset() && found != null) current.copy(documentQuad = found) else current
            }
        }
    }

    /** 자동으로 다시 찾는다. 찾지 못하면 가장자리에서 조금 들어온 사각형으로 둔다. */
    fun detectDocument() {
        val ready = _state.value as? ImageEditorUiState.Ready ?: return
        if (ready.activeTool != ImageTool.DOCUMENT || ready.documentBusy) return
        val preview = ready.preview.bitmap
        val page = pages.getOrNull(pageIndex)
        viewModelScope.launch {
            val analysis = withContext(ioDispatcher) { runCatching { DocumentRectifier.analyze(preview) }.getOrNull() }
            val found = analysis?.quad
            if (found != null) page?.documentOutline = analysis.outline
            updateReady { if (it.activeTool == ImageTool.DOCUMENT) it.copy(documentQuad = found ?: DocumentQuad.inset()) else it }
        }
    }

    /** 사진 전체를 문서로 둔다. 이미 반듯한 문서의 원근만 살짝 고칠 때 시작점으로 쓴다. */
    fun useWholeImage() = updateReady { if (it.activeTool == ImageTool.DOCUMENT && !it.documentBusy) it.copy(documentQuad = DocumentQuad.inset(0.0)) else it }

    /** [corner](0: 왼쪽 위부터 시계 방향)를 정규화 좌표 ([x], [y])로 옮긴다. 이미지 밖으로 나가지 않는다. */
    override fun moveDocumentCorner(corner: Int, x: Double, y: Double) = updateReady { ready ->
        val quad = ready.documentQuad ?: return@updateReady ready
        if (ready.documentBusy || corner !in 0..3) return@updateReady ready
        val point = PointN(x.coerceIn(0.0, 1.0), y.coerceIn(0.0, 1.0))
        val moved = when (corner) {
            0 -> quad.copy(topLeft = point)
            1 -> quad.copy(topRight = point)
            2 -> quad.copy(bottomRight = point)
            else -> quad.copy(bottomLeft = point)
        }
        ready.copy(documentQuad = moved)
    }

    /**
     * 맞춘 모서리로 문서를 펴서 지금 쪽의 새 원본으로 쓴다. 보정·필터는 이어지고, 위치가 바뀌는 편집은 초기화한다.
     * 실패하면 편집은 그대로 두고 안내만 띄운다.
     */
    fun applyDocument() {
        val ready = _state.value as? ImageEditorUiState.Ready ?: return
        val quad = ready.documentQuad ?: return
        val directory = documentDirectory ?: return
        if (ready.activeTool != ImageTool.DOCUMENT || ready.documentBusy || ready.export != null) return
        if (!quad.isUsable) return
        val page = pages.getOrNull(pageIndex) ?: return
        val current = ready.transaction.history.current
        updateReady { it.copy(documentBusy = true) }
        viewModelScope.launch {
            val output = File(directory, "${UUID.randomUUID()}.jpg")
            val written = withContext(ioDispatcher) {
                try {
                    // 사용자가 모서리를 옮기지 않았으면 감지한 휜 테두리 곡선을 따라 편다.
                    val outline = page.documentOutline?.takeIf { it.quad == quad }
                    DocumentRectifier(registry, contentResolver, memoryBudgetBytes).rectify(ready.source, quad, output, ready.scanMode, outline = outline)
                    true
                } catch (error: FrameKitException) {
                    logFailure("document", error)
                    output.delete()
                    false
                } catch (error: OutOfMemoryError) {
                    logFailure("document", FrameKitException(EditorErrorCode.INSUFFICIENT_MEMORY, cause = error))
                    output.delete()
                    false
                }
            }
            if (!written) {
                updateReady { it.copy(documentBusy = false, notice = SessionNotice.DOCUMENT_FAILED) }
                return@launch
            }
            val replacement = Page(newId(), EditorInput.FileSource(output.absolutePath), original = page.original ?: page.input)
            val dropped = current.hasPlacedEdits
            replacement.carry = { fresh -> fresh.copy(adjustments = current.adjustments, filter = current.filter) }
            replacement.recognizeAfterLoad = true
            // 쪽을 다시 여는 동안 상태가 바뀌므로, 안내는 다시 연 뒤에 띄운다.
            if (dropped) pendingNotice = SessionNotice.DOCUMENT_APPLIED
            replacePage(page, replacement)
        }
    }

    /** 문서 보정으로 바꾼 쪽을 처음 원본으로 되돌린다. 보정·필터는 이어진다. */
    fun restoreOriginalDocument() {
        val ready = _state.value as? ImageEditorUiState.Ready ?: return
        if (ready.documentBusy || ready.export != null || ready.transaction.isActive) return
        val page = pages.getOrNull(pageIndex) ?: return
        val original = page.original ?: return
        val current = ready.transaction.history.current
        val replacement = Page(newId(), original)
        replacement.carry = { fresh -> fresh.copy(adjustments = current.adjustments, filter = current.filter) }
        replacePage(page, replacement)
    }

    private fun replacePage(old: Page, replacement: Page) {
        val index = swapPage(old, replacement) ?: return
        pagesChanged = true
        persistPages()
        loadPage(index, stash = false)
        loadPageThumbnails()
    }

    // 쪽 목록에서 [old]를 [replacement]로 바꾸고, 문서 보정으로 만든 이전 중간 파일을 정리한다. 바꾼 위치를 돌려준다.
    private fun swapPage(old: Page, replacement: Page): Int? {
        val index = pages.indexOf(old).takeIf { it >= 0 } ?: return null
        pages[index] = replacement
        recorders.remove(old.id)?.end()
        // 문서 보정으로 만든 중간 파일은 다른 쪽이 쓰지 않으면 지운다.
        (old.input as? EditorInput.FileSource)?.let { input ->
            val file = File(input.absolutePath)
            if (documentDirectory != null && file.parentFile == documentDirectory && pages.none { (it.input as? EditorInput.FileSource)?.absolutePath == input.absolutePath }) {
                viewModelScope.launch(ioDispatcher) { file.delete() }
            }
        }
        _pageThumbnails.value = _pageThumbnails.value - old.id
        return index
    }

    fun resetTool() {
        when ((_state.value as? ImageEditorUiState.Ready)?.activeTool) {
            ImageTool.ADJUST -> return commitImmediate { it.copy(adjustments = Adjustments()) }
            ImageTool.FILTER -> return commitImmediate { it.copy(filter = FilterSelection()) }
            else -> Unit
        }
        updateReady { it.copy(cropAspect = CropAspectRatio.Free) }
        updateDraft { ready, geometry ->
            when (ready.activeTool) {
                ImageTool.CROP -> geometry.copy(crop = CropBoundsCalculator.maxCrop(frameOf(ready, geometry), CropAspectRatio.Free))
                ImageTool.ROTATE -> GeometryEdit()
                else -> geometry
            }
        }
    }

    /** 미리보기에서 피사체를 찾아 마스크를 에셋으로 저장하고 누끼를 실행 취소 한 단계로 커밋한다. */
    fun removeBackground() {
        val ready = _state.value as? ImageEditorUiState.Ready ?: return
        val remover = backgroundRemover ?: return
        if (ready.cutoutStatus == CutoutStatus.Processing || ready.cutoutStatus.blocksRemoval || ready.transaction.isActive) return
        val assets = assets() ?: return
        updateReady { it.copy(cutoutStatus = CutoutStatus.Processing) }
        viewModelScope.launch {
            try {
                val mask = remover.subjectMask(ready.preview.bitmap)
                val id = withContext(ioDispatcher) { CutoutMasks.save(mask, assets) }
                _cutoutMasks.update { it + (id to mask) }
                commitImmediate { it.copy(cutout = SubjectCutout(id)) }
                updateReady { it.copy(cutoutStatus = null) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: FrameKitException) {
                logFailure("cutout", error)
                when {
                    !remover.isAvailable -> updateReady { it.copy(cutoutStatus = CutoutStatus.DeviceUnsupported) }
                    // 모델이 없어졌거나(Play 서비스가 지운 경우 등) 아직이면 상태를 다시 확인해 내려받기 안내로 이어 간다.
                    error.code == EditorErrorCode.UNSUPPORTED_OPERATION -> recheckCutoutModel(fallback = CutoutStatus.Failed(error.code))
                    else -> updateReady { it.copy(cutoutStatus = CutoutStatus.Failed(error.code)) }
                }
            } catch (error: Exception) {
                logFailure("cutout", FrameKitException(EditorErrorCode.UNKNOWN, cause = error))
                updateReady { it.copy(cutoutStatus = CutoutStatus.Failed(EditorErrorCode.UNKNOWN)) }
            }
        }
    }

    // ---- 배경 제거 모델 ----

    /** 배경 제거 도구가 열려 있으면 한 번 모델 상태를 확인한다. 세션을 복원해 도구가 열린 채 시작할 때도 여기로 온다. */
    private fun ensureCutoutModelChecked(ready: ImageEditorUiState.Ready) {
        if (ready.activeTool != ImageTool.CUTOUT || cutoutModelChecked || cutoutModelJob?.isActive == true) return
        val remover = backgroundRemover ?: return
        cutoutModelChecked = true
        if (ready.cutoutStatus == null) updateReady { it.copy(cutoutStatus = CutoutStatus.ModelChecking) }
        cutoutModelJob = viewModelScope.launch {
            val state = queryModelState(remover)
            applyModelState(state)
            // 전에 받기 시작한 것이 끝나지 않았으면(앱이 중간에 끝난 경우 등) 이어서 따라간다. 사용자가 이미 받기로 했으므로
            // 다 받으면 다시 누르지 않아도 바로 배경을 지운다.
            if (state is OnDeviceModelState.Downloading) {
                removeAfterDownload = true
                collectDownload(remover)
            }
        }
    }

    private fun recheckCutoutModel(fallback: CutoutStatus) {
        val remover = backgroundRemover ?: return
        cutoutModelJob = viewModelScope.launch {
            val state = queryModelState(remover)
            if (state is OnDeviceModelState.Ready) updateReady { it.copy(cutoutStatus = fallback) } else applyModelState(state)
        }
    }

    private suspend fun queryModelState(remover: BackgroundRemover): OnDeviceModelState = try {
        remover.modelState()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        // 확인이 실패하면 막지 않는다. 실행해 보고 모델이 없으면 그때 다시 안내한다.
        OnDeviceModelState.Ready
    }

    /** 패널의 "모델 내려받기". 데이터 사용 안내를 먼저 보여 준다. */
    fun requestModelDownload() = updateReady { it.copy(showModelDownloadDialog = true) }

    fun dismissModelDownloadDialog() = updateReady { it.copy(showModelDownloadDialog = false) }

    /** 안내를 보고 받기로 했다. 다 받으면 도구가 열려 있을 때 바로 배경을 지운다. */
    fun confirmModelDownload() {
        updateReady { it.copy(showModelDownloadDialog = false) }
        startModelDownload()
    }

    /** 내려받기 실패 후 다시 시도. 이미 데이터 사용에 동의했으므로 다시 묻지 않는다. */
    fun retryModelDownload() = startModelDownload()

    private fun startModelDownload() {
        val remover = backgroundRemover ?: return
        removeAfterDownload = true
        cutoutModelJob?.cancel()
        cutoutModelJob = viewModelScope.launch { collectDownload(remover) }
    }

    private suspend fun collectDownload(remover: BackgroundRemover) {
        try {
            remover.downloadModel().collect { state ->
                applyModelState(state)
                if (state is OnDeviceModelState.Ready) onCutoutModelReady()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            applyModelState(OnDeviceModelState.DownloadFailed(OnDeviceModelState.Failure.OTHER))
        }
    }

    private fun applyModelState(state: OnDeviceModelState) {
        val status = when (state) {
            OnDeviceModelState.Ready -> null
            OnDeviceModelState.NeedsDownload -> CutoutStatus.ModelNeedsDownload
            is OnDeviceModelState.Downloading -> CutoutStatus.ModelDownloading(state.bytesDownloaded, state.totalBytes, state.installing)
            is OnDeviceModelState.DownloadFailed -> CutoutStatus.ModelDownloadFailed(state.reason == OnDeviceModelState.Failure.NO_NETWORK)
            is OnDeviceModelState.Unavailable -> when (state.reason) {
                OnDeviceModelState.Unsupported.NO_PLAY_SERVICES -> CutoutStatus.NoPlayServices
                OnDeviceModelState.Unsupported.DEVICE -> CutoutStatus.DeviceUnsupported
            }
        }
        // 배경을 지우는 중이면 그 표시를 덮지 않는다.
        updateReady { if (it.cutoutStatus == CutoutStatus.Processing) it else it.copy(cutoutStatus = status) }
    }

    // 다 받으면 따로 누르지 않아도 이어서 쓸 수 있게 한다. 도구를 닫았으면 알림만 띄운다.
    private fun onCutoutModelReady() {
        val ready = _state.value as? ImageEditorUiState.Ready ?: return
        val autoRun = removeAfterDownload
        removeAfterDownload = false
        when {
            ready.activeTool != ImageTool.CUTOUT -> if (autoRun) updateReady { it.copy(notice = SessionNotice.CUTOUT_MODEL_READY) }
            autoRun && ready.displayed.cutout == null -> removeBackground()
        }
    }

    /** 누끼를 끈다. 실행 취소로 되돌릴 수 있도록 마스크 에셋은 남겨 둔다. */
    fun restoreBackground() = commitImmediate { it.copy(cutout = null) }

    private fun assets(): ProjectAssetStore? = session.assets() ?: assetFallback

    private fun ensureCutoutMask(id: String?) {
        if (id == null || _cutoutMasks.value.containsKey(id)) return
        val assets = assets() ?: return
        viewModelScope.launch {
            val mask = withContext(ioDispatcher) { CutoutMasks.load(assets, id) } ?: return@launch
            _cutoutMasks.update { it + (id to mask) }
        }
    }

    fun selectAdjustment(kind: AdjustmentKind) = updateReady { it.copy(adjustKind = kind) }

    /** 선택한 보정 항목의 슬라이더 이동. 드래그의 첫 호출이 제스처를 시작한다. */
    fun changeAdjustment(display: Float) = updateGesture { ready, project ->
        val kind = ready.adjustKind
        project.copy(adjustments = project.adjustments.with(kind, kind.fromDisplay(display)))
    }

    /** 슬라이더 드래그 종료. 실행 취소 한 단계로 커밋한다. */
    fun finishGesture() = updateReady { ready ->
        if (ready.activeTool?.isDraft == true || !ready.transaction.isActive) ready else ready.copy(transaction = ready.transaction.commit())
    }

    /** 프리셋 선택은 실행 취소 한 단계다. 다시 선택하면 강도를 유지하고, 새 프리셋은 최대 강도로 시작한다. */
    fun selectFilter(presetId: String) = commitImmediate { project ->
        val intensity = when {
            presetId == FilterCatalog.ORIGINAL_ID -> 0.0
            project.filter.presetId == presetId && project.filter.intensity > 0.0 -> project.filter.intensity
            else -> 1.0
        }
        project.copy(filter = FilterSelection(presetId, intensity))
    }

    fun changeFilterIntensity(display: Float) = updateGesture { _, project ->
        if (project.filter.presetId == FilterCatalog.ORIGINAL_ID) {
            project
        } else {
            project.copy(filter = project.filter.copy(intensity = (display.toDouble() / AdjustmentKind.DISPLAY_RANGE).coerceIn(0.0, 1.0)))
        }
    }

    private fun updateGesture(transform: (ImageEditorUiState.Ready, ImageProject) -> ImageProject) = updateReady { ready ->
        if (ready.activeTool?.isDraft == true || ready.export != null) return@updateReady ready
        val transaction = ready.transaction.begin()
        val draft = checkNotNull(transaction.draft)
        ready.copy(transaction = transaction.update(transform(ready, draft)))
    }

    private fun commitImmediate(transform: (ImageProject) -> ImageProject) = updateReady { ready ->
        if (ready.transaction.isActive || ready.export != null) return@updateReady ready
        ready.copy(transaction = ready.transaction.update(transform(ready.transaction.history.current)).commit())
    }

    fun selectAspect(aspect: CropAspectRatio) {
        updateReady { it.copy(cropAspect = aspect) }
        updateDraft { ready, geometry -> GeometryOperations.withAspect(ready.source.metadata.uprightSize, geometry, aspect) }
    }

    override fun beginCropDrag(handle: CropHandle) {
        val ready = _state.value as? ImageEditorUiState.Ready ?: return
        if (ready.activeTool != ImageTool.CROP) return
        cropDragStart = handle to ready.displayed.geometry.crop
        updateReady { it.copy(draggingCrop = true) }
    }

    /** @param dx [beginCropDrag] 이후의 총 가로 이동량(정규화 G 단위). */
    override fun dragCrop(dx: Double, dy: Double) {
        val (handle, start) = cropDragStart ?: return
        updateDraft { ready, geometry ->
            val frame = frameOf(ready, geometry)
            val aspect = when (val ratio = ready.cropAspect) {
                CropAspectRatio.Free -> null
                else -> CropBoundsCalculator.resolvePixelAspect(frame, ratio)
            }
            geometry.copy(crop = CropHandleDrag.drag(frame, start, handle, dx, dy, aspect))
        }
    }

    override fun endCropDrag() {
        cropDragStart = null
        updateReady { it.copy(draggingCrop = false) }
    }

    fun rotateLeft() = updateDraft { ready, geometry -> keepAspect(ready, GeometryOperations.rotateCounterClockwise(geometry)) }

    fun rotateRight() = updateDraft { ready, geometry -> keepAspect(ready, GeometryOperations.rotateClockwise(geometry)) }

    // 자르기 중 90° 회전하면 프레임의 가로세로가 뒤바뀐다. 고른 비율 칩과 실제 프레임이 어긋나지 않도록 다시 맞춘다.
    private fun keepAspect(ready: ImageEditorUiState.Ready, geometry: GeometryEdit): GeometryEdit =
        if (ready.activeTool == ImageTool.CROP && ready.cropAspect != CropAspectRatio.Free) {
            GeometryOperations.withAspect(ready.source.metadata.uprightSize, geometry, ready.cropAspect)
        } else {
            geometry
        }

    fun flipHorizontal() = updateDraft { _, geometry -> GeometryOperations.flipHorizontal(geometry) }

    fun flipVertical() = updateDraft { _, geometry -> GeometryOperations.flipVertical(geometry) }

    fun changeStraighten(degrees: Double) {
        val ready = _state.value as? ImageEditorUiState.Ready ?: return
        val start = straightenStart ?: ready.displayed.geometry.also { straightenStart = it }
        updateDraft { current, _ -> GeometryOperations.withStraighten(current.source.metadata.uprightSize, start, degrees) }
    }

    fun finishStraighten() {
        straightenStart = null
    }

    fun undo() = updateReady { ready ->
        if (!config.allowUndo || ready.export != null) ready else ready.copy(transaction = ready.transaction.undo())
    }

    fun redo() = updateReady { ready ->
        if (!config.allowRedo || ready.export != null) ready else ready.copy(transaction = ready.transaction.redo())
    }

    override fun toggleChrome() = updateReady { ready ->
        if (ready.activeTool != null || ready.export != null) ready.copy(chromeHidden = false) else ready.copy(chromeHidden = !ready.chromeHidden)
    }

    /** 숨긴 조작부를 다시 보인다. */
    fun showChrome() = updateReady { if (it.chromeHidden) it.copy(chromeHidden = false) else it }

    override fun showOriginal(show: Boolean) = updateReady { it.copy(showingOriginal = show && it.activeTool == null) }

    fun save() {
        // 열린 도구의 초안은 저장 전에 적용한다. 사용자가 적용 버튼을 따로 누르지 않아도 보이는 그대로 저장된다.
        if ((_state.value as? ImageEditorUiState.Ready)?.activeTool?.isDraft == true) applyTool()
        val ready = _state.value as? ImageEditorUiState.Ready ?: return
        if (ready.activeTool?.isDraft == true || ready.transaction.isActive) {
            updateReady { it.copy(showApplyHint = true) }
            return
        }
        // 결과를 이미 보냈거나 export가 진행 중이면 두 번째 저장 요청은 무시한다.
        if (_result.value != null || ready.export != null || exportJob?.isActive == true) return
        if (pages.size > 1 || request.export.format == ImageFormat.PDF) return saveAll(ready)
        val snapshot = enforced(ready.transaction.history.current)
        updateReady { it.copy(export = ExportUiState.Running(ExportStageUi.PREPARING)) }
        exportJob = viewModelScope.launch {
            try {
                session.markExport(snapshot, running = true)
                val media = exportCoordinator.export(snapshot, ready.source, request.export, request.output, assets()) { stage ->
                    updateReady { current ->
                        if (current.export is ExportUiState.Running && current.export.stage != ExportStageUi.CANCELLING) {
                            current.copy(export = ExportUiState.Running(stage.toUi()))
                        } else {
                            current
                        }
                    }
                }
                finish(FrameKitResult.Success(media))
            } catch (cancelled: CancellationException) {
                updateReady { it.copy(export = null) }
                withContext(NonCancellable) { session.markExport(snapshot, running = false) }
                throw cancelled
            } catch (error: FrameKitException) {
                logFailure("export", error)
                session.markExport(snapshot, running = false)
                updateReady { it.copy(export = ExportUiState.Failed(error.code)) }
            } catch (error: Exception) {
                logFailure("export", FrameKitException(EditorErrorCode.UNKNOWN, cause = error))
                session.markExport(snapshot, running = false)
                updateReady { it.copy(export = ExportUiState.Failed(EditorErrorCode.UNKNOWN)) }
            }
        }
    }

    /**
     * 모든 쪽을 저장한다. PDF면 문서로 묶고(설정에 따라 쪽마다), 아니면 사진마다 파일을 만든다. 하나라도
     * 실패하거나 취소되면 이번에 만든 파일을 모두 지워 결과가 일부만 남지 않게 한다.
     */
    private fun saveAll(ready: ImageEditorUiState.Ready) {
        stashCurrentPage()
        updateReady { it.copy(export = ExportUiState.Running(ExportStageUi.PREPARING)) }
        exportJob = viewModelScope.launch {
            val produced = mutableListOf<EditedMedia>()
            try {
                val jobs = withContext(ioDispatcher) {
                    pages.map { page ->
                        val (sourceId, info) = openPage(page)
                        val project = page.transaction?.history?.current
                            ?: ImageProject(
                                ProjectId(newId()),
                                sourceId,
                                watermark = sharedWatermark?.watermark ?: config.watermark?.initialWatermark,
                                grainSeed = Random.nextLong(),
                            )
                        PdfPage(enforced(project), info, recorders[page.id]?.assets() ?: assetFallback)
                    }
                }
                val total = jobs.size
                fun progress(index: Int, stage: ImageExportStage) = updateReady { current ->
                    if (current.export is ExportUiState.Running && current.export.stage != ExportStageUi.CANCELLING) {
                        current.copy(export = ExportUiState.Running(stage.toUi(), (index + if (stage == ImageExportStage.FINALIZING) 1f else 0.5f) / total))
                    } else {
                        current
                    }
                }
                if (request.export.format == ImageFormat.PDF) {
                    produced += exportCoordinator.exportPdf(jobs, request.export, request.output) { index, _, stage -> progress(index, stage) }
                } else {
                    jobs.forEachIndexed { index, job ->
                        produced += exportCoordinator.export(job.project, job.source, request.export, request.output, job.assets) { stage -> progress(index, stage) }
                    }
                }
                finish(FrameKitResult.Success(produced.first(), produced.toList()))
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) { exportCoordinator.deleteOutputs(produced) }
                updateReady { it.copy(export = null) }
                throw cancelled
            } catch (error: FrameKitException) {
                logFailure("export", error)
                exportCoordinator.deleteOutputs(produced)
                updateReady { it.copy(export = ExportUiState.Failed(error.code)) }
            } catch (error: Exception) {
                logFailure("export", FrameKitException(EditorErrorCode.UNKNOWN, cause = error))
                exportCoordinator.deleteOutputs(produced)
                updateReady { it.copy(export = ExportUiState.Failed(EditorErrorCode.UNKNOWN)) }
            }
        }
    }

    fun cancelExport() {
        val job = exportJob ?: return
        if (!job.isActive) return
        updateReady { it.copy(export = ExportUiState.Running(ExportStageUi.CANCELLING)) }
        job.cancel()
    }

    fun dismissExportError() = updateReady { it.copy(export = null) }

    fun dismissApplyHint() = updateReady { it.copy(showApplyHint = false) }

    /** 닫기 버튼 또는 시스템 뒤로 가기. */
    fun requestClose() {
        when (val current = _state.value) {
            is ImageEditorUiState.Ready -> when {
                current.export is ExportUiState.Running -> cancelExport()
                anyPageDirty(current) -> updateReady { it.copy(showDiscardDialog = true) }
                else -> finish(FrameKitResult.Cancelled)
            }
            is ImageEditorUiState.LoadFailed -> finish(FrameKitResult.Failure(EditorError(current.code)))
            else -> finish(FrameKitResult.Cancelled)
        }
    }

    /** 시스템 뒤로 가기. 열린 도구를 먼저 닫고, 그다음에는 [requestClose]처럼 동작한다. */
    fun onBack() {
        val ready = _state.value as? ImageEditorUiState.Ready
        when {
            ready == null || ready.activeTool == null || ready.export != null -> requestClose()
            // 바꾼 내용이 있으면 바로 버리지 않고 적용할지 묻는다.
            ready.activeTool.isDraft && ready.hasDraftChanges -> updateReady { it.copy(showDraftDialog = true) }
            ready.activeTool.isDraft -> cancelTool()
            // 바로 기록되는 도구도 연 뒤에 바뀐 게 있으면 자르기처럼 적용할지 묻는다.
            toolSessionChanged(ready) -> updateReady { it.copy(showDraftDialog = true) }
            else -> closeTool()
        }
    }

    fun applyDraftFromDialog() {
        updateReady { it.copy(showDraftDialog = false) }
        if ((_state.value as? ImageEditorUiState.Ready)?.activeTool?.isDraft == true) applyTool() else closeTool()
    }

    fun discardDraftFromDialog() {
        updateReady { it.copy(showDraftDialog = false) }
        if ((_state.value as? ImageEditorUiState.Ready)?.activeTool?.isDraft == true) cancelTool() else revertTool()
    }

    /** 바로 기록되는 도구를 연 뒤 바뀐 것이 있으면 `true`. 적용 버튼을 보일지 정할 때도 쓴다. */
    internal fun toolSessionChanged(ready: ImageEditorUiState.Ready): Boolean {
        val entry = toolEntry ?: return false
        return ready.activeTool?.isDraft == false && !ready.transaction.history.current.sameContentAs(entry)
    }

    /**
     * 바로 기록되는 도구를 닫으며, 도구를 열기 전 상태로 되돌린다(취소). 그사이 기록된 단계는 실행 취소로 되돌리므로
     * 다시 실행으로 되살릴 수 있다.
     */
    fun revertTool() = updateReady { ready ->
        val entry = toolEntry
        if (ready.activeTool?.isDraft != false || ready.transaction.isActive) return@updateReady ready
        var transaction = ready.transaction
        if (entry != null) {
            while (transaction.history.canUndo && !transaction.history.current.sameContentAs(entry)) transaction = transaction.undo()
            // 기록이 이어지지 않아(복원 등) 되돌아가지 못했으면 열 때의 상태를 새 단계로 넣는다.
            if (!transaction.history.current.sameContentAs(entry)) transaction = transaction.update(entry).commit()
        }
        toolEntry = null
        ready.copy(activeTool = null, transaction = transaction)
    }

    fun dismissDraftDialog() = updateReady { it.copy(showDraftDialog = false) }

    fun confirmDiscard() = finish(FrameKitResult.Cancelled)

    fun dismissDiscard() = updateReady { it.copy(showDiscardDialog = false) }

    private fun finish(result: FrameKitResult) {
        if (_result.value != null) return
        pendingRestore?.let(session::discard)
        recorders.values.forEach(ImageSessionRecorder::end)
        // 카메라로 찍은 임시 원본은 결과를 만든 뒤에는 필요 없다.
        CaptureFiles.delete(savedState.get<String>(KEY_CAPTURE_PATH))
        // 문서 보정으로 만든 중간 파일도 결과를 만들었거나 버렸으면 필요 없다.
        val documentFiles = pages.mapNotNull { (it.input as? EditorInput.FileSource)?.absolutePath }.map(::File).filter { it.parentFile == documentDirectory }
        if (documentFiles.isNotEmpty()) viewModelScope.launch(NonCancellable + ioDispatcher) { documentFiles.forEach(File::delete) }
        _result.value = result
    }

    private fun updateReady(transform: (ImageEditorUiState.Ready) -> ImageEditorUiState.Ready) {
        var committedChange: ImageProject? = null
        _state.update { current ->
            if (current !is ImageEditorUiState.Ready) return@update current
            val next = transform(current)
            val before = current.transaction.history.current
            val after = next.transaction.history.current
            committedChange = after.takeIf { it !== before }
            next
        }
        committedChange?.let(session::saveCommitted)
    }

    private fun updateDraft(transform: (ImageEditorUiState.Ready, GeometryEdit) -> GeometryEdit) = updateReady { ready ->
        val draft = ready.transaction.draft ?: return@updateReady ready
        ready.copy(transaction = ready.transaction.update(draft.copy(geometry = transform(ready, draft.geometry))))
    }

    private fun newId(): String = UUID.randomUUID().toString()

    private fun frameOf(ready: ImageEditorUiState.Ready, geometry: GeometryEdit) =
        GeometryFrame(ready.source.metadata.uprightSize, geometry)

    private fun logFailure(stage: String, error: FrameKitException) {
        // URI·파일명이 남지 않도록 예외 메시지 대신 코드와 예외 종류만 기록한다.
        Log.w(TAG, "$stage failed: code=${error.code} cause=${error.cause?.javaClass?.simpleName}")
    }

    override fun onCleared() {
        if (_result.value == null) recorders.values.forEach(ImageSessionRecorder::detach)
        colorRenderer.release()
    }

    private fun ImageExportStage.toUi(): ExportStageUi = when (this) {
        ImageExportStage.PREPARING -> ExportStageUi.PREPARING
        ImageExportStage.RENDERING -> ExportStageUi.RENDERING
        ImageExportStage.ENCODING -> ExportStageUi.ENCODING
        ImageExportStage.FINALIZING -> ExportStageUi.FINALIZING
    }

    private companion object {
        const val TAG = "FrameKit"
        const val KEY_PICKED_URIS = "framekit_picked_uris"
        const val KEY_CAPTURE_PATH = "framekit_capture_path"
        const val KEY_PAGES = "framekit_pages"
        const val KEY_PAGE_IDS = "framekit_page_ids"
        const val KEY_PAGE_INDEX = "framekit_page_index"
        const val KEY_PAGES_CHANGED = "framekit_pages_changed"
        const val KEY_PAGE_ORIGINAL_IDS = "framekit_page_original_ids"
        const val KEY_PAGE_ORIGINALS = "framekit_page_originals"
        const val DEFAULT_MEMORY_BUDGET = 256L * 1024 * 1024
        const val MIN_DOCUMENT_CHARACTERS = 20
        const val DOCUMENT_STALE_MILLIS = 7L * 24 * 60 * 60 * 1000
        const val MAIN_PAGE_ID = "main"
        const val PAGE_THUMBNAIL_PX = 200

        // 화면에 띄우지 않은 쪽에서 문서를 찾을 때 읽는 긴 변(px). 감지는 256px로 줄여 하므로 이 정도면 충분하다.
        const val DETECT_LONG_EDGE = 512
        const val PAGE_THUMBNAIL_DEBOUNCE_MILLIS = 150L
        const val SNAPSHOT_DEBOUNCE_MILLIS = 300L
        const val MIN_MASK_SIZE = 0.01

        // 막 그리기 시작한 도형의 크기. 끌기 전에도 그릴 수 있게 0이 아닌 작은 값으로 둔다.
        const val MIN_SHAPE_RATIO = 0.001
    }
}

/** 지금 쪽 미리보기를 편집 내용대로 다시 그릴 때 필요한 것. */
private data class PageThumbnailRequest(
    val pageId: String,
    val source: DecodedImage,
    val project: ImageProject,
    val metadata: SourceMetadata,
    val cutoutMask: Bitmap?,
)

/** 여러 장 편집의 한 쪽. 아직 열지 않은 쪽은 [transaction]이 `null`이다. */
private class Page(val id: String, val input: EditorInput, val original: EditorInput? = null) {
    var sourceId: SourceId? = null
    var info: ImageSourceInfo? = null
    var transaction: HistoryTransaction<ImageProject>? = null

    /** 문서 보정 뒤 처음 열 때, 이전 원본의 보정·필터를 새 프로젝트에 옮겨 담는다. */
    var carry: ((ImageProject) -> ImageProject)? = null

    /** 문서 판별을 마쳤는지와 그 결과(문서가 아니면 `null`). 다시 열 때 또 계산하지 않는다. */
    var documentChecked: Boolean = false
    var documentSuggestion: DocumentQuad? = null

    /** 감지한 문서의 실제 테두리(휜 변 포함). 모서리가 이와 같을 때만 펼 때 쓴다. */
    var documentOutline: DocumentOutline? = null
    var documentSuggestionDismissed: Boolean = false

    /** 문서 보정 직후 글자를 인식해야 하면 `true`. 인식한 글자는 [documentText]. */
    var recognizeAfterLoad: Boolean = false
    var documentText: String? = null
}

// 위치가 바뀌는 편집(자르기·텍스트·스티커·그리기·가리기·배경 제거)이 있으면 `true`. 문서를 펴면 이것들은 초기화된다.
private val ImageProject.hasPlacedEdits: Boolean
    get() = geometry != GeometryEdit() || overlays.isNotEmpty() || drawing.isNotEmpty() || privacyMasks.isNotEmpty() || cutout != null
