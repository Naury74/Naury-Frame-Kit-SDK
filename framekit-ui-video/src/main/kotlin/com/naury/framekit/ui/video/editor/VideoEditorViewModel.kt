package com.naury.framekit.ui.video.editor

import java.io.File
import com.naury.framekit.android.capture.CaptureFiles
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import android.view.SurfaceHolder
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.naury.framekit.android.input.EditorInput
import com.naury.framekit.android.result.EditedMedia
import com.naury.framekit.android.result.EditorError
import com.naury.framekit.android.result.EditorErrorCode
import com.naury.framekit.android.result.FrameKitException
import com.naury.framekit.android.result.FrameKitResult
import com.naury.framekit.android.session.EditorSessionStore
import com.naury.framekit.android.session.SessionRecord
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
import com.naury.framekit.core.history.EditHistory
import com.naury.framekit.core.history.HistoryTransaction
import com.naury.framekit.core.model.ProjectId
import com.naury.framekit.core.model.SourceId
import com.naury.framekit.core.overlay.EmojiCatalog
import com.naury.framekit.core.overlay.ImageOverlay
import com.naury.framekit.core.overlay.MaskShape
import com.naury.framekit.core.overlay.PrivacyMask
import com.naury.framekit.core.overlay.TextStyleSpec
import com.naury.framekit.core.validation.VideoProjectValidator
import com.naury.framekit.core.video.AudioClip
import com.naury.framekit.core.video.CanvasSpec
import com.naury.framekit.core.video.TimeRangeUs
import com.naury.framekit.core.video.TimedOverlay
import com.naury.framekit.core.video.TimedPrivacyMask
import com.naury.framekit.core.video.Timeline
import com.naury.framekit.core.video.TimelineTimeMapper
import com.naury.framekit.core.video.VideoClip
import com.naury.framekit.core.video.VideoProject
import com.naury.framekit.image.effect.ColorEffectRenderer
import com.naury.framekit.image.effect.CpuColorEffectRenderer
import com.naury.framekit.ui.component.ExportStageUi
import com.naury.framekit.ui.tool.OverlayGestures
import com.naury.framekit.ui.tool.PrivacySettings
import com.naury.framekit.ui.tool.PrivacyShape
import com.naury.framekit.ui.video.contract.VideoEditorRequest
import com.naury.framekit.ui.video.contract.VideoTool
import com.naury.framekit.ui.video.editor.VideoSessionRecorder.Companion.toInput
import com.naury.framekit.ui.video.editor.VideoSessionRecorder.Companion.toReference
import com.naury.framekit.ui.video.editor.VideoTimelineEditing as Edit
import com.naury.framekit.video.VideoPlanFactory
import com.naury.framekit.video.export.VideoExportProgress
import com.naury.framekit.video.preview.VideoPlaybackState
import com.naury.framekit.video.preview.VideoPreviewEngine
import com.naury.framekit.video.source.AudioSourceInfo
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
import java.io.Closeable
import java.util.UUID
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.random.Random

/** 편집한 영상을 저장한다. 실제 앱에서는 [VideoExportCoordinator][com.naury.framekit.video.export.VideoExportCoordinator]다. */
internal fun interface VideoExporter {
    suspend fun export(
        project: VideoProject,
        sources: Map<SourceId, VideoSourceInfo>,
        locations: Map<SourceId, SourceLocation>,
        audioSources: Map<SourceId, AudioSourceInfo>,
        onProgress: (VideoExportProgress) -> Unit,
    ): EditedMedia
}

/** 타임라인 프레임. 실제 앱에서는 [VideoThumbnailLoader][com.naury.framekit.video.thumbnail.VideoThumbnailLoader]다. */
internal fun interface VideoFrameSource {
    suspend fun frame(location: SourceLocation, sourceKey: String, timeUs: Long, heightPx: Int): Bitmap?
}

