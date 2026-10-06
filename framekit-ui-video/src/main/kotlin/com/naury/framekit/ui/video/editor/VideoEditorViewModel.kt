package com.naury.framekit.ui.video.editor

import com.naury.framekit.core.validation.VideoProjectValidator
import com.naury.framekit.core.history.EditHistory
import com.naury.framekit.android.session.SessionRecord
import com.naury.framekit.android.session.EditorSessionStore
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.naury.framekit.android.input.EditorInput
import com.naury.framekit.android.result.EditedMedia
import com.naury.framekit.android.result.EditorError
import com.naury.framekit.android.result.EditorErrorCode
import com.naury.framekit.android.result.FrameKitException
import com.naury.framekit.android.result.FrameKitResult
import com.naury.framekit.android.source.SessionSourceRegistry
import com.naury.framekit.android.source.SourceLocation
import com.naury.framekit.core.effect.AdjustmentKind
import com.naury.framekit.core.effect.Adjustments
import com.naury.framekit.core.effect.ColorEffectSpec
import com.naury.framekit.core.effect.FilterCatalog
import com.naury.framekit.core.effect.FilterSelection
import com.naury.framekit.core.geometry.CropAspectRatio
import com.naury.framekit.core.geometry.CropBoundsCalculator
import com.naury.framekit.core.geometry.CropHandle
import com.naury.framekit.core.geometry.CropHandleDrag
import com.naury.framekit.core.geometry.GeometryEdit
import com.naury.framekit.core.geometry.GeometryFrame
import com.naury.framekit.core.geometry.GeometryOperations
import com.naury.framekit.core.geometry.PointN
import com.naury.framekit.core.geometry.RectN
import com.naury.framekit.core.history.HistoryTransaction
import com.naury.framekit.core.model.ProjectId
import com.naury.framekit.core.model.SourceId
import com.naury.framekit.core.overlay.MaskShape
import com.naury.framekit.core.overlay.PrivacyMask
import com.naury.framekit.core.video.TimeRangeUs
import com.naury.framekit.core.video.TimedPrivacyMask
import com.naury.framekit.core.video.Timeline
import com.naury.framekit.core.video.TimelineTimeMapper
import com.naury.framekit.core.video.VideoClip
import com.naury.framekit.core.video.VideoProject
import com.naury.framekit.image.effect.ColorEffectRenderer
import com.naury.framekit.image.effect.CpuColorEffectRenderer
import com.naury.framekit.ui.component.ExportStageUi
import com.naury.framekit.ui.tool.PrivacySettings
import com.naury.framekit.ui.tool.PrivacyShape
import com.naury.framekit.ui.video.contract.VideoEditorRequest
import com.naury.framekit.ui.video.contract.VideoTool
import com.naury.framekit.video.VideoPlanFactory
import com.naury.framekit.video.export.VideoExportProgress
import com.naury.framekit.video.preview.VideoPlaybackState
import com.naury.framekit.video.preview.VideoPreviewEngine
import com.naury.framekit.video.source.VideoSourceInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.random.Random

/** Writes the edited video; [VideoExportCoordinator][com.naury.framekit.video.export.VideoExportCoordinator] in production. */
internal fun interface VideoExporter {
    suspend fun export(
        project: VideoProject,
        sources: Map<SourceId, VideoSourceInfo>,
        locations: Map<SourceId, SourceLocation>,
        onProgress: (VideoExportProgress) -> Unit,
    ): EditedMedia
}

/** Timeline frames; [VideoThumbnailLoader][com.naury.framekit.video.thumbnail.VideoThumbnailLoader] in production. */
internal fun interface VideoFrameSource {
    suspend fun frame(location: SourceLocation, sourceKey: String, timeUs: Long, heightPx: Int): Bitmap?
}

/**
 * State holder of one video editing session.
 *
 * Edits follow the photo editor: draft tools (trim, crop, rotate) are one undo step per session, and
 * slider gestures are one step per drag. The preview player shows the displayed project, rebuilt
 * after a short debounce so dragging a slider does not prepare the player on every frame. Export
 * always uses the committed snapshot.
 */
