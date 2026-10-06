package com.naury.framekit.ui.image.editor

import android.content.ContentResolver
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
import com.naury.framekit.android.session.SessionRecord
import com.naury.framekit.core.history.EditHistory
import com.naury.framekit.core.model.SourceId
import com.naury.framekit.core.validation.ImageProjectValidator
import com.naury.framekit.image.decode.DecodedImage
import com.naury.framekit.image.decode.ImageSourceInfo
import kotlinx.coroutines.NonCancellable
import com.naury.framekit.core.geometry.CropAspectRatio
import com.naury.framekit.core.geometry.CropBoundsCalculator
import com.naury.framekit.core.geometry.CropHandle
import com.naury.framekit.core.geometry.CropHandleDrag
import com.naury.framekit.core.geometry.GeometryEdit
import com.naury.framekit.core.geometry.GeometryFrame
import com.naury.framekit.core.geometry.GeometryOperations
import com.naury.framekit.core.geometry.RectN
import com.naury.framekit.core.history.HistoryTransaction
import com.naury.framekit.core.model.ImageProject
import com.naury.framekit.core.model.ProjectId
import com.naury.framekit.image.decode.BitmapDecoder
import com.naury.framekit.image.decode.ImageMetadataReader
import com.naury.framekit.image.decode.SampleSize
import com.naury.framekit.image.export.ImageExportCoordinator
import com.naury.framekit.image.export.ImageExportStage
import com.naury.framekit.ui.component.ExportStageUi
import com.naury.framekit.ui.image.contract.ImageEditorRequest
import com.naury.framekit.ui.image.contract.ImageTool
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * State holder of one image editing session.
 *
 * Every edit goes through [HistoryTransaction]: a tool session is `begin → update* → apply/cancel`,
 * so one crop or rotate session becomes exactly one undo step. Export always uses the committed
 * snapshot, never the draft.
 */