/**
 * 영상 편집 세션 하나의 상태 홀더.
 *
 * 편집 방식은 사진 편집기를 따른다. 초안 도구(구간·자르기·회전·속도·텍스트)는 도구를 열고 적용할 때까지가
 * 실행 취소 한 단계이고, 슬라이더는 드래그당, 클립 나누기·순서 변경·추가·삭제는 각각 한 단계다. 클립 단위
 * 도구는 선택한 클립을 고친다. 미리보기는 표시 중인 프로젝트를 짧게 모았다가 다시 구성하고, 저장은 항상
 * 확정된 프로젝트를 쓴다.
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
    private val closeables: List<Closeable> = emptyList(),
    sessionStore: EditorSessionStore? = null,
    snapshotDebounceMillis: Long = SNAPSHOT_DEBOUNCE_MILLIS,
    private val readAudio: (SourceId) -> AudioSourceInfo = { throw FrameKitException(EditorErrorCode.UNSUPPORTED_OPERATION, "No audio reader") },
    private val describe: (Uri) -> String? = { null },
    private val captureFile: (() -> Pair<File, Uri>)? = null,
    cleanup: (() -> Unit)? = null,
) : ViewModel(), VideoCanvasActions {

    private val _state = MutableStateFlow<VideoEditorUiState>(VideoEditorUiState.Loading)
    val state: StateFlow<VideoEditorUiState> = _state.asStateFlow()

    private val _result = MutableStateFlow<FrameKitResult?>(null)

    /** 최종 결과. 이 값이 `null`이 아니게 되는 즉시 Activity가 종료된다. */
    val result: StateFlow<FrameKitResult?> = _result.asStateFlow()

    private val engine = previewEngineFactory(viewModelScope)

    /** 미리보기의 재생 위치와 상태(미리보기 타임라인의 출력 시간 기준). */
    val playback: StateFlow<VideoPlaybackState> = engine.state

    private val _previewSize = MutableStateFlow<Pair<Int, Int>?>(null)

    /** 미리보기가 보여 주는 프레임 크기. 출력 캔버스이며, 자르는 중에는 선택한 클립의 자르기 전 프레임이다. */
    val previewSize: StateFlow<Pair<Int, Int>?> = _previewSize.asStateFlow()

    private val _previewFailed = MutableStateFlow(false)

    /** 미리보기를 구성하지 못했으면 `true`. 재생 오류는 [playback]의 `error`로 온다. */
    val previewFailed: StateFlow<Boolean> = _previewFailed.asStateFlow()

    private val _filterThumbnails = MutableStateFlow<Map<String, Bitmap>>(emptyMap())
    val filterThumbnails: StateFlow<Map<String, Bitmap>> = _filterThumbnails.asStateFlow()

    private var exportJob: Job? = null
    private var thumbnailJob: Job? = null
    private var thumbnailClipSource: SourceId? = null
    private var cropDragStart: Pair<CropHandle, RectN>? = null
    private var straightenStart: GeometryEdit? = null
    private var trimEdge: TrimEdge? = null
    private var maskAnchor: PointN? = null
    private var overlayGestureStart: ImageOverlay? = null
    private var planRevision = 0L
    private var lastPreviewProject: VideoProject? = null
    private var pendingSeekUs: Long? = null
    private val previewRequests = MutableStateFlow<VideoProject?>(null)
    private val session = sessionStore?.let { VideoSessionRecorder(it, savedState, viewModelScope, ioDispatcher, snapshotDebounceMillis) }

    // 권한을 잃은 원본을 다시 고를 때까지 이전 세션을 들고 있다가, 같은 영상인지 확인한 뒤에만 복원한다.
    private var pendingRestore: SessionRecord? = null

    val config get() = request.config

    /** 클립을 더 붙이거나 나눌 수 있는 설정이면 `true`. */
    val multiClip: Boolean get() = config.maxClipCount > 1

    init {
        // 이전 저장이 중단되며 남긴 임시 MP4를 지운다(수 GB일 수 있다).
        cleanup?.let { task -> viewModelScope.launch(ioDispatcher) { runCatching(task) } }
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

    // ---- 열기·복원 ----

    /** 이 요청으로 처음에 고를 수 있는 최대 영상 수. */
    private val pickLimit: Int
        get() = when (val input = request.input) {
            is EditorInput.Pick -> minOf(input.maxItems, config.maxClipCount)
            else -> 1
        }

    private fun start() {
        when (val input = request.input) {
            is EditorInput.UriSource, is EditorInput.FileSource -> load(input)
            is EditorInput.Multiple -> load(input.items.first(), then = input.items.drop(1))
            is EditorInput.Pick -> {
                val picked = savedState.get<ArrayList<Uri>>(KEY_PICKED_URIS)
                if (picked.isNullOrEmpty()) {
                    _state.value = VideoEditorUiState.AwaitingSource(VideoSourceMode.Pick(pickLimit))
                } else {
                    load(EditorInput.UriSource(picked.first()), then = picked.drop(1).map(EditorInput::UriSource))
                }
            }
            is EditorInput.Capture -> {
                val path = savedState.get<String>(KEY_CAPTURE_PATH)
                if (path != null && CaptureFiles.isFilled(File(path))) {
                    load(EditorInput.FileSource(path))
                } else {
                    _state.value = VideoEditorUiState.AwaitingSource(VideoSourceMode.Capture)
                }
            }
        }
    }

    private fun restore() {
        viewModelScope.launch {
            val previous = withContext(ioDispatcher) { session?.loadPrevious() }
            if (previous == null) {
                start()
            } else {
                pendingRestore = previous
                load(previous.source.toInput())
            }
        }
    }

    /** picker 결과와 함께 호출된다. 빈 목록은 사용자가 picker를 닫았다는 뜻이다. */
    fun onPicked(uris: List<Uri>) {
        if (uris.isEmpty()) {
            if (_state.value is VideoEditorUiState.AwaitingSource) finish(FrameKitResult.Cancelled)
            return
        }
        val limited = uris.take(maxOf(1, pickLimit))
        savedState[KEY_PICKED_URIS] = ArrayList(limited)
        load(EditorInput.UriSource(limited.first()), then = limited.drop(1).map(EditorInput::UriSource))
    }

    /** 한 개 picker 결과. `null`은 picker를 닫았다는 뜻이다. */
    fun onPicked(uri: Uri?) = onPicked(listOfNotNull(uri))

    /** 촬영 파일을 만들고 카메라 앱에 넘길 Uri를 돌려준다. 만들 수 없으면 실패로 닫고 `null`. */
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
            load(EditorInput.FileSource(path))
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
        _state.value = VideoEditorUiState.AwaitingSource(VideoSourceMode.Pick(pickLimit))
    }

    /** @param then 처음 영상 뒤에 이어 붙일 영상. 처음 상태(기준)로 들어가 실행 취소 대상이 아니다. */
    private fun load(input: EditorInput, then: List<EditorInput> = emptyList()) {
        _state.value = VideoEditorUiState.Loading
        viewModelScope.launch {
            try {
                val restoring = pendingRestore != null
                _state.value = withContext(ioDispatcher) {
                    val (sourceId, loaded) = openVideo(input)
                    val previous = session?.attach(pendingRestore, input, sourceId, loaded.info)
                    pendingRestore = null
                    restoredState(previous, sourceId, loaded)
                }
                if (then.isNotEmpty() && !restoring) addClipInputs(then, asBaseline = true)
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

    /** IO 스레드에서 영상 원본을 등록하고 읽는다. */
    private fun openVideo(input: EditorInput): Pair<SourceId, LoadedVideo> {
        val sourceId = registry.register(input)
        val info = readSource(sourceId)
        val location = registry.location(sourceId) ?: throw FrameKitException(EditorErrorCode.SOURCE_UNAVAILABLE, "Source has no location")
        if ((info.metadata.durationUs ?: 0L) < config.minClipDurationUs) {
            throw FrameKitException(EditorErrorCode.INVALID_SOURCE, "Video is shorter than the minimum clip")
        }
        return sourceId to LoadedVideo(info, location, input.toReference())
    }

    private fun openAudio(input: EditorInput, name: String?): Pair<SourceId, LoadedAudio> {
        val sourceId = registry.register(input)
        val info = readAudio(sourceId)
        val location = registry.location(sourceId) ?: throw FrameKitException(EditorErrorCode.SOURCE_UNAVAILABLE, "Source has no location")
        return sourceId to LoadedAudio(info, location, input.toReference(), name)
    }

    private fun restoredState(previous: SessionRecord?, mainId: SourceId, main: LoadedVideo): VideoEditorUiState.Ready {
        val sources = mutableMapOf(mainId to main)
        val music = mutableMapOf<SourceId, LoadedAudio>()
        val snapshot = previous?.videoSnapshot
        // 이전 세션에서 붙였던 원본을 다시 연다. 열지 못한 원본의 클립은 빼고 복원한다.
        val extra = snapshot?.extraSources.orEmpty().map { reference ->
            runCatching { openVideo(reference.toInput()) }.getOrNull()?.let { (id, loaded) ->
                sources[id] = loaded
                id to reference
            }
        }
        val audio = snapshot?.audioSources.orEmpty().map { reference ->
            runCatching { openAudio(reference.toInput(), null) }.getOrNull()?.let { (id, loaded) ->
                music[id] = loaded
                id to reference
            }
        }
        val restoredPair = session?.restoredProject(previous, extra, audio)
        val restored = restoredPair?.first?.takeIf { project ->
            val metadata = sources.mapValues { it.value.info.metadata } + music.mapValues { it.value.info.metadata }
            VideoProjectValidator.validate(project, metadata, config.minClipDurationUs).isValid &&
                TimelineTimeMapper.durationUs(project.timeline) <= config.maxTimelineDurationUs &&
                project.timeline.videoClips.size <= config.maxClipCount
        }
        // 허용 길이보다 긴 영상은 앞부분만 남긴 채로 열고, 사용자가 구간을 옮겨 고른다.
        val duration = main.info.metadata.durationUs ?: 0L
        val firstClipId = restored?.timeline?.videoClips?.firstOrNull { it.source == mainId }?.id ?: newId()
        val clip = VideoClip(firstClipId, mainId, TimeRangeUs(0, min(duration, config.maxTimelineDurationUs)))
        val baseline = VideoProject(restored?.id ?: ProjectId(newId()), Timeline(listOf(clip)), grainSeed = restored?.grainSeed ?: Random.nextLong())
        val transaction = if (restored != null) HistoryTransaction(EditHistory.restore(baseline, restored)) else HistoryTransaction.start(baseline)
        val notice = when {
            previous?.exportWasInterrupted == true -> VideoNotice.EXPORT_INTERRUPTED
            restored != null && restoredPair.second -> VideoNotice.PARTIALLY_RESTORED
            restored != null && !restored.sameContentAs(baseline) -> VideoNotice.RESTORED
            else -> null
        }
        if (previous?.exportWasInterrupted == true || restoredPair?.second == true) session?.saveCommitted(transaction.history.current)
        return VideoEditorUiState.Ready(sources, transaction, transaction.history.current.timeline.videoClips.first().id, music = music, notice = notice)
    }

    // ---- 미리보기 ----

    private fun requestPreview(ready: VideoEditorUiState.Ready) {
        val project = previewProject(ready)
        if (lastPreviewProject?.sameContentAs(project) == true) return
        lastPreviewProject = project
        previewRequests.value = project
    }

    private fun previewProject(ready: VideoEditorUiState.Ready): VideoProject {
        val project = ready.displayed
        return when {
            // 자르기·회전 중에는 선택한 클립만, 잘리기 전 전체 프레임으로 보여 주고 그 위에 자르기 프레임을 그린다.
            ready.activeTool?.isGeometry == true -> {
                val clip = ready.clip.let { it.copy(effects = it.effects.copy(geometry = it.effects.geometry.copy(crop = RectN.Full))) }
                project.copy(timeline = Timeline(listOf(clip)))
            }
            // 텍스트·스티커를 고치는 동안에는 화면이 직접 그려 손가락을 바로 따라가게 한다.
            ready.activeTool == VideoTool.TEXT || ready.activeTool == VideoTool.STICKER ->
                project.copy(timeline = project.timeline.copy(overlays = emptyList()))
            else -> project
        }
    }

    private fun showPreview(project: VideoProject) {
        val ready = _state.value as? VideoEditorUiState.Ready ?: return
        val plan = try {
            VideoPlanFactory.create(
                project,
                ready.sources.mapValues { it.value.info },
                ready.sources.mapValues { it.value.location } + ready.music.mapValues { it.value.location },
                PREVIEW_SHORT_SIDE,
                ready.music.mapValues { it.value.info },
            ).copy(projectRevision = ++planRevision)
        } catch (error: FrameKitException) {
            logFailure("preview", error)
            return
        }
        val duration = TimelineTimeMapper.durationUs(project.timeline)
        val position = (pendingSeekUs ?: engine.state.value.positionUs).coerceIn(0, max(0, duration - FRAME_US))
        pendingSeekUs = null
        _previewSize.value = plan.canvasSize.width to plan.canvasSize.height
        try {
            engine.setPlan(plan, position)
            _previewFailed.value = false
        } catch (error: RuntimeException) {
            // 미리보기 구성에 실패해도 편집과 저장은 계속할 수 있게 한다. 화면에는 미리보기 오류를 표시한다.
            logFailure("preview", FrameKitException(EditorErrorCode.DECODE_FAILED, cause = error))
            _previewFailed.value = true
        }
    }

    fun togglePlayback() {
        if (engine.state.value.isPlaying) engine.pause() else engine.play()
    }

    /** 출력 시간으로 탐색한다. 사용자가 드래그하는 동안 [scrubbing]이면 정확도보다 속도를 우선한다. */
    fun seekTo(positionUs: Long, scrubbing: Boolean = false) {
        val ready = _state.value as? VideoEditorUiState.Ready ?: return
        if (scrubbing && engine.state.value.isPlaying) engine.pause()
        engine.setScrubbing(scrubbing)
        engine.seekTo(positionUs.coerceIn(0, max(0, ready.durationUs - FRAME_US)))
    }

    fun finishScrub() = engine.setScrubbing(false)

    fun attachSurface(holder: SurfaceHolder) = engine.attachSurface(holder)

    fun detachSurface(holder: SurfaceHolder) = engine.detachSurface(holder)

    /** Activity가 멈출 때 호출된다. 백그라운드에서는 재생을 계속하지 않는다. */
    fun onStop() = engine.pause()

    /** [source] 원본의 타임라인 프레임. 읽을 수 없으면 `null`. */
    suspend fun timelineFrame(source: SourceId, sourceTimeUs: Long, heightPx: Int): Bitmap? {
        val ready = _state.value as? VideoEditorUiState.Ready ?: return null
        val location = ready.sources[source]?.location ?: return null
        return frames.frame(location, source.value, sourceTimeUs, heightPx)
    }

    // ---- 도구 ----

    fun dismissNotice() = updateReady { it.copy(notice = null) }

    fun selectTool(tool: VideoTool) {
        val ready = _state.value as? VideoEditorUiState.Ready ?: return
        if (ready.activeTool != null || ready.export != null || ready.transaction.isActive || ready.busy || tool !in config.enabledTools) return
        if (tool == VideoTool.FILTER) ensureFilterThumbnails(ready)
        if (tool.isDraft || tool == VideoTool.STICKER) engine.pause()
        if (tool.isGeometry) {
            // 선택한 클립만 미리보므로 위치를 그 클립 안의 시간으로 바꾼다.
            pendingSeekUs = (engine.state.value.positionUs - ready.clipStartUs).coerceIn(0, ready.clip.outputDurationUs)
        }
        if (tool == VideoTool.TEXT) return openText(existingId = null)
        updateReady {
            when {
                tool.isDraft -> it.copy(activeTool = tool, cropAspect = CropAspectRatio.Free, transaction = it.transaction.begin())
                else -> it.copy(activeTool = tool, selectedMaskId = null, selectedOverlayId = null)
            }
        }
    }

    fun applyTool() {
        val ready = _state.value as? VideoEditorUiState.Ready ?: return
        if (ready.activeTool?.isDraft != true) return
        var draft = ready.transaction.draft ?: return
        val editing = ready.editingTextId
        if (editing != null) {
            val text = draft.timeline.overlays.firstOrNull { it.overlay.id == editing }?.overlay as? ImageOverlay.Text
            if (text != null && text.text.isBlank()) draft = Edit.removeOverlay(draft, editing)
        }
        leaveGeometry(ready)
        updateReady { current ->
            val committed = current.transaction.update(Edit.clampTimed(draft)).commit()
            val selected = current.selectedOverlayId?.takeIf { id -> committed.history.current.timeline.overlays.any { it.overlay.id == id } }
            current.copy(activeTool = null, transaction = committed, editingTextId = null, selectedOverlayId = selected)
        }
    }

    /** 초안 도구 취소. 프로젝트는 도구를 열기 전 상태로 돌아간다. */
    fun cancelTool() {
        val ready = _state.value as? VideoEditorUiState.Ready ?: return
        if (ready.activeTool?.isDraft != true) return
        leaveGeometry(ready)
        updateReady { current ->
            val cancelled = current.transaction.cancel()
            val selected = current.selectedOverlayId?.takeIf { id -> cancelled.history.current.timeline.overlays.any { it.overlay.id == id } }
            current.copy(activeTool = null, transaction = cancelled, editingTextId = null, selectedOverlayId = selected)
        }
    }

    private fun leaveGeometry(ready: VideoEditorUiState.Ready) {
        if (ready.activeTool?.isGeometry == true) pendingSeekUs = ready.clipStartUs + engine.state.value.positionUs
    }

    /** 변경이 이미 확정된 도구를 닫는다. */
    fun closeTool() = updateReady { ready ->
        if (ready.activeTool?.isDraft != false || ready.transaction.isActive) ready else ready.copy(activeTool = null, selectedMaskId = null, selectedOverlayId = null)
    }

    fun resetTool() {
        val ready = _state.value as? VideoEditorUiState.Ready ?: return
        val id = ready.clip.id
        when (ready.activeTool) {
            VideoTool.ADJUST -> commitImmediate { Edit.updateClip(it, id) { c -> c.copy(effects = c.effects.copy(adjustments = Adjustments())) } }
            VideoTool.FILTER -> commitImmediate { Edit.updateClip(it, id) { c -> c.copy(effects = c.effects.copy(filter = FilterSelection())) } }
            VideoTool.SPEED -> selectSpeed(1.0)
            VideoTool.AUDIO -> {
                val hadMusic = ready.displayed.timeline.audioClips.isNotEmpty()
                commitImmediate { Edit.setMusic(Edit.updateClip(it, id) { c -> c.copy(muted = false, volume = 1.0) }, null) }
                if (hadMusic) notifyCleared()
            }
            VideoTool.PRIVACY -> if (ready.displayed.timeline.privacyMasks.isNotEmpty()) {
                commitImmediate { it.copy(timeline = it.timeline.copy(privacyMasks = emptyList())) }
                notifyCleared()
            }
            VideoTool.STICKER -> if (ready.displayed.timeline.overlays.any { it.overlay is ImageOverlay.Sticker }) {
                commitImmediate { p -> p.copy(timeline = p.timeline.copy(overlays = p.timeline.overlays.filterNot { it.overlay is ImageOverlay.Sticker })) }
                notifyCleared()
            }
            VideoTool.TEXT -> updateEditingText { it.copy(style = TextStyleSpec()) }
            VideoTool.TRIM -> updateDraftClip { r, clip ->
                clip.copy(sourceRange = TimeRangeUs(0, min(r.sourceDurationUs, (maxClipOutputUs(r, clip) * clip.speed).roundToLong())))
            }
            VideoTool.CROP -> {
                updateReady { it.copy(cropAspect = CropAspectRatio.Free) }
                updateDraftGeometry { r, geometry -> geometry.copy(crop = CropBoundsCalculator.maxCrop(frameOf(r, geometry), CropAspectRatio.Free)) }
            }
            VideoTool.ROTATE -> updateDraftGeometry { _, _ -> GeometryEdit() }
            VideoTool.CANVAS -> setCanvas(CanvasSpec())
            null -> Unit
        }
    }

    // 여러 항목을 한 번에 지운 초기화는 되돌리기 쉽도록 실행 취소를 안내한다.
    private fun notifyCleared() = updateReady { it.copy(notice = VideoNotice.CLEARED) }

    // ---- 클립 ----

    /** 클립을 고른다. [seek]이면 그 클립의 시작으로 이동한다. */
    fun selectClip(id: String, seek: Boolean = false) {
        val ready = _state.value as? VideoEditorUiState.Ready ?: return
        if (ready.activeTool != null || ready.clips.none { it.id == id }) return
        updateReady { it.copy(selectedClipId = id) }
        if (seek) seekTo(Edit.clipStart(ready.displayed, id))
    }

    /** 재생 위치를 지나는 클립을 고른다. 타임라인을 끌어 재생 위치를 옮긴 뒤 호출한다. */
    fun selectClipAtPlayhead() {
        val ready = _state.value as? VideoEditorUiState.Ready ?: return
        if (ready.activeTool != null) return
        val id = Edit.clipAt(ready.displayed, engine.state.value.positionUs) ?: return
        if (id != ready.selectedClipId) updateReady { it.copy(selectedClipId = id) }
    }

    /** 재생 위치에서 클립을 둘로 나눈다. 효과는 양쪽에 그대로 남는다. */
    fun splitAtPlayhead() {
        val ready = _state.value as? VideoEditorUiState.Ready ?: return
        if (!multiClip || ready.transaction.isActive) return
        if (ready.clips.size >= config.maxClipCount) return updateReady { it.copy(notice = VideoNotice.CLIP_LIMIT) }
        val newId = newId()
        val split = Edit.split(ready.transaction.history.current, engine.state.value.positionUs, config.minClipDurationUs, newId)
            ?: return updateReady { it.copy(notice = VideoNotice.SPLIT_UNAVAILABLE) }
        engine.pause()
        commitImmediate { split }
        updateReady { it.copy(selectedClipId = newId) }
    }

    /** 선택한 클립을 앞(-1)이나 뒤(+1)로 옮긴다. 텍스트·마스크·음악은 출력 시간에 그대로 남는다. */
    fun moveSelectedClip(delta: Int) {
        val ready = _state.value as? VideoEditorUiState.Ready ?: return
        engine.pause()
        commitImmediate { Edit.move(it, ready.clip.id, delta) }
        (_state.value as? VideoEditorUiState.Ready)?.let { seekTo(it.clipStartUs) }
    }

    fun deleteSelectedClip() {
        val ready = _state.value as? VideoEditorUiState.Ready ?: return
        if (ready.clips.size <= 1) return
        val index = ready.clips.indexOfFirst { it.id == ready.clip.id }
        val next = ready.clips.getOrNull(index + 1) ?: ready.clips[index - 1]
        engine.pause()
        commitImmediate { Edit.remove(it, ready.clip.id) }
        updateReady { it.copy(selectedClipId = next.id) }
    }

    /** 고른 영상을 끝에 붙인다. 클립 수·전체 길이 제한을 넘는 영상은 붙이지 않는다. 한 번에 한 단계다. */
    fun addClips(uris: List<Uri>) {
        if (!multiClip) return
        addClipInputs(uris.map(EditorInput::UriSource), asBaseline = false)
    }

    /** @param asBaseline `true`면 처음 연 상태로 넣어 실행 취소 기록을 남기지 않는다(여러 개를 한 번에 열 때). */
    private fun addClipInputs(inputs: List<EditorInput>, asBaseline: Boolean) {
        val ready = _state.value as? VideoEditorUiState.Ready ?: return
        if (inputs.isEmpty() || ready.busy || ready.transaction.isActive) return
        engine.pause()
        updateReady { it.copy(busy = true) }
        viewModelScope.launch {
            var notice: VideoNotice? = null
            val opened = withContext(ioDispatcher) {
                inputs.mapNotNull { input ->
                    try {
                        openVideo(input)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        logFailure("add", error as? FrameKitException ?: FrameKitException(EditorErrorCode.DECODE_FAILED, cause = error))
                        notice = VideoNotice.ADD_FAILED
                        null
                    }
                }
            }
            val current = _state.value as? VideoEditorUiState.Ready ?: return@launch
            var remaining = config.maxTimelineDurationUs - current.durationUs
            var slots = config.maxClipCount - current.clips.size
            val added = mutableListOf<VideoClip>()
            opened.forEach { (id, loaded) ->
                val length = min(loaded.info.metadata.durationUs ?: 0L, remaining)
                when {
                    slots <= 0 -> notice = VideoNotice.CLIP_LIMIT
                    length < config.minClipDurationUs -> notice = VideoNotice.TIMELINE_FULL
                    else -> {
                        added += VideoClip(newId(), id, TimeRangeUs(0, length))
                        remaining -= length
                        slots -= 1
                        session?.addSource(id, loaded.reference)
                    }
                }
            }
            updateReady { it.copy(sources = it.sources + opened.toMap(), busy = false, notice = notice ?: it.notice) }
            if (added.isNotEmpty() && asBaseline) {
                updateReady { it.copy(transaction = HistoryTransaction.start(Edit.append(it.transaction.history.current, added))) }
            } else if (added.isNotEmpty()) {
                commitImmediate { Edit.append(it, added) }
                updateReady { it.copy(selectedClipId = added.first().id) }
                (_state.value as? VideoEditorUiState.Ready)?.let { seekTo(it.clipStartUs) }
            }
        }
    }

    // ---- 구간 자르기 ----

    /** 구간 손잡이 드래그를 시작한다. */
    fun beginTrim(edge: TrimEdge) {
        val ready = _state.value as? VideoEditorUiState.Ready ?: return
        if (ready.activeTool != VideoTool.TRIM) return
        trimEdge = edge
        engine.pause()
    }

    /**
     * 드래그 중인 손잡이를 원본 시간 [sourceTimeUs]로 옮긴다. 남길 구간은 배속 적용 뒤 출력 시간 기준으로
     * 최소 클립 길이와, 다른 클립을 뺀 남은 최대 길이 사이로 유지된다.
     */
    fun dragTrim(sourceTimeUs: Long) {
        val edge = trimEdge ?: return
        updateDraftClip { ready, clip ->
            val range = clip.sourceRange
            val minLength = (config.minClipDurationUs * clip.speed).roundToLong()
            val maxLength = (maxClipOutputUs(ready, clip) * clip.speed).toLong()
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
            val start = ready.clipStartUs
            pendingSeekUs = if (edge == TrimEdge.START) start else start + ((next.durationUs / clip.speed).toLong() - FRAME_US).coerceAtLeast(0)
            clip.copy(sourceRange = next)
        }
    }

    fun endTrim() {
        trimEdge = null
    }

    // 다른 클립의 길이를 뺀, 이 클립이 가질 수 있는 최대 출력 길이.
    private fun maxClipOutputUs(ready: VideoEditorUiState.Ready, clip: VideoClip): Long =
        config.maxTimelineDurationUs - (TimelineTimeMapper.durationUs(ready.displayed.timeline) - clip.outputDurationUs)

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

    fun rotateLeft() = updateDraftGeometry { ready, geometry -> keepAspect(ready, GeometryOperations.rotateCounterClockwise(geometry)) }

    fun rotateRight() = updateDraftGeometry { ready, geometry -> keepAspect(ready, GeometryOperations.rotateClockwise(geometry)) }

    // 자르기 중 90° 회전하면 프레임의 가로세로가 뒤바뀐다. 고른 비율 칩과 실제 프레임이 어긋나지 않도록 다시 맞춘다.
    private fun keepAspect(ready: VideoEditorUiState.Ready, geometry: GeometryEdit): GeometryEdit =
        if (ready.activeTool == VideoTool.CROP && ready.cropAspect != CropAspectRatio.Free) {
            GeometryOperations.withAspect(ready.source.metadata.uprightSize, geometry, ready.cropAspect)
        } else {
            geometry
        }

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

    // ---- 화면 비율 ----

    /** 결과 화면 비율과 맞춤 방식을 바꾼다. 한 번 고를 때마다 한 단계다. */
    fun setCanvas(canvas: CanvasSpec) = commitImmediate { it.copy(canvas = canvas) }

    // ---- 보정·필터 ----

    fun selectAdjustment(kind: AdjustmentKind) = updateReady { it.copy(adjustKind = kind) }

    fun changeAdjustment(display: Float) = updateGesture { ready, project ->
        val kind = ready.adjustKind
        Edit.updateClip(project, ready.clip.id) { it.copy(effects = it.effects.copy(adjustments = it.effects.adjustments.with(kind, kind.fromDisplay(display)))) }
    }

    /** 슬라이더나 화면 제스처가 끝났다. 실행 취소 한 단계로 확정한다. */
    fun finishGesture() = updateReady { ready ->
        if (ready.activeTool?.isDraft == true || !ready.transaction.isActive) ready else ready.copy(transaction = ready.transaction.commit())
    }

    fun selectFilter(presetId: String) {
        val id = (_state.value as? VideoEditorUiState.Ready)?.clip?.id ?: return
        commitImmediate { project ->
            Edit.updateClip(project, id) { clip ->
                val current = clip.effects.filter
                val intensity = when {
                    presetId == FilterCatalog.ORIGINAL_ID -> 0.0
                    current.presetId == presetId && current.intensity > 0.0 -> current.intensity
                    else -> 1.0
                }
                clip.copy(effects = clip.effects.copy(filter = FilterSelection(presetId, intensity)))
            }
        }
    }

    fun changeFilterIntensity(display: Float) = updateGesture { ready, project ->
        Edit.updateClip(project, ready.clip.id) { clip ->
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
        if (thumbnailClipSource == ready.clip.source && thumbnailJob != null) return
        thumbnailJob?.cancel()
        thumbnailClipSource = ready.clip.source
        _filterThumbnails.value = emptyMap()
        thumbnailJob = viewModelScope.launch {
            val frame = frames.frame(ready.location, ready.clip.source.value, ready.clip.sourceRange.startUs, FILTER_THUMBNAIL_PX)
            if (frame == null) {
                // 프레임을 못 읽었으면 다음에 필터 도구를 열 때 다시 시도한다.
                thumbnailJob = null
                return@launch
            }
            FilterCatalog.all.forEach { preset ->
                // 썸네일 하나를 못 만들어도(메모리·GL 오류) 나머지는 계속 만든다.
                val thumbnail = withContext(Dispatchers.Default) {
                    try {
                        frame.copy(Bitmap.Config.ARGB_8888, true)?.also {
                            colorRenderer.apply(it, ColorEffectSpec.of(Adjustments(), FilterSelection(preset.id, 1.0), 0L), includeCanvasEffects = false)
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        null
                    } catch (_: OutOfMemoryError) {
                        null
                    }
                } ?: return@forEach
                _filterThumbnails.update { it + (preset.id to thumbnail) }
            }
        }
    }

    // ---- 속도·소리 ----

    /** 선택한 클립에 [speed]를 적용해도 전체 결과가 설정된 길이 제한 안에 있는지 확인한다. */
    fun speedAllowed(speed: Double): SpeedCheck {
        val ready = _state.value as? VideoEditorUiState.Ready ?: return SpeedCheck.OK
        val clipOutput = ready.clip.sourceRange.durationUs / speed
        val total = ready.durationUs - ready.clip.outputDurationUs + clipOutput
        return when {
            total > config.maxTimelineDurationUs -> SpeedCheck.TOO_LONG
            clipOutput < config.minClipDurationUs -> SpeedCheck.TOO_SHORT
            else -> SpeedCheck.OK
        }
    }

    /** 속도 도구 안에서 고른 배속. 적용하기 전까지는 초안이다. */
    fun selectSpeed(speed: Double) {
        when (speedAllowed(speed)) {
            SpeedCheck.TOO_LONG -> return updateReady { it.copy(notice = VideoNotice.SPEED_TOO_LONG) }
            SpeedCheck.TOO_SHORT -> return updateReady { it.copy(notice = VideoNotice.SPEED_TOO_SHORT) }
            SpeedCheck.OK -> Unit
        }
        val ready = _state.value as? VideoEditorUiState.Ready ?: return
        if (ready.activeTool == VideoTool.SPEED) {
            pendingSeekUs = ready.clipStartUs
            updateDraftClip { _, clip -> clip.copy(speed = speed) }
        }
    }

    fun setMuted(muted: Boolean) {
        val id = (_state.value as? VideoEditorUiState.Ready)?.clip?.id ?: return
        commitImmediate { Edit.updateClip(it, id) { c -> c.copy(muted = muted) } }
    }

    /** 원본 소리 볼륨 슬라이더(퍼센트), `0..200`. */
    fun changeVolume(percent: Float) = updateGesture { ready, project ->
        Edit.updateClip(project, ready.clip.id) { it.copy(volume = (percent / 100.0).coerceIn(0.0, VideoClip.MAX_VOLUME), muted = false) }
    }

    /** 배경 음악을 고른다. 이미 있으면 바꾼다. 재생 위치에서 시작한다. */
    fun addMusic(uri: Uri?) {
        val ready = _state.value as? VideoEditorUiState.Ready ?: return
        if (uri == null || ready.busy || ready.transaction.isActive) return
        updateReady { it.copy(busy = true) }
        viewModelScope.launch {
            val opened = withContext(ioDispatcher) {
                try {
                    openAudio(EditorInput.UriSource(uri), describe(uri))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    logFailure("music", error as? FrameKitException ?: FrameKitException(EditorErrorCode.DECODE_FAILED, cause = error))
                    null
                }
            }
            if (opened == null) return@launch updateReady { it.copy(busy = false, notice = VideoNotice.ADD_FAILED) }
            val (id, loaded) = opened
            session?.addAudio(id, loaded.reference)
            updateReady { it.copy(music = it.music + (id to loaded), busy = false) }
            val start = engine.state.value.positionUs.coerceAtLeast(0)
            val clip = AudioClip(newId(), id, TimeRangeUs(0, loaded.info.metadata.durationUs ?: 0L), timelineStartUs = start)
            commitImmediate { Edit.clampTimed(Edit.setMusic(it, clip)) }
        }
    }

    fun removeMusic() = commitImmediate { Edit.setMusic(it, null) }

    fun setMusicLoop(loop: Boolean) = commitImmediate { p -> Edit.updateMusic(p) { it.copy(loop = loop) } }

    /** 배경 음악이 재생 위치에서 시작하게 한다. */
    fun startMusicHere() {
        val position = engine.state.value.positionUs
        commitImmediate { p -> Edit.clampTimed(Edit.updateMusic(p) { it.copy(timelineStartUs = position.coerceAtLeast(0)) }) }
    }

    /** 배경 음악 볼륨 슬라이더(퍼센트), `0..200`. */
    fun changeMusicVolume(percent: Float) = updateGesture { _, project ->
        Edit.updateMusic(project) { it.copy(volume = (percent / 100.0).coerceIn(0.0, VideoClip.MAX_VOLUME)) }
    }

    /** 곡의 어느 지점부터 쓸지(원본 시간 µs). 곡 끝에서 1초는 남긴다. */
    fun changeMusicOffset(offsetUs: Long) = updateGesture { ready, project ->
        Edit.updateMusic(project) { clip ->
            val total = ready.music[clip.source]?.info?.metadata?.durationUs ?: clip.sourceRange.endExclusiveUs
            val start = offsetUs.coerceIn(0, max(0, total - MIN_MUSIC_US))
            clip.copy(sourceRange = TimeRangeUs(start, total))
        }
    }

    // ---- 텍스트·스티커 ----

    private fun openText(existingId: String?) {
        updateReady { ready ->
            val begun = ready.transaction.begin()
            val draft = checkNotNull(begun.draft)
            val id = existingId ?: newId()
            val project = if (existingId != null) {
                draft
            } else {
                val range = Edit.defaultRange(engine.state.value.positionUs, ready.durationUs, DEFAULT_ITEM_US)
                draft.copy(timeline = draft.timeline.copy(overlays = draft.timeline.overlays + TimedOverlay(ImageOverlay.Text(id, ""), range)))
            }
            ready.copy(activeTool = VideoTool.TEXT, transaction = begun.update(project), editingTextId = id, selectedOverlayId = id)
        }
    }

    /** 기존 텍스트를 텍스트 도구로 연다. */
    override fun editText(id: String) {
        val ready = _state.value as? VideoEditorUiState.Ready ?: return
        val canEdit = (ready.activeTool == null || ready.activeTool == VideoTool.STICKER) && ready.export == null &&
            !ready.transaction.isActive && VideoTool.TEXT in config.enabledTools
        if (!canEdit || ready.displayed.timeline.overlays.none { it.overlay.id == id && it.overlay is ImageOverlay.Text }) return
        engine.pause()
        openText(id)
    }

    fun updateText(text: String) = updateEditingText { it.copy(text = text) }

    fun updateTextStyle(transform: (TextStyleSpec) -> TextStyleSpec) = updateEditingText { it.copy(style = transform(it.style)) }

    fun updateTextOpacity(opacity: Double) = updateEditingText { it.copy(transform = it.transform.copy(opacity = opacity.coerceIn(0.0, 1.0))) }

    private fun updateEditingText(transform: (ImageOverlay.Text) -> ImageOverlay.Text) = updateReady { ready ->
        val id = ready.editingTextId ?: return@updateReady ready
        val draft = ready.transaction.draft ?: return@updateReady ready
        val text = draft.timeline.overlays.firstOrNull { it.overlay.id == id }?.overlay as? ImageOverlay.Text ?: return@updateReady ready
        ready.copy(transaction = ready.transaction.update(Edit.replaceOverlay(draft, transform(text))))
    }

    fun selectStickerCategory(category: EmojiCatalog.Category?) = updateReady { it.copy(stickerCategory = category) }

    /** 스티커(이모지나 호스트 이미지의 에셋 id)를 화면 가운데에 재생 위치부터 3초 동안 붙이고 고른다. 한 단계다. */
    fun addSticker(assetId: String) {
        val ready = _state.value as? VideoEditorUiState.Ready ?: return
        val id = newId()
        val range = Edit.defaultRange(engine.state.value.positionUs, ready.durationUs, DEFAULT_ITEM_US)
        commitImmediate { it.copy(timeline = it.timeline.copy(overlays = it.timeline.overlays + TimedOverlay(ImageOverlay.Sticker(id, assetId), range))) }
        updateReady { it.copy(selectedOverlayId = id) }
    }

    override fun selectOverlay(id: String?) = updateReady { it.copy(selectedOverlayId = id) }

    fun deleteOverlay(id: String) {
        commitImmediate { Edit.removeOverlay(it, id) }
        updateReady { if (it.selectedOverlayId == id) it.copy(selectedOverlayId = null) else it }
    }

    override fun beginOverlayGesture(id: String) {
        val ready = _state.value as? VideoEditorUiState.Ready ?: return
        overlayGestureStart = ready.displayed.timeline.overlays.firstOrNull { it.overlay.id == id }?.overlay
        updateReady { it.copy(selectedOverlayId = id) }
    }

    /** 이동은 정규화 캔버스 단위, 확대는 배율, 회전은 도 단위이며 모두 제스처를 시작한 뒤의 변화량이다. */
    override fun updateOverlayGesture(panX: Double, panY: Double, zoom: Double, rotation: Double, snapX: Double, snapY: Double) {
        val start = overlayGestureStart ?: return
        val moved = start.withTransform(OverlayGestures.transform(start.transform, panX, panY, zoom, rotation, snapX, snapY))
        val ready = _state.value as? VideoEditorUiState.Ready ?: return
        if (ready.activeTool == VideoTool.TEXT) {
            updateReady { r -> r.transaction.draft?.let { r.copy(transaction = r.transaction.update(Edit.replaceOverlay(it, moved))) } ?: r }
        } else {
            updateGesture { _, project -> Edit.replaceOverlay(project, moved) }
        }
    }

    override fun finishOverlayGesture() {
        overlayGestureStart = null
        finishGesture()
    }

    // ---- 모자이크 ----

    /** 다음 마스크의 설정. 기존 마스크는 효과를 그대로 유지한다. */
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
        val mask = TimedPrivacyMask(PrivacyMask(id, shape, ready.privacy.effect()), Edit.defaultRange(engine.state.value.positionUs, ready.durationUs, DEFAULT_ITEM_US))
        updateGesture { _, project -> project.copy(timeline = project.timeline.copy(privacyMasks = project.timeline.privacyMasks + mask)) }
        updateReady { it.copy(selectedMaskId = id) }
    }

    override fun extendMask(x: Double, y: Double) {
        val anchor = maskAnchor ?: return
        val id = (_state.value as? VideoEditorUiState.Ready)?.selectedMaskId ?: return
        val rect = RectN(min(anchor.x, x), min(anchor.y, y), max(anchor.x, x), max(anchor.y, y))
        updateGesture { _, project ->
            Edit.updateMask(project, id) { timed ->
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

    /** 마스크의 시작(또는 끝)을 재생 위치로 옮긴다. 최소 한 프레임은 남긴다. */
    fun setMaskEdge(id: String, start: Boolean) {
        val position = engine.state.value.positionUs
        commitImmediate { project ->
            val duration = TimelineTimeMapper.durationUs(project.timeline)
            Edit.updateMask(project, id) { it.copy(range = Edit.moveEdge(it.range, position, start, duration, FRAME_US)) }
        }
    }

    /** 텍스트·스티커의 시작(또는 끝)을 재생 위치로 옮긴다. 최소 한 프레임은 남긴다. */
    fun setOverlayEdge(id: String, start: Boolean) {
        val position = engine.state.value.positionUs
        commitImmediate { project ->
            val duration = TimelineTimeMapper.durationUs(project.timeline)
            Edit.updateOverlay(project, id) { it.copy(range = Edit.moveEdge(it.range, position, start, duration, FRAME_US)) }
        }
    }

    // ---- 기록·저장·종료 ----

    fun undo() = updateReady { ready ->
        if (!config.allowUndo || ready.export != null) ready else ready.copy(transaction = ready.transaction.undo()).fixSelection()
    }

    fun redo() = updateReady { ready ->
        if (!config.allowRedo || ready.export != null) ready else ready.copy(transaction = ready.transaction.redo()).fixSelection()
    }

    // 실행 취소로 선택한 클립이 사라지면 첫 클립을 고른다.
    private fun VideoEditorUiState.Ready.fixSelection(): VideoEditorUiState.Ready =
        if (clips.any { it.id == selectedClipId }) this else copy(selectedClipId = clips.first().id)

    fun save() {
        // 열린 도구의 초안은 저장 전에 적용한다. 사용자가 적용 버튼을 따로 누르지 않아도 보이는 그대로 저장된다.
        if ((_state.value as? VideoEditorUiState.Ready)?.activeTool?.isDraft == true) applyTool()
        val ready = _state.value as? VideoEditorUiState.Ready ?: return
        if (ready.activeTool?.isDraft == true || ready.transaction.isActive) {
            updateReady { it.copy(showApplyHint = true) }
            return
        }
        // 결과를 이미 보냈거나 저장이 진행 중이면 두 번째 저장 요청은 무시한다.
        if (_result.value != null || ready.export != null || exportJob?.isActive == true || ready.busy) return
        engine.pause()
        val snapshot = ready.transaction.history.current
        updateReady { it.copy(export = ExportUiState.Running(ExportStageUi.PREPARING)) }
        exportJob = viewModelScope.launch {
            try {
                session?.markExport(snapshot, running = true)
                val media = exporter.export(
                    snapshot,
                    ready.sources.mapValues { it.value.info },
                    ready.sources.mapValues { it.value.location } + ready.music.mapValues { it.value.location },
                    ready.music.mapValues { it.value.info },
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

    /** 닫기 버튼이나 시스템 뒤로 가기. */
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

    /** 시스템 뒤로 가기. 열린 도구를 먼저 닫고, 그다음은 [requestClose]와 같다. */
    fun onBack() {
        val ready = _state.value as? VideoEditorUiState.Ready
        when {
            ready == null || ready.activeTool == null || ready.export != null -> requestClose()
            // 바꾼 내용이 있으면 바로 버리지 않고 적용할지 묻는다.
            ready.activeTool.isDraft && ready.hasDraftChanges -> updateReady { it.copy(showDraftDialog = true) }
            ready.activeTool.isDraft -> cancelTool()
            else -> closeTool()
        }
    }

    fun applyDraftFromDialog() {
        updateReady { it.copy(showDraftDialog = false) }
        applyTool()
    }

    fun discardDraftFromDialog() {
        updateReady { it.copy(showDraftDialog = false) }
        cancelTool()
    }

    fun dismissDraftDialog() = updateReady { it.copy(showDraftDialog = false) }

    fun confirmDiscard() = finish(FrameKitResult.Cancelled)

    fun dismissDiscard() = updateReady { it.copy(showDiscardDialog = false) }

    private fun finish(result: FrameKitResult) {
        if (_result.value != null) return
        engine.pause()
        pendingRestore?.let { session?.discard(it) }
        session?.end()
        // 카메라로 찍은 임시 원본은 결과를 만든 뒤에는 필요 없다.
        CaptureFiles.delete(savedState.get<String>(KEY_CAPTURE_PATH))
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
        ready.copy(transaction = ready.transaction.update(Edit.updateClip(draft, ready.clip.id) { transform(ready, it) }))
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
        const val KEY_PICKED_URIS = "framekit_picked_video_uris"
        const val KEY_CAPTURE_PATH = "framekit_capture_video_path"
        const val PREVIEW_DEBOUNCE_MILLIS = 120L
        const val SNAPSHOT_DEBOUNCE_MILLIS = 300L
        const val PREVIEW_SHORT_SIDE = 720
        const val FILTER_THUMBNAIL_PX = 160
        const val FRAME_US = 33_333L
        const val DEFAULT_ITEM_US = 3_000_000L
        const val MIN_MUSIC_US = 1_000_000L
        const val MIN_MASK_SIZE = 0.01
    }
}

internal enum class TrimEdge { START, END }

internal enum class SpeedCheck { OK, TOO_LONG, TOO_SHORT }

/** 영상 미리보기 위의 제스처. */
internal interface VideoCanvasActions {
    fun beginCropDrag(handle: CropHandle)
    fun dragCrop(dx: Double, dy: Double)
    fun endCropDrag()
    fun beginMask(x: Double, y: Double)
    fun extendMask(x: Double, y: Double)
    fun finishMask()
    fun selectMask(id: String?)
    fun selectOverlay(id: String?)
    fun editText(id: String)
    fun beginOverlayGesture(id: String)
    fun updateOverlayGesture(panX: Double, panY: Double, zoom: Double, rotation: Double, snapX: Double, snapY: Double)
    fun finishOverlayGesture()
}