internal class VideoEditorViewModel(
    private val request: VideoEditorRequest,
    private val savedState: SavedStateHandle,
    private val registry: SessionSourceRegistry,
    private val readSource: (SourceId) -> VideoSourceInfo,
    private val exporter: VideoExporter,
    previewEngineFactory: (CoroutineScope) -> VideoPreviewEngine,
    private val frames: VideoFrameSource,
    private val ioDispatcher: CoroutineDispatcher,
    private val colorRenderer: ColorEffectRenderer = CpuColorEffectRenderer,
    private val previewDebounceMillis: Long = PREVIEW_DEBOUNCE_MILLIS,
    private val closeables: List<java.io.Closeable> = emptyList(),
    sessionStore: EditorSessionStore? = null,
    snapshotDebounceMillis: Long = SNAPSHOT_DEBOUNCE_MILLIS,
) : ViewModel(), VideoCanvasActions {

    private val _state = MutableStateFlow<VideoEditorUiState>(VideoEditorUiState.Loading)
    val state: StateFlow<VideoEditorUiState> = _state.asStateFlow()

    private val _result = MutableStateFlow<FrameKitResult?>(null)

    /** Final result; the Activity finishes as soon as this is not `null`. */
    val result: StateFlow<FrameKitResult?> = _result.asStateFlow()

    private val engine = previewEngineFactory(viewModelScope)

    /** Playback position and status of the preview, in output time. */
    val playback: StateFlow<VideoPlaybackState> = engine.state

    private val _previewSize = MutableStateFlow<Pair<Int, Int>?>(null)

    /** Size of the frame the preview shows: the output canvas, or the uncropped frame while cropping. */
    val previewSize: StateFlow<Pair<Int, Int>?> = _previewSize.asStateFlow()

    private val _filterThumbnails = MutableStateFlow<Map<String, Bitmap>>(emptyMap())
    val filterThumbnails: StateFlow<Map<String, Bitmap>> = _filterThumbnails.asStateFlow()

    private var exportJob: Job? = null
    private var thumbnailJob: Job? = null
    private var cropDragStart: Pair<CropHandle, RectN>? = null
    private var straightenStart: GeometryEdit? = null
    private var trimEdge: TrimEdge? = null
    private var maskAnchor: PointN? = null
    private var planRevision = 0L
    private var lastPreviewProject: VideoProject? = null
    private var pendingSeekUs: Long? = null
    private val previewRequests = MutableStateFlow<VideoProject?>(null)
    private val session = sessionStore?.let { VideoSessionRecorder(it, savedState, viewModelScope, ioDispatcher, snapshotDebounceMillis) }

    // 권한을 잃은 원본을 다시 고를 때까지 이전 세션을 들고 있다가, 같은 영상인지 확인한 뒤에만 복원한다.
    private var pendingRestore: SessionRecord? = null

    val config get() = request.config

    init {
        if (savedState.contains(VideoSessionRecorder.KEY_SESSION_ID) && session != null) restore() else start()
        viewModelScope.launch {
            state.collect { current -> if (current is VideoEditorUiState.Ready) requestPreview(current) }
        }
        viewModelScope.launch {
            previewRequests.filterNotNull().collectLatest { project ->
                delay(previewDebounceMillis)
                showPreview(project)
            }
        }
    }

    private fun start() {
        val pickedUri = savedState.get<Uri>(KEY_PICKED_URI)
        when {
            request.input !is EditorInput.Pick -> load(request.input)
            pickedUri != null -> load(EditorInput.UriSource(pickedUri))
            else -> _state.value = VideoEditorUiState.AwaitingPick
        }
    }

    private fun restore() {
        viewModelScope.launch {
            val previous = withContext(ioDispatcher) { session?.loadPrevious() }
            if (previous == null) {
                start()
            } else {
                pendingRestore = previous
                load(with(VideoSessionRecorder) { previous.source.toInput() })
            }
        }
    }

    /** Called with the picker result. `null` means the user dismissed the picker. */
    fun onPicked(uri: Uri?) {
        if (uri == null) {
            if (_state.value is VideoEditorUiState.AwaitingPick) finish(FrameKitResult.Cancelled)
            return
        }
        savedState[KEY_PICKED_URI] = uri
        load(EditorInput.UriSource(uri))
    }

    /** Returns to the picker after a source failed to open. */
    fun chooseAnother() {
        savedState.remove<Uri>(KEY_PICKED_URI)
        _state.value = VideoEditorUiState.AwaitingPick
    }

    private fun load(input: EditorInput) {
        _state.value = VideoEditorUiState.Loading
        viewModelScope.launch {
            try {
                _state.value = withContext(ioDispatcher) {
                    val sourceId = registry.register(input)
                    val info = readSource(sourceId)
                    val location = registry.location(sourceId)
                        ?: throw FrameKitException(EditorErrorCode.SOURCE_UNAVAILABLE, "Source has no location")
                    val duration = info.metadata.durationUs ?: 0L
                    if (duration < config.minClipDurationUs) {
                        throw FrameKitException(EditorErrorCode.INVALID_SOURCE, "Video is shorter than the minimum clip")
                    }
                    val previous = session?.attach(pendingRestore, input, sourceId, info)
                    pendingRestore = null
                    restoredState(previous, sourceId, info, location, duration)
                }
            } catch (error: FrameKitException) {
                logFailure("load", error)
                _state.value = VideoEditorUiState.LoadFailed(error.code, canChooseAnother = request.input is EditorInput.Pick)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                logFailure("load", FrameKitException(EditorErrorCode.UNKNOWN, cause = error))
                _state.value = VideoEditorUiState.LoadFailed(EditorErrorCode.UNKNOWN, canChooseAnother = request.input is EditorInput.Pick)
            }
        }
    }

    private fun restoredState(
        previous: SessionRecord?,
        sourceId: SourceId,
        info: VideoSourceInfo,
        location: SourceLocation,
        durationUs: Long,
    ): VideoEditorUiState.Ready {
        val restored = session?.restoredProject(previous)?.takeIf {
            VideoProjectValidator.validate(it, mapOf(sourceId to info.metadata), config.minClipDurationUs).isValid &&
                TimelineTimeMapper.durationUs(it.timeline) <= config.maxTimelineDurationUs
        }
        // 허용 길이보다 긴 영상은 앞부분만 남긴 채로 열고, 사용자가 구간을 옮겨 고른다.
        val clip = VideoClip(restored?.timeline?.videoClips?.first()?.id ?: newId(), sourceId, TimeRangeUs(0, min(durationUs, config.maxTimelineDurationUs)))
        val baseline = VideoProject(
            restored?.id ?: ProjectId(newId()),
            Timeline(listOf(clip)),
            grainSeed = restored?.grainSeed ?: Random.nextLong(),
        )
        val transaction = if (restored != null) HistoryTransaction(EditHistory.restore(baseline, restored)) else HistoryTransaction.start(baseline)
        val notice = when {
            previous?.exportWasInterrupted == true -> VideoNotice.EXPORT_INTERRUPTED
            restored != null && !restored.sameContentAs(baseline) -> VideoNotice.RESTORED
            else -> null
        }
        if (previous?.exportWasInterrupted == true) session?.saveCommitted(transaction.history.current)
        return VideoEditorUiState.Ready(info, location, transaction, notice = notice)
    }

    // ---- 미리보기 ----

    private fun requestPreview(ready: VideoEditorUiState.Ready) {
        val project = previewProject(ready)
        if (lastPreviewProject?.sameContentAs(project) == true) return
        lastPreviewProject = project
        previewRequests.value = project
    }

    private fun previewProject(ready: VideoEditorUiState.Ready): VideoProject {
        if (ready.activeTool?.isGeometry != true) return ready.displayed
        // 자르기·회전 중에는 잘리기 전 전체 프레임을 보여 주고 그 위에 crop 프레임을 그린다.
        return ready.displayed.updateClip { it.copy(effects = it.effects.copy(geometry = it.effects.geometry.copy(crop = RectN.Full))) }
    }

    private fun showPreview(project: VideoProject) {
        val ready = _state.value as? VideoEditorUiState.Ready ?: return
        val plan = try {
            VideoPlanFactory.create(
                project,
                mapOf(ready.clip.source to ready.source),
                mapOf(ready.clip.source to ready.location),
                PREVIEW_SHORT_SIDE,
            ).copy(projectRevision = ++planRevision)
        } catch (error: FrameKitException) {
            logFailure("preview", error)
            return
        }
        val duration = TimelineTimeMapper.durationUs(project.timeline)
        val position = (pendingSeekUs ?: engine.state.value.positionUs).coerceIn(0, max(0, duration - FRAME_US))
        pendingSeekUs = null
        _previewSize.value = plan.canvasSize.width to plan.canvasSize.height
        engine.setPlan(plan, position)
    }

    fun togglePlayback() {
        if (engine.state.value.isPlaying) engine.pause() else engine.play()
    }

    /** Seeks to an output time; [scrubbing] favours speed while the user drags. */
    fun seekTo(positionUs: Long, scrubbing: Boolean = false) {
        val ready = _state.value as? VideoEditorUiState.Ready ?: return
        engine.setScrubbing(scrubbing)
        engine.seekTo(positionUs.coerceIn(0, max(0, ready.durationUs - FRAME_US)))
    }

    fun finishScrub() = engine.setScrubbing(false)

    fun attachSurface(holder: android.view.SurfaceHolder) = engine.attachSurface(holder)

    fun detachSurface(holder: android.view.SurfaceHolder) = engine.detachSurface(holder)

    /** Called when the Activity stops; playback never continues in the background. */
    fun onStop() = engine.pause()

    /** A timeline frame of the edited source, `null` when it cannot be read. */
    suspend fun timelineFrame(sourceTimeUs: Long, heightPx: Int): Bitmap? {
        val ready = _state.value as? VideoEditorUiState.Ready ?: return null
        return frames.frame(ready.location, ready.clip.source.value, sourceTimeUs, heightPx)
    }

    // ---- 도구 ----

    fun dismissNotice() = updateReady { it.copy(notice = null) }

    fun selectTool(tool: VideoTool) = updateReady { ready ->
        if (ready.activeTool != null || ready.export != null || ready.transaction.isActive || tool !in config.enabledTools) {
            return@updateReady ready
        }
        if (tool == VideoTool.FILTER) ensureFilterThumbnails(ready)
        if (tool.isDraft) engine.pause()
        when {
            tool.isDraft -> ready.copy(activeTool = tool, cropAspect = CropAspectRatio.Free, transaction = ready.transaction.begin())
            else -> ready.copy(activeTool = tool, selectedMaskId = null)
        }
    }

    fun applyTool() = updateReady { ready ->
        if (ready.activeTool?.isDraft != true) return@updateReady ready
        val draft = ready.transaction.draft ?: return@updateReady ready
        ready.copy(activeTool = null, transaction = ready.transaction.update(clampMasks(draft)).commit())
    }

    /** Cancel of a draft tool: the project returns to the state before the tool opened. */
    fun cancelTool() = updateReady { ready ->
        if (ready.activeTool?.isDraft != true) ready else ready.copy(activeTool = null, transaction = ready.transaction.cancel())
    }

    /** Closes a tool whose changes are already committed. */
    fun closeTool() = updateReady { ready ->
        if (ready.activeTool?.isDraft != false || ready.transaction.isActive) ready else ready.copy(activeTool = null, selectedMaskId = null)
    }

    fun resetTool() {
        val ready = _state.value as? VideoEditorUiState.Ready ?: return
        when (ready.activeTool) {
            VideoTool.ADJUST -> commitImmediate { project -> project.updateClip { it.copy(effects = it.effects.copy(adjustments = Adjustments())) } }
            VideoTool.FILTER -> commitImmediate { project -> project.updateClip { it.copy(effects = it.effects.copy(filter = FilterSelection())) } }
            VideoTool.SPEED -> selectSpeed(1.0)
            VideoTool.AUDIO -> commitImmediate { project -> clampMasks(project.updateClip { it.copy(muted = false, volume = 1.0) }) }
            VideoTool.PRIVACY -> commitImmediate { it.copy(timeline = it.timeline.copy(privacyMasks = emptyList())) }
            VideoTool.TRIM -> updateDraftClip { _, clip ->
                clip.copy(sourceRange = TimeRangeUs(0, min(ready.sourceDurationUs, (config.maxTimelineDurationUs * clip.speed).roundToLong())))
            }
            VideoTool.CROP -> {
                updateReady { it.copy(cropAspect = CropAspectRatio.Free) }
                updateDraftGeometry { r, geometry -> geometry.copy(crop = CropBoundsCalculator.maxCrop(frameOf(r, geometry), CropAspectRatio.Free)) }
            }
            VideoTool.ROTATE -> updateDraftGeometry { _, _ -> GeometryEdit() }
            null -> Unit
        }
    }

    // ---- 구간 자르기 ----

    /** Starts dragging a trim handle. */
    fun beginTrim(edge: TrimEdge) {
        val ready = _state.value as? VideoEditorUiState.Ready ?: return
        if (ready.activeTool != VideoTool.TRIM) return
        trimEdge = edge
        engine.pause()
    }

    /**
     * Moves the dragged handle to [sourceTimeUs]. The kept part stays between the minimum clip and
     * the maximum timeline length, measured in output time after speed.
     */
    fun dragTrim(sourceTimeUs: Long) {
        val edge = trimEdge ?: return
        updateDraftClip { ready, clip ->
            val range = clip.sourceRange
            val minLength = (config.minClipDurationUs * clip.speed).roundToLong()
            val maxLength = (config.maxTimelineDurationUs * clip.speed).toLong()
            val next = when (edge) {
                TrimEdge.START -> {
                    val start = sourceTimeUs.coerceIn(max(0, range.endExclusiveUs - maxLength), max(0, range.endExclusiveUs - minLength))
                    TimeRangeUs(start, range.endExclusiveUs)
                }
                TrimEdge.END -> {
                    val end = sourceTimeUs.coerceIn(min(ready.sourceDurationUs, range.startUs + minLength), min(ready.sourceDurationUs, range.startUs + maxLength))
                    TimeRangeUs(range.startUs, end)
                }
            }
            pendingSeekUs = if (edge == TrimEdge.START) 0 else ((next.durationUs / clip.speed).toLong() - FRAME_US).coerceAtLeast(0)
            clip.copy(sourceRange = next)
        }
    }

    fun endTrim() {
        trimEdge = null
    }

    // ---- 자르기·회전 ----

    fun selectAspect(aspect: CropAspectRatio) {
        updateReady { it.copy(cropAspect = aspect) }
        updateDraftGeometry { ready, geometry -> GeometryOperations.withAspect(ready.source.metadata.uprightSize, geometry, aspect) }
    }

    override fun beginCropDrag(handle: CropHandle) {
        val ready = _state.value as? VideoEditorUiState.Ready ?: return
        if (ready.activeTool != VideoTool.CROP) return
        cropDragStart = handle to ready.clip.effects.geometry.crop
        updateReady { it.copy(draggingCrop = true) }
    }

    override fun dragCrop(dx: Double, dy: Double) {
        val (handle, start) = cropDragStart ?: return
        updateDraftGeometry { ready, geometry ->
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

    fun rotateLeft() = updateDraftGeometry { _, geometry -> GeometryOperations.rotateCounterClockwise(geometry) }

    fun rotateRight() = updateDraftGeometry { _, geometry -> GeometryOperations.rotateClockwise(geometry) }

    fun flipHorizontal() = updateDraftGeometry { _, geometry -> GeometryOperations.flipHorizontal(geometry) }

    fun flipVertical() = updateDraftGeometry { _, geometry -> GeometryOperations.flipVertical(geometry) }

    fun changeStraighten(degrees: Double) {
        val ready = _state.value as? VideoEditorUiState.Ready ?: return
        val start = straightenStart ?: ready.clip.effects.geometry.also { straightenStart = it }
        updateDraftGeometry { current, _ -> GeometryOperations.withStraighten(current.source.metadata.uprightSize, start, degrees) }
    }

    fun finishStraighten() {
        straightenStart = null
    }

    // ---- 보정·필터 ----

    fun selectAdjustment(kind: AdjustmentKind) = updateReady { it.copy(adjustKind = kind) }

    fun changeAdjustment(display: Float) = updateGesture { ready, project ->
        val kind = ready.adjustKind
        project.updateClip { it.copy(effects = it.effects.copy(adjustments = it.effects.adjustments.with(kind, kind.fromDisplay(display)))) }
    }

    /** End of a slider drag: commits it as one undo step. */
    fun finishGesture() = updateReady { ready ->
        if (ready.activeTool?.isDraft == true || !ready.transaction.isActive) ready else ready.copy(transaction = ready.transaction.commit())
    }

    fun selectFilter(presetId: String) = commitImmediate { project ->
        project.updateClip { clip ->
            val current = clip.effects.filter
            val intensity = when {
                presetId == FilterCatalog.ORIGINAL_ID -> 0.0
                current.presetId == presetId && current.intensity > 0.0 -> current.intensity
                else -> 1.0
            }
            clip.copy(effects = clip.effects.copy(filter = FilterSelection(presetId, intensity)))
        }
    }

    fun changeFilterIntensity(display: Float) = updateGesture { _, project ->
        project.updateClip { clip ->
            val filter = clip.effects.filter
            if (filter.presetId == FilterCatalog.ORIGINAL_ID) {
                clip
            } else {
                val intensity = (display.toDouble() / AdjustmentKind.DISPLAY_RANGE).coerceIn(0.0, 1.0)
                clip.copy(effects = clip.effects.copy(filter = filter.copy(intensity = intensity)))
            }
        }
    }

    private fun ensureFilterThumbnails(ready: VideoEditorUiState.Ready) {
        if (thumbnailJob != null) return
        thumbnailJob = viewModelScope.launch {
            val frame = frames.frame(ready.location, ready.clip.source.value, ready.clip.sourceRange.startUs, FILTER_THUMBNAIL_PX) ?: return@launch
            FilterCatalog.presets.forEach { preset ->
                val thumbnail = withContext(Dispatchers.Default) {
                    frame.copy(Bitmap.Config.ARGB_8888, true).also {
                        colorRenderer.apply(it, ColorEffectSpec.of(Adjustments(), FilterSelection(preset.id, 1.0), 0L), includeCanvasEffects = false)
                    }
                }
                _filterThumbnails.update { it + (preset.id to thumbnail) }
            }
        }
    }

    // ---- 속도·소리 ----

    /** `true` when [speed] keeps the result within the configured length limits. */
    fun speedAllowed(speed: Double): SpeedCheck {
        val ready = _state.value as? VideoEditorUiState.Ready ?: return SpeedCheck.OK
        val output = ready.clip.sourceRange.durationUs / speed
        return when {
            output > config.maxTimelineDurationUs -> SpeedCheck.TOO_LONG
            output < config.minClipDurationUs -> SpeedCheck.TOO_SHORT
            else -> SpeedCheck.OK
        }
    }

    fun selectSpeed(speed: Double) {
        when (speedAllowed(speed)) {
            SpeedCheck.TOO_LONG -> return updateReady { it.copy(notice = VideoNotice.SPEED_TOO_LONG) }
            SpeedCheck.TOO_SHORT -> return updateReady { it.copy(notice = VideoNotice.SPEED_TOO_SHORT) }
            SpeedCheck.OK -> Unit
        }
        commitImmediate { project -> clampMasks(project.updateClip { it.copy(speed = speed) }) }
    }

    fun setMuted(muted: Boolean) = commitImmediate { project -> project.updateClip { it.copy(muted = muted) } }

    /** Volume slider in percent, `0..200`. */
    fun changeVolume(percent: Float) = updateGesture { _, project ->
        project.updateClip { it.copy(volume = (percent / 100.0).coerceIn(0.0, VideoClip.MAX_VOLUME), muted = false) }
    }

    // ---- 모자이크 ----

    /** Settings for the next mask; existing masks keep their effect. */
    fun updatePrivacy(transform: (PrivacySettings) -> PrivacySettings) = updateReady { ready ->
        val next = transform(ready.privacy)
        ready.copy(privacy = if (next.shape == PrivacyShape.BRUSH) next.copy(shape = PrivacyShape.RECTANGLE) else next)
    }

    override fun beginMask(x: Double, y: Double) {
        val ready = _state.value as? VideoEditorUiState.Ready ?: return
        if (ready.activeTool != VideoTool.PRIVACY || ready.transaction.isActive) return
        if (ready.displayed.timeline.privacyMasks.size >= Timeline.MAX_PRIVACY_MASKS) {
            updateReady { it.copy(notice = VideoNotice.MASK_LIMIT) }
            return
        }
        engine.pause()
        maskAnchor = PointN(x, y)
        val id = newId()
        val shape = if (ready.privacy.shape == PrivacyShape.ELLIPSE) MaskShape.Ellipse(RectN(x, y, x, y)) else MaskShape.Rectangle(RectN(x, y, x, y))
        val mask = TimedPrivacyMask(PrivacyMask(id, shape, ready.privacy.effect()), defaultMaskRange(ready))
        updateGesture { _, project -> project.copy(timeline = project.timeline.copy(privacyMasks = project.timeline.privacyMasks + mask)) }
        updateReady { it.copy(selectedMaskId = id) }
    }

    override fun extendMask(x: Double, y: Double) {
        val anchor = maskAnchor ?: return
        val id = (_state.value as? VideoEditorUiState.Ready)?.selectedMaskId ?: return
        val rect = RectN(min(anchor.x, x), min(anchor.y, y), max(anchor.x, x), max(anchor.y, y))
        updateGesture { _, project ->
            project.updateMask(id) { timed ->
                val shape = when (timed.mask.shape) {
                    is MaskShape.Ellipse -> MaskShape.Ellipse(rect)
                    else -> MaskShape.Rectangle(rect)
                }
                timed.copy(mask = timed.mask.copy(shape = shape))
            }
        }
    }

    override fun finishMask() {
        maskAnchor ?: return
        maskAnchor = null
        val ready = _state.value as? VideoEditorUiState.Ready ?: return
        val id = ready.selectedMaskId
        val mask = ready.displayed.timeline.privacyMasks.firstOrNull { it.mask.id == id }
        val rect = when (val shape = mask?.mask?.shape) {
            is MaskShape.Rectangle -> shape.rect
            is MaskShape.Ellipse -> shape.rect
            else -> null
        }
        // 손가락을 거의 움직이지 않은 탭은 마스크를 만들지 않는다.
        if (rect == null || rect.width < MIN_MASK_SIZE || rect.height < MIN_MASK_SIZE) {
            updateReady { it.copy(transaction = it.transaction.cancel(), selectedMaskId = null) }
        } else {
            finishGesture()
        }
    }

    override fun selectMask(id: String?) = updateReady { it.copy(selectedMaskId = id) }

    fun deleteMask(id: String) {
        commitImmediate { project -> project.copy(timeline = project.timeline.copy(privacyMasks = project.timeline.privacyMasks.filterNot { it.mask.id == id })) }
        updateReady { if (it.selectedMaskId == id) it.copy(selectedMaskId = null) else it }
    }

    /** Moves the start (or end) of a mask to the playhead, keeping at least one frame. */
    fun setMaskEdge(id: String, start: Boolean) {
        val position = engine.state.value.positionUs
        commitImmediate { project ->
            val duration = TimelineTimeMapper.durationUs(project.timeline)
            project.updateMask(id) { timed ->
                val range = timed.range
                val next = if (start) {
                    TimeRangeUs(position.coerceIn(0, range.endExclusiveUs - FRAME_US), range.endExclusiveUs)
                } else {
                    TimeRangeUs(range.startUs, (position + FRAME_US).coerceIn(range.startUs + FRAME_US, duration))
                }
                timed.copy(range = next)
            }
        }
    }

    private fun defaultMaskRange(ready: VideoEditorUiState.Ready): TimeRangeUs {
        val duration = ready.durationUs
        val length = min(DEFAULT_MASK_US, duration)
        val start = engine.state.value.positionUs.coerceIn(0, duration - length)
        return TimeRangeUs(start, start + length)
    }

    // 길이가 바뀌면(자르기·속도) 결과 밖으로 나간 마스크 구간을 줄이고, 비게 된 마스크는 지운다.
    private fun clampMasks(project: VideoProject): VideoProject {
        val duration = TimelineTimeMapper.durationUs(project.timeline)
        val masks = project.timeline.privacyMasks.mapNotNull { timed ->
            val range = TimeRangeUs(timed.range.startUs.coerceIn(0, duration), timed.range.endExclusiveUs.coerceIn(0, duration))
            if (range.isEmpty) null else timed.copy(range = range)
        }
        return if (masks == project.timeline.privacyMasks) project else project.copy(timeline = project.timeline.copy(privacyMasks = masks))
    }

    // ---- 기록·저장·종료 ----

    fun undo() = updateReady { ready ->
        if (!config.allowUndo || ready.export != null) ready else ready.copy(transaction = ready.transaction.undo())
    }

    fun redo() = updateReady { ready ->
        if (!config.allowRedo || ready.export != null) ready else ready.copy(transaction = ready.transaction.redo())
    }

    fun save() {
        val ready = _state.value as? VideoEditorUiState.Ready ?: return
        if (ready.activeTool?.isDraft == true || ready.transaction.isActive) {
            updateReady { it.copy(showApplyHint = true) }
            return
        }
        // 결과를 이미 보냈거나 export가 진행 중이면 두 번째 저장 요청은 무시한다.
        if (_result.value != null || ready.export != null || exportJob?.isActive == true) return
        engine.pause()
        val snapshot = ready.transaction.history.current
        updateReady { it.copy(export = ExportUiState.Running(ExportStageUi.PREPARING)) }
        exportJob = viewModelScope.launch {
            try {
                session?.markExport(snapshot, running = true)
                val media = exporter.export(
                    snapshot,
                    mapOf(ready.clip.source to ready.source),
                    mapOf(ready.clip.source to ready.location),
                ) { progress -> updateReady { current -> current.withProgress(progress) } }
                finish(FrameKitResult.Success(media))
            } catch (cancelled: CancellationException) {
                updateReady { it.copy(export = null) }
                withContext(NonCancellable) { session?.markExport(snapshot, running = false) }
                throw cancelled
            } catch (error: FrameKitException) {
                logFailure("export", error)
                session?.markExport(snapshot, running = false)
                updateReady { it.copy(export = ExportUiState.Failed(error.code)) }
            } catch (error: Exception) {
                logFailure("export", FrameKitException(EditorErrorCode.UNKNOWN, cause = error))
                session?.markExport(snapshot, running = false)
                updateReady { it.copy(export = ExportUiState.Failed(EditorErrorCode.UNKNOWN)) }
            }
        }
    }

    private fun VideoEditorUiState.Ready.withProgress(progress: VideoExportProgress): VideoEditorUiState.Ready {
        val running = export as? ExportUiState.Running ?: return this
        if (running.stage == ExportStageUi.CANCELLING) return this
        val next = when (progress) {
            VideoExportProgress.Preparing -> ExportUiState.Running(ExportStageUi.PREPARING)
            is VideoExportProgress.Encoding -> ExportUiState.Running(ExportStageUi.ENCODING, progress.percent?.let { it / 100f })
            VideoExportProgress.Finalizing -> ExportUiState.Running(ExportStageUi.FINALIZING)
        }
        return copy(export = next)
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
            is VideoEditorUiState.Ready -> when {
                current.export is ExportUiState.Running -> cancelExport()
                current.isDirty || current.hasDraftChanges -> updateReady { it.copy(showDiscardDialog = true) }
                else -> finish(FrameKitResult.Cancelled)
            }
            is VideoEditorUiState.LoadFailed -> finish(FrameKitResult.Failure(EditorError(current.code)))
            else -> finish(FrameKitResult.Cancelled)
        }
    }

    /** System back: closes an open tool first, then behaves like [requestClose]. */
    fun onBack() {
        val ready = _state.value as? VideoEditorUiState.Ready
        when {
            ready == null || ready.activeTool == null || ready.export != null -> requestClose()
            ready.activeTool.isDraft -> cancelTool()
            else -> closeTool()
        }
    }

    fun confirmDiscard() = finish(FrameKitResult.Cancelled)

    fun dismissDiscard() = updateReady { it.copy(showDiscardDialog = false) }

    private fun finish(result: FrameKitResult) {
        if (_result.value != null) return
        engine.pause()
        pendingRestore?.let { session?.discard(it) }
        session?.end()
        _result.value = result
    }

    // ---- 내부 ----

    private fun updateReady(transform: (VideoEditorUiState.Ready) -> VideoEditorUiState.Ready) {
        var committedChange: VideoProject? = null
        _state.update { current ->
            if (current !is VideoEditorUiState.Ready) return@update current
            val next = transform(current)
            committedChange = next.transaction.history.current.takeIf { it !== current.transaction.history.current }
            next
        }
        committedChange?.let { session?.saveCommitted(it) }
    }

    private fun updateGesture(transform: (VideoEditorUiState.Ready, VideoProject) -> VideoProject) = updateReady { ready ->
        if (ready.activeTool?.isDraft == true || ready.export != null) return@updateReady ready
        val transaction = ready.transaction.begin()
        ready.copy(transaction = transaction.update(transform(ready, checkNotNull(transaction.draft))))
    }

    private fun commitImmediate(transform: (VideoProject) -> VideoProject) = updateReady { ready ->
        if (ready.transaction.isActive || ready.export != null) return@updateReady ready
        val next = transform(ready.transaction.history.current)
        if (next.sameContentAs(ready.transaction.history.current)) ready else ready.copy(transaction = ready.transaction.update(next).commit())
    }

    private fun updateDraftClip(transform: (VideoEditorUiState.Ready, VideoClip) -> VideoClip) = updateReady { ready ->
        val draft = ready.transaction.draft ?: return@updateReady ready
        ready.copy(transaction = ready.transaction.update(draft.updateClip { transform(ready, it) }))
    }

    private fun updateDraftGeometry(transform: (VideoEditorUiState.Ready, GeometryEdit) -> GeometryEdit) = updateDraftClip { ready, clip ->
        clip.copy(effects = clip.effects.copy(geometry = transform(ready, clip.effects.geometry)))
    }

    private fun frameOf(ready: VideoEditorUiState.Ready, geometry: GeometryEdit) = GeometryFrame(ready.source.metadata.uprightSize, geometry)

    private fun newId(): String = UUID.randomUUID().toString()

    private fun logFailure(stage: String, error: FrameKitException) {
        // URI·파일명이 남지 않도록 예외 메시지 대신 코드와 예외 종류만 기록한다.
        Log.w(TAG, "$stage failed: code=${error.code} cause=${error.cause?.javaClass?.simpleName}")
    }

    override fun onCleared() {
        if (_result.value == null) session?.detach()
        engine.release()
        colorRenderer.release()
        closeables.forEach { runCatching { it.close() } }
    }

    private companion object {
        const val TAG = "FrameKit"
        const val KEY_PICKED_URI = "framekit_picked_video_uri"
        const val PREVIEW_DEBOUNCE_MILLIS = 120L
        const val SNAPSHOT_DEBOUNCE_MILLIS = 300L
        const val PREVIEW_SHORT_SIDE = 720
        const val FILTER_THUMBNAIL_PX = 160
        const val FRAME_US = 33_333L
        const val DEFAULT_MASK_US = 3_000_000L
        const val MIN_MASK_SIZE = 0.01
    }
}

internal enum class TrimEdge { START, END }

internal enum class SpeedCheck { OK, TOO_LONG, TOO_SHORT }

/** Gestures on the video preview. */
internal interface VideoCanvasActions {
    fun beginCropDrag(handle: CropHandle)
    fun dragCrop(dx: Double, dy: Double)
    fun endCropDrag()
    fun beginMask(x: Double, y: Double)
    fun extendMask(x: Double, y: Double)
    fun finishMask()
    fun selectMask(id: String?)
}

private fun VideoProject.updateClip(transform: (VideoClip) -> VideoClip): VideoProject =
    copy(timeline = timeline.copy(videoClips = timeline.videoClips.mapIndexed { index, clip -> if (index == 0) transform(clip) else clip }))

private fun VideoProject.updateMask(id: String, transform: (TimedPrivacyMask) -> TimedPrivacyMask): VideoProject =
    copy(timeline = timeline.copy(privacyMasks = timeline.privacyMasks.map { if (it.mask.id == id) transform(it) else it }))