internal class ImageEditorViewModel(
    private val request: ImageEditorRequest,
    private val savedState: SavedStateHandle,
    private val registry: SessionSourceRegistry,
    private val exportCoordinator: ImageExportCoordinator,
    private val previewLongEdge: Int,
    private val ioDispatcher: CoroutineDispatcher,
    sessionStore: EditorSessionStore,
    snapshotDebounceMillis: Long = SNAPSHOT_DEBOUNCE_MILLIS,
    private val contentResolver: ContentResolver? = null,
) : ViewModel() {

    private val _state = MutableStateFlow<ImageEditorUiState>(ImageEditorUiState.Loading)
    val state: StateFlow<ImageEditorUiState> = _state.asStateFlow()

    private val _result = MutableStateFlow<FrameKitResult?>(null)

    /** Final result; the Activity finishes as soon as this is not `null`. */
    val result: StateFlow<FrameKitResult?> = _result.asStateFlow()

    private var exportJob: Job? = null
    private var cropDragStart: Pair<CropHandle, RectN>? = null
    private var straightenStart: GeometryEdit? = null
    private val session = ImageSessionRecorder(sessionStore, savedState, viewModelScope, ioDispatcher, snapshotDebounceMillis)

    // 권한을 잃은 원본을 다시 고를 때까지 이전 세션을 들고 있다가, 같은 이미지인지 확인한 뒤에만 복원한다.
    private var pendingRestore: SessionRecord? = null

    val config get() = request.config

    init {
        viewModelScope.launch(ioDispatcher) { exportCoordinator.deleteStalePartials() }
        if (savedState.contains(ImageSessionRecorder.KEY_SESSION_ID)) restore() else start()
    }

    private fun start() {
        val pickedUri = savedState.get<Uri>(KEY_PICKED_URI)
        when {
            request.input !is EditorInput.Pick -> load(request.input)
            pickedUri != null -> load(EditorInput.UriSource(pickedUri))
            else -> _state.value = ImageEditorUiState.AwaitingPick
        }
    }

    private fun restore() {
        viewModelScope.launch {
            val previous = withContext(ioDispatcher) { session.loadPrevious() }
            if (previous == null) {
                start()
            } else {
                pendingRestore = previous
                load(with(ImageSessionRecorder) { previous.source.toInput() })
            }
        }
    }

    /** Called with the picker result. `null` means the user dismissed the picker. */
    fun onPicked(uri: Uri?) {
        if (uri == null) {
            if (_state.value is ImageEditorUiState.AwaitingPick) finish(FrameKitResult.Cancelled)
            return
        }
        savedState[KEY_PICKED_URI] = uri
        load(EditorInput.UriSource(uri))
    }

    /** Returns to the picker after a source failed to open. */
    fun chooseAnother() {
        savedState.remove<Uri>(KEY_PICKED_URI)
        _state.value = ImageEditorUiState.AwaitingPick
    }

    private fun load(input: EditorInput) {
        _state.value = ImageEditorUiState.Loading
        viewModelScope.launch {
            try {
                val ready = withContext(ioDispatcher) {
                    val sourceId = registry.register(input)
                    val info = ImageMetadataReader(registry).read(sourceId)
                    val sample = SampleSize.forMinimumLongEdge(info.encodedSize, previewLongEdge)
                    val preview = BitmapDecoder(registry, contentResolver).decode(info, sample)
                    val restored = session.attach(pendingRestore, input, info)
                    pendingRestore = null
                    restoredState(restored, sourceId, info, preview)
                }
                _state.value = ready
            } catch (error: FrameKitException) {
                logFailure("load", error)
                _state.value = ImageEditorUiState.LoadFailed(error.code, canChooseAnother = request.input is EditorInput.Pick)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                logFailure("load", FrameKitException(EditorErrorCode.UNKNOWN, cause = error))
                _state.value = ImageEditorUiState.LoadFailed(EditorErrorCode.UNKNOWN, canChooseAnother = request.input is EditorInput.Pick)
            }
        }
    }

    private fun restoredState(
        restored: SessionRecord?,
        sourceId: SourceId,
        info: ImageSourceInfo,
        preview: DecodedImage,
    ): ImageEditorUiState.Ready {
        val projectId = ProjectId(restored?.snapshot?.projectId ?: UUID.randomUUID().toString())
        val baseline = ImageProject(projectId, sourceId)
        // 저장된 값이 지금 원본에 맞지 않으면(예: crop 범위 밖) 복원하지 않고 처음부터 연다.
        val committed = restored?.snapshot?.toProject(sourceId)
            ?.takeIf { ImageProjectValidator.validate(it, info.metadata).isValid }
        val transaction = if (committed != null) {
            HistoryTransaction(EditHistory.restore(baseline, committed))
        } else {
            HistoryTransaction.start(baseline)
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

    fun selectTool(tool: ImageTool) = updateReady { ready ->
        if (ready.activeTool != null || ready.export != null || tool !in config.enabledTools) return@updateReady ready
        ready.copy(activeTool = tool, cropAspect = CropAspectRatio.Free, transaction = ready.transaction.begin())
    }

    fun applyTool() = updateReady { ready ->
        if (ready.activeTool == null) return@updateReady ready
        ready.copy(activeTool = null, transaction = ready.transaction.commit())
    }

    fun cancelTool() = updateReady { ready ->
        if (ready.activeTool == null) return@updateReady ready
        ready.copy(activeTool = null, transaction = ready.transaction.cancel())
    }

    fun resetTool() {
        updateReady { it.copy(cropAspect = CropAspectRatio.Free) }
        updateDraft { ready, geometry ->
            when (ready.activeTool) {
                ImageTool.CROP -> geometry.copy(crop = CropBoundsCalculator.maxCrop(frameOf(ready, geometry), CropAspectRatio.Free))
                ImageTool.ROTATE -> GeometryEdit()
                null -> geometry
            }
        }
    }

    fun selectAspect(aspect: CropAspectRatio) {
        updateReady { it.copy(cropAspect = aspect) }
        updateDraft { ready, geometry -> GeometryOperations.withAspect(ready.source.metadata.uprightSize, geometry, aspect) }
    }

    fun beginCropDrag(handle: CropHandle) {
        val ready = _state.value as? ImageEditorUiState.Ready ?: return
        if (ready.activeTool != ImageTool.CROP) return
        cropDragStart = handle to ready.displayed.geometry.crop
        updateReady { it.copy(draggingCrop = true) }
    }

    /** @param dx total horizontal movement since [beginCropDrag], in normalized G units. */
    fun dragCrop(dx: Double, dy: Double) {
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

    fun endCropDrag() {
        cropDragStart = null
        updateReady { it.copy(draggingCrop = false) }
    }

    fun rotateLeft() = updateDraft { _, geometry -> GeometryOperations.rotateCounterClockwise(geometry) }

    fun rotateRight() = updateDraft { _, geometry -> GeometryOperations.rotateClockwise(geometry) }

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

    fun showOriginal(show: Boolean) = updateReady { it.copy(showingOriginal = show && it.activeTool == null) }

    fun save() {
        val ready = _state.value as? ImageEditorUiState.Ready ?: return
        if (ready.activeTool != null) {
            updateReady { it.copy(showApplyHint = true) }
            return
        }
        // 결과를 이미 보냈거나 export가 진행 중이면 두 번째 저장 요청은 무시한다.
        if (_result.value != null || ready.export != null || exportJob?.isActive == true) return
        val snapshot = ready.transaction.history.current
        updateReady { it.copy(export = ExportUiState.Running(ExportStageUi.PREPARING)) }
        exportJob = viewModelScope.launch {
            try {
                session.markExport(snapshot, running = true)
                val media = exportCoordinator.export(snapshot, ready.source, request.export, request.output) { stage ->
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

    fun cancelExport() {
        val job = exportJob ?: return
        if (!job.isActive) return
        updateReady { it.copy(export = ExportUiState.Running(ExportStageUi.CANCELLING)) }
        job.cancel()
    }

    fun dismissExportError() = updateReady { it.copy(export = null) }

    fun dismissApplyHint() = updateReady { it.copy(showApplyHint = false) }

    /** Close button or system back. */
    fun requestClose() {
        when (val current = _state.value) {
            is ImageEditorUiState.Ready -> when {
                current.export is ExportUiState.Running -> cancelExport()
                current.isDirty || current.hasDraftChanges -> updateReady { it.copy(showDiscardDialog = true) }
                else -> finish(FrameKitResult.Cancelled)
            }
            is ImageEditorUiState.LoadFailed -> finish(FrameKitResult.Failure(EditorError(current.code)))
            else -> finish(FrameKitResult.Cancelled)
        }
    }

    /** System back: closes an open tool first, then behaves like [requestClose]. */
    fun onBack() {
        val ready = _state.value as? ImageEditorUiState.Ready
        if (ready?.activeTool != null && ready.export == null) cancelTool() else requestClose()
    }

    fun confirmDiscard() = finish(FrameKitResult.Cancelled)

    fun dismissDiscard() = updateReady { it.copy(showDiscardDialog = false) }

    private fun finish(result: FrameKitResult) {
        if (_result.value != null) return
        pendingRestore?.let(session::discard)
        session.end()
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

    private fun frameOf(ready: ImageEditorUiState.Ready, geometry: GeometryEdit) =
        GeometryFrame(ready.source.metadata.uprightSize, geometry)

    private fun logFailure(stage: String, error: FrameKitException) {
        // URI·파일명이 남지 않도록 예외 메시지 대신 코드와 예외 종류만 기록한다.
        Log.w(TAG, "$stage failed: code=${error.code} cause=${error.cause?.javaClass?.simpleName}")
    }

    override fun onCleared() {
        if (_result.value == null) session.detach()
        (_state.value as? ImageEditorUiState.Ready)?.preview?.recycle()
    }

    private fun ImageExportStage.toUi(): ExportStageUi = when (this) {
        ImageExportStage.PREPARING -> ExportStageUi.PREPARING
        ImageExportStage.RENDERING -> ExportStageUi.RENDERING
        ImageExportStage.ENCODING -> ExportStageUi.ENCODING
        ImageExportStage.FINALIZING -> ExportStageUi.FINALIZING
    }

    private companion object {
        const val TAG = "FrameKit"
        const val KEY_PICKED_URI = "framekit_picked_uri"
        const val SNAPSHOT_DEBOUNCE_MILLIS = 300L
    }
}
