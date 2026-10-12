package com.naury.framekit.ui.motion.editor

import com.naury.framekit.video.audio.Waveform
import com.naury.framekit.core.video.AudioEditing
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import java.io.File
import com.naury.framekit.motion.model.MotionAudio
import com.naury.framekit.core.video.AudioRole
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.core.graphics.scale
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.naury.framekit.android.input.EditorInput
import com.naury.framekit.android.result.EditorErrorCode
import com.naury.framekit.android.result.FrameKitException
import com.naury.framekit.android.result.FrameKitResult
import com.naury.framekit.android.source.SessionSourceRegistry
import com.naury.framekit.core.effect.AdjustmentKind
import com.naury.framekit.core.effect.Adjustments
import com.naury.framekit.core.effect.ColorEffectSpec
import com.naury.framekit.core.effect.FilterCatalog
import com.naury.framekit.core.effect.FilterSelection
import com.naury.framekit.core.geometry.PointN
import com.naury.framekit.core.history.HistoryTransaction
import com.naury.framekit.core.model.PixelSize
import com.naury.framekit.core.model.SourceId
import com.naury.framekit.core.overlay.EmojiCatalog
import com.naury.framekit.core.overlay.ImageOverlay
import com.naury.framekit.core.overlay.OverlayTransform
import com.naury.framekit.core.overlay.TextStyleSpec
import com.naury.framekit.image.effect.ColorEffectRenderer
import com.naury.framekit.image.overlay.OverlayRenderer
import com.naury.framekit.motion.codec.AnimatedFrames
import com.naury.framekit.motion.export.MotionExportSettings
import com.naury.framekit.motion.export.MotionExporter
import com.naury.framekit.motion.export.MotionFormat
import com.naury.framekit.motion.export.MotionSize
import com.naury.framekit.motion.model.MotionKind
import com.naury.framekit.motion.model.MotionOverlay
import com.naury.framekit.motion.model.MotionProject
import com.naury.framekit.motion.model.OverlayAnimation
import com.naury.framekit.motion.render.MotionMedia
import com.naury.framekit.motion.render.MotionRenderer
import com.naury.framekit.motion.render.MotionTimeline
import com.naury.framekit.motion.source.MotionSourceLoader
import com.naury.framekit.motion.source.MotionSourceType
import com.naury.framekit.ui.motion.contract.MotionEditorRequest
import com.naury.framekit.ui.motion.contract.MotionTool
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.math.roundToLong

/**
 * 움직이는 이미지 편집기의 상태와 동작. 미리보기는 [MotionRenderer]로 저장과 같은 방식으로 그린다.
 *
 * 편집 도구는 모두 바로 기록하고(실행 취소 한 단계), 도구를 열 때의 상태를 기억해 두었다가 뒤로 가면 적용할지 묻는다.
 * 텍스트만 초안으로 편집하고 적용할 때 기록한다.
 */
/** 소리 조각을 끄는 방식. */
internal enum class MotionAudioDrag { MOVE, START, END }

internal class MotionEditorViewModel(
    private val request: MotionEditorRequest,
    private val savedState: SavedStateHandle,
    private val registry: SessionSourceRegistry,
    private val loader: MotionSourceLoader,
    private val exporter: MotionExporter,
    private val colorRenderer: ColorEffectRenderer,
    private val ioDispatcher: CoroutineDispatcher,
    private val renderDispatcher: CoroutineDispatcher,
    private val memoryBudgetBytes: Long,
    private val previewLongEdge: Int = PREVIEW_LONG_EDGE,
    private val audioPlayer: MotionAudioPlayer? = null,
    private val describeAudio: (EditorInput) -> MotionAudioSource? = { null },
    private val waveformLoader: (Uri) -> Waveform? = { null },
) : ViewModel() {

    private val _waveforms = MutableStateFlow<Map<String, Waveform>>(emptyMap())

    /** 소리 원본 id별 파형. 소리 줄에 그린다. */
    val waveforms: StateFlow<Map<String, Waveform>> = _waveforms.asStateFlow()
    private var audioDrag: Pair<MotionAudioDrag, MotionAudio>? = null

    private val _state = MutableStateFlow<MotionEditorUiState>(MotionEditorUiState.Loading)
    val state: StateFlow<MotionEditorUiState> = _state.asStateFlow()

    private val _result = MutableStateFlow<FrameKitResult?>(null)

    /** 최종 결과. `null`이 아니게 되면 Activity가 끝난다. */
    val result: StateFlow<FrameKitResult?> = _result.asStateFlow()

    private val _frame = MutableStateFlow<Bitmap?>(null)

    /** 지금 미리보기 프레임. */
    val frame: StateFlow<Bitmap?> = _frame.asStateFlow()

    private val _thumbnails = MutableStateFlow<Map<String, Bitmap>>(emptyMap())

    /** 사진 id별 작은 미리보기(슬라이드 목록). */
    val thumbnails: StateFlow<Map<String, Bitmap>> = _thumbnails.asStateFlow()

    private val _filterThumbnails = MutableStateFlow<Map<String, Bitmap>>(emptyMap())

    /** 필터 id별 미리보기. */
    val filterThumbnails: StateFlow<Map<String, Bitmap>> = _filterThumbnails.asStateFlow()

    val config get() = request.config
    val exportConfig get() = request.export

    private val renderer = MotionRenderer(colorRenderer)
    private val overlayRenderer = OverlayRenderer()
    private val aspects = mutableMapOf<String, Double>()
    private val previewSlides = SlideCache(loader, previewLongEdge, PREVIEW_CACHE)
    private var animation: AnimatedFrames? = null
    private var viewport: PixelSize? = null
    private var playbackJob: Job? = null
    private var renderJob: Job? = null
    private var exportJob: Job? = null
    private var filterJob: Job? = null
    // 화면에 걸려 있을 수 있는 비트맵을 덮어쓰지 않도록 세 장을 돌려 쓴다.
    private val buffers = arrayOfNulls<Bitmap>(3)
    private var bufferIndex = 0
    private var playStartedAt = 0L
    private var playStartPosition = 0L
    private var toolEntry: MotionProject? = null
    private var gestureStart: ImageOverlay? = null

    init {
        val saved = savedState.get<ArrayList<EditorInput>>(KEY_INPUTS)
        val sessionInputs = savedState.get<ArrayList<EditorInput>>(KEY_SESSION_INPUTS)
        val snapshot = savedState.get<String>(KEY_SESSION)?.let(MotionSessionSnapshot::decode)
        when {
            saved != null && sessionInputs != null && snapshot != null -> restore(sessionInputs, snapshot)
            saved != null -> load(saved)
            request.input is EditorInput.Pick -> _state.value = MotionEditorUiState.AwaitingSource((request.input as EditorInput.Pick).maxItems)
            request.input is EditorInput.Multiple -> load((request.input as EditorInput.Multiple).items)
            else -> load(listOf(request.input))
        }
        // 편집이 기록될 때마다 저장해, 프로세스가 끝났다가 돌아와도 이어서 편집할 수 있게 한다.
        viewModelScope.launch {
            state.map { (it as? MotionEditorUiState.Ready)?.transaction?.history?.current }
                .filterNotNull()
                .distinctUntilChanged()
                .collect(::saveSession)
        }
    }

    private fun saveSession(project: MotionProject) {
        runCatching {
            val (ids, snapshot) = MotionSessionSnapshot.of(project)
            savedState[KEY_SESSION_INPUTS] = ArrayList(ids.map { registry.describe(SourceId(it)) })
            savedState[KEY_SESSION] = snapshot.encode()
        }.onFailure { Log.w(TAG, "session save failed", it) }
    }

    // ---- 원본 ----

    /** 사진 선택기 결과. 빈 목록이면 사용자가 닫은 것이다. */
    fun onPicked(uris: List<Uri>) {
        if (uris.isEmpty()) return finish(FrameKitResult.Cancelled)
        load(uris.take(config.maxImageCount).map(EditorInput::UriSource))
    }

    fun chooseAnother() {
        savedState.remove<ArrayList<EditorInput>>(KEY_INPUTS)
        savedState.remove<String>(KEY_SESSION)
        savedState.remove<ArrayList<EditorInput>>(KEY_SESSION_INPUTS)
        _state.value = MotionEditorUiState.AwaitingSource(config.maxImageCount)
    }

    // 저장해 둔 편집 내용으로 다시 연다. 원본을 다시 등록해 새 id를 받고, 소리 원본 정보도 다시 읽는다.
    private fun restore(inputs: List<EditorInput>, snapshot: MotionSessionSnapshot) {
        _state.value = MotionEditorUiState.Loading
        viewModelScope.launch {
            try {
                val ready = withContext(ioDispatcher) {
                    val ids = inputs.map { registry.register(it).value }
                    val project = snapshot.toProject(ids)
                    val base = prepareIds(project.sourceIds.map(::SourceId), project.kind)
                    val sources = project.audio.associate { audio -> audio.sourceId to (describeAudio(inputs[ids.indexOf(audio.sourceId)]) ?: MotionAudioSource(null, audio.sourceDurationMs)) }
                    base.copy(transaction = HistoryTransaction.start(project), audioSources = sources, notice = MotionNotice.RESTORED)
                }
                _state.value = ready
                if (ready.displayed.kind == MotionKind.SLIDESHOW) loadThumbnails(ready.displayed.sourceIds)
                ready.displayed.audio.map { it.sourceId }.distinct().forEach(::loadWaveform)
                startPlayback()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                // 이어 오지 못하면 처음 고른 원본으로 새로 연다.
                Log.w(TAG, "restore failed", error)
                savedState.remove<String>(KEY_SESSION)
                load(savedState.get<ArrayList<EditorInput>>(KEY_INPUTS).orEmpty())
            }
        }
    }

    private fun load(inputs: List<EditorInput>) {
        savedState[KEY_INPUTS] = ArrayList(inputs)
        _state.value = MotionEditorUiState.Loading
        viewModelScope.launch {
            try {
                val ready = withContext(ioDispatcher) { prepare(inputs) }
                _state.value = ready
                if (ready.displayed.kind == MotionKind.SLIDESHOW) loadThumbnails(ready.displayed.sourceIds)
                startPlayback()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: FrameKitException) {
                Log.w(TAG, "load failed: ${error.code}")
                _state.value = MotionEditorUiState.LoadFailed(error.code, request.input is EditorInput.Pick)
            } catch (error: OutOfMemoryError) {
                _state.value = MotionEditorUiState.LoadFailed(EditorErrorCode.INSUFFICIENT_MEMORY, request.input is EditorInput.Pick)
            } catch (error: Exception) {
                Log.w(TAG, "load failed", error)
                _state.value = MotionEditorUiState.LoadFailed(EditorErrorCode.DECODE_FAILED, request.input is EditorInput.Pick)
            }
        }
    }

    // 하나뿐인 원본이 움직이면 그 원본을 편집하고, 아니면 사진(움직이는 원본은 첫 프레임)으로 슬라이드쇼를 만든다.
    private fun prepare(inputs: List<EditorInput>): MotionEditorUiState.Ready = prepareIds(inputs.map { registry.register(it) }, kind = null)

    // kind가 있으면(이어 열기) 그 종류로 연다. 없으면 원본을 보고 정한다.
    private fun prepareIds(ids: List<SourceId>, kind: MotionKind?): MotionEditorUiState.Ready {
        val single = ids.singleOrNull()
        val type = single?.takeIf { kind != MotionKind.SLIDESHOW }?.let { loader.probe(it) }
        val project: MotionProject
        var sourceDuration = 0L
        var notice: MotionNotice? = null
        if (single != null && type != null && type != MotionSourceType.STILL) {
            val frames = loader.decodeAnimation(single, previewLongEdge, config.maxAnimationFrames, (memoryBudgetBytes / 3).coerceAtMost(MAX_ANIMATION_BYTES))
            animation = frames
            sourceDuration = frames.totalMs
            project = MotionProject(MotionKind.ANIMATION, listOf(single.value), loop = com.naury.framekit.motion.model.LoopMode.FORWARD)
            if (frames.frames.size >= config.maxAnimationFrames) notice = MotionNotice.FRAMES_TRIMMED
        } else {
            ids.forEach { id -> aspects[id.value] = loader.stillAspect(id) }
            project = MotionProject(MotionKind.SLIDESHOW, ids.map { it.value }, slideDurationMs = config.slideDurationMs)
        }
        return MotionEditorUiState.Ready(HistoryTransaction.start(project), sourceDurationMs = sourceDuration, notice = notice)
    }

    private fun loadThumbnails(ids: List<String>) {
        viewModelScope.launch(ioDispatcher) {
            ids.filter { it !in _thumbnails.value }.forEach { id ->
                val thumbnail = runCatching { loader.decodeStill(SourceId(id), THUMBNAIL_PX) }.getOrNull() ?: return@forEach
                _thumbnails.update { it + (id to thumbnail) }
            }
        }
    }

    /** 미리보기와 저장이 함께 쓰는 원본 묶음. 사진은 [slides]에서 필요할 때 푼다. */
    private fun media(project: MotionProject, slides: SlideCache): MotionMedia = MotionMedia(
        kind = project.kind,
        slideAspects = if (project.kind == MotionKind.SLIDESHOW) project.sourceIds.map { aspects[it] ?: 1.0 } else emptyList(),
        slide = { index -> slides.get(project.sourceIds[index]) },
        animation = animation,
    )

    // ---- 미리보기 ----

    /** 캔버스가 픽셀 크기와 함께 부른다. 미리보기는 이 크기 안에 맞춰 그린다. */
    fun onViewport(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        viewport = PixelSize(width, height)
        requestRender()
    }

    fun togglePlayback() {
        val ready = ready() ?: return
        if (ready.playing) pause() else startPlayback()
    }

    /** 재생 위치를 옮긴다. 옮기는 동안은 멈춘다. */
    fun seekTo(positionMs: Long) {
        val ready = ready() ?: return
        playbackJob?.cancel()
        audioPlayer?.pauseAll()
        val duration = MotionTimeline.durationMs(ready.displayed, media(ready.displayed, previewSlides))
        updateReady { it.copy(playing = false, positionMs = positionMs.coerceIn(0, duration)) }
        requestRender()
    }

    private fun pause() {
        playbackJob?.cancel()
        audioPlayer?.pauseAll()
        updateReady { it.copy(playing = false) }
        requestRender()
    }

    private fun startPlayback() {
        val ready = ready() ?: return
        // 텍스트·스티커 위치를 고치는 동안에는 움직이지 않게 멈춰 둔다.
        if (ready.activeTool == MotionTool.TEXT || ready.activeTool == MotionTool.STICKER) return requestRender()
        playbackJob?.cancel()
        updateReady { it.copy(playing = true) }
        playStartedAt = SystemClock.elapsedRealtime()
        playStartPosition = ready.positionMs
        playbackJob = viewModelScope.launch {
            while (isActive) {
                val current = ready() ?: break
                val media = media(current.displayed, previewSlides)
                val duration = MotionTimeline.durationMs(current.displayed, media).coerceAtLeast(1)
                val position = (playStartPosition + SystemClock.elapsedRealtime() - playStartedAt) % duration
                val started = SystemClock.elapsedRealtime()
                updateReady { it.copy(positionMs = position) }
                audioPlayer?.sync(current.displayed.audio, position, duration, playing = true)
                renderNow(current.displayed, position, animate = true)
                delay((FRAME_MS - (SystemClock.elapsedRealtime() - started)).coerceAtLeast(1))
            }
        }
    }

    private fun requestRender() {
        val ready = ready() ?: return
        if (ready.playing && playbackJob?.isActive == true) return
        renderJob?.cancel()
        val animate = ready.activeTool != MotionTool.TEXT && ready.activeTool != MotionTool.STICKER
        renderJob = viewModelScope.launch { renderNow(ready.displayed, ready.positionMs, animate) }
    }

    private suspend fun renderNow(project: MotionProject, positionMs: Long, animate: Boolean) {
        val view = viewport ?: return
        val bitmap = try {
            withContext(renderDispatcher) {
                val media = media(project, previewSlides)
                val size = previewSize(project, media, view)
                val index = (bufferIndex + 1) % buffers.size
                val drawn = renderer.render(project, media, positionMs, size.width, size.height, buffers[index], animateOverlays = animate)
                buffers[index] = drawn
                bufferIndex = index
                prefetchNextSlide(project, media, positionMs)
                drawn
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w(TAG, "preview failed", error)
            return
        } catch (_: OutOfMemoryError) {
            return
        }
        _frame.value = bitmap
    }

    // 미리보기는 화면 크기 안에서 결과 비율을 지키고, 너무 크게 그리지 않는다.
    private fun previewSize(project: MotionProject, media: MotionMedia, view: PixelSize): PixelSize {
        val full = renderer.outputSize(project, media, previewLongEdge)
        val scale = minOf(1.0, view.width.toDouble() / full.width, view.height.toDouble() / full.height)
        return PixelSize(maxOf(2, (full.width * scale).toInt()), maxOf(2, (full.height * scale).toInt()))
    }

    // 다음 사진을 미리 풀어 두어 전환 때 멈칫하지 않게 한다.
    private fun prefetchNextSlide(project: MotionProject, media: MotionMedia, positionMs: Long) {
        if (project.kind != MotionKind.SLIDESHOW || media.slideCount < 2) return
        val next = project.sourceIds.getOrNull(MotionTimeline.slideAt(project, media, positionMs).index + 1) ?: return
        if (previewSlides.contains(next)) return
        viewModelScope.launch(ioDispatcher) { runCatching { previewSlides.get(next) } }
    }

    // ---- 도구 ----

    fun selectTool(tool: MotionTool) {
        val ready = ready() ?: return
        if (ready.activeTool != null || ready.export != null || ready.transaction.isActive) return
        if (tool == MotionTool.TEXT) return openText(existingId = null)
        toolEntry = ready.transaction.history.current
        updateReady { it.copy(activeTool = tool, selectedOverlayId = if (tool == MotionTool.STICKER) it.selectedOverlayId else null) }
        if (tool == MotionTool.STICKER) pause() else requestRender()
        if (tool == MotionTool.FILTER) ensureFilterThumbnails()
    }

    /** 적용. 텍스트는 초안을 기록하고, 나머지는 이미 기록된 채로 도구를 닫는다. */
    fun applyTool() {
        val ready = ready() ?: return
        if (ready.activeTool == MotionTool.TEXT) {
            val id = ready.editingTextId
            var transaction = ready.transaction
            val draft = transaction.draft
            if (draft != null && id != null) {
                val text = draft.overlays.firstOrNull { it.overlay.id == id }?.overlay as? ImageOverlay.Text
                if (text != null && text.text.isBlank()) transaction = transaction.update(draft.copy(overlays = draft.overlays.filterNot { it.overlay.id == id }))
            }
            updateReady { it.copy(transaction = transaction.commit(), activeTool = null, editingTextId = null) }
        } else {
            if (ready.transaction.isActive) updateReady { it.copy(transaction = it.transaction.commit()) }
            updateReady { it.copy(activeTool = null) }
        }
        toolEntry = null
        startPlayback()
    }

    /** 뒤로 가기. 바뀐 내용이 있으면 적용할지 묻는다. */
    fun onBack() {
        val ready = ready() ?: return requestClose()
        when {
            ready.export is MotionExportUiState.Running -> cancelExport()
            ready.showSaveSheet -> dismissSaveSheet()
            ready.activeTool == null -> requestClose()
            toolChanged(ready) -> updateReady { it.copy(showDraftDialog = true) }
            else -> revertTool()
        }
    }

    private fun toolChanged(ready: MotionEditorUiState.Ready): Boolean {
        if (ready.activeTool == MotionTool.TEXT) {
            val draft = ready.transaction.draft ?: return false
            // 새로 연 텍스트를 비워 둔 채면 적용해도 빠지므로 바뀐 것으로 보지 않는다.
            val id = ready.editingTextId
            val blank = draft.overlays.firstOrNull { it.overlay.id == id }?.overlay.let { it is ImageOverlay.Text && it.text.isBlank() }
            val isNew = ready.transaction.history.current.overlays.none { it.overlay.id == id }
            val compared = if (blank && isNew) draft.copy(overlays = draft.overlays.filterNot { it.overlay.id == id }) else draft
            return !compared.sameContentAs(ready.transaction.history.current)
        }
        val entry = toolEntry ?: return false
        return !ready.displayed.sameContentAs(entry)
    }

    fun applyDraftFromDialog() {
        updateReady { it.copy(showDraftDialog = false) }
        applyTool()
    }

    fun discardDraftFromDialog() {
        updateReady { it.copy(showDraftDialog = false) }
        revertTool()
    }

    fun dismissDraftDialog() = updateReady { it.copy(showDraftDialog = false) }

    // 도구를 열기 전 상태로 돌아간다. 그사이 기록된 단계는 실행 취소로 되돌려 다시 실행할 수 있게 둔다.
    private fun revertTool() {
        val ready = ready() ?: return
        var transaction = if (ready.transaction.isActive) ready.transaction.cancel() else ready.transaction
        val entry = toolEntry
        if (entry != null && ready.activeTool != MotionTool.TEXT) {
            while (transaction.history.canUndo && !transaction.history.current.sameContentAs(entry)) transaction = transaction.undo()
            if (!transaction.history.current.sameContentAs(entry)) transaction = transaction.update(entry).commit()
        }
        toolEntry = null
        updateReady { it.copy(transaction = transaction, activeTool = null, editingTextId = null) }
        startPlayback()
    }

    /** 도구 하단의 초기화. 열린 도구가 다루는 값만 처음으로 돌린다. */
    fun resetTool() {
        val defaults = MotionProject(MotionKind.SLIDESHOW, emptyList(), slideDurationMs = config.slideDurationMs)
        commit { project ->
            when (ready()?.activeTool) {
                MotionTool.MOTION -> project.copy(cameraMotion = defaults.cameraMotion)
                MotionTool.TRANSITION -> project.copy(transition = defaults.transition, transitionMs = defaults.transitionMs)
                MotionTool.TIMING -> project.copy(slideDurationMs = defaults.slideDurationMs, speed = 1.0, trimStartMs = 0, trimEndMs = null)
                MotionTool.LOOP -> project.copy(loop = defaults.loop)
                MotionTool.CANVAS -> project.copy(aspect = defaults.aspect, fit = defaults.fit, backgroundArgb = defaults.backgroundArgb)
                MotionTool.ADJUST -> project.copy(adjustments = Adjustments())
                MotionTool.FILTER -> project.copy(filter = FilterSelection())
                MotionTool.TEXT, MotionTool.STICKER, MotionTool.AUDIO, null -> project
            }
        }
    }

    fun undo() = updateReady { it.copy(transaction = it.transaction.undo()) }.also { afterEdit() }

    fun redo() = updateReady { it.copy(transaction = it.transaction.redo()) }.also { afterEdit() }

    /** 실행 취소 한 단계로 바로 기록한다. */
    fun commit(transform: (MotionProject) -> MotionProject) {
        updateReady { ready ->
            if (ready.transaction.isActive || ready.export != null) return@updateReady ready
            val next = transform(ready.transaction.history.current)
            if (next.sameContentAs(ready.transaction.history.current)) ready else ready.copy(transaction = ready.transaction.begin().update(next).commit())
        }
        afterEdit()
    }

    /** 슬라이더를 끄는 동안의 변경. 첫 호출이 초안을 시작하고 [finishDrag]가 한 단계로 기록한다. */
    fun drag(transform: (MotionProject) -> MotionProject) {
        updateReady { ready ->
            if (ready.export != null) return@updateReady ready
            val begun = ready.transaction.begin()
            ready.copy(transaction = begun.update(transform(checkNotNull(begun.draft))))
        }
        afterEdit()
    }

    fun finishDrag() {
        updateReady { ready -> if (ready.transaction.isActive && ready.activeTool != MotionTool.TEXT) ready.copy(transaction = ready.transaction.commit()) else ready }
        afterEdit()
    }

    private fun afterEdit() {
        val ready = ready() ?: return
        val duration = MotionTimeline.durationMs(ready.displayed, media(ready.displayed, previewSlides))
        if (ready.positionMs > duration) updateReady { it.copy(positionMs = 0) }
        requestRender()
    }

    // ---- 보정·필터 ----

    fun selectAdjustment(kind: AdjustmentKind) = updateReady { it.copy(adjustKind = kind) }

    fun changeAdjustment(display: Float) {
        val kind = ready()?.adjustKind ?: return
        drag { it.copy(adjustments = it.adjustments.with(kind, kind.fromDisplay(display))) }
    }

    fun selectFilter(presetId: String) = commit { project ->
        val intensity = when {
            presetId == FilterCatalog.ORIGINAL_ID -> 0.0
            project.filter.presetId == presetId && project.filter.intensity > 0.0 -> project.filter.intensity
            else -> 1.0
        }
        project.copy(filter = FilterSelection(presetId, intensity))
    }

    fun changeFilterIntensity(display: Float) = drag { project ->
        if (project.filter.presetId == FilterCatalog.ORIGINAL_ID) project
        else project.copy(filter = project.filter.copy(intensity = (display.toDouble() / AdjustmentKind.DISPLAY_RANGE).coerceIn(0.0, 1.0)))
    }

    // 지금 미리보기의 작은 사본에 필터마다 색을 입혀 고르기 쉽게 한다.
    private fun ensureFilterThumbnails() {
        if (_filterThumbnails.value.isNotEmpty() || filterJob?.isActive == true) return
        val base = _frame.value ?: return
        filterJob = viewModelScope.launch {
            val small = withContext(renderDispatcher) {
                val ratio = FILTER_THUMBNAIL_PX.toDouble() / maxOf(base.width, base.height)
                runCatching { base.scale(maxOf(1, (base.width * ratio).toInt()), maxOf(1, (base.height * ratio).toInt())) }.getOrNull()
            } ?: return@launch
            FilterCatalog.all.forEach { preset ->
                val thumbnail = withContext(renderDispatcher) {
                    runCatching {
                        small.copy(Bitmap.Config.ARGB_8888, true).also { colorRenderer.apply(it, ColorEffectSpec.of(Adjustments(), FilterSelection(preset.id, 1.0), 0L), includeCanvasEffects = false) }
                    }.getOrNull()
                } ?: return@forEach
                _filterThumbnails.update { it + (preset.id to thumbnail) }
            }
        }
    }

    // ---- 슬라이드 ----

    fun selectSlide(index: Int) {
        val ready = ready() ?: return
        if (index !in ready.displayed.sourceIds.indices) return
        updateReady { it.copy(selectedSlide = index) }
        seekTo(index * ready.displayed.slideDurationMs)
    }

    fun moveSlide(delta: Int) {
        val ready = ready() ?: return
        val from = ready.selectedSlide
        val to = from + delta
        if (to !in ready.displayed.sourceIds.indices) return
        commit { project -> project.copy(sourceIds = project.sourceIds.toMutableList().apply { add(to, removeAt(from)) }) }
        updateReady { it.copy(selectedSlide = to) }
    }

    fun removeSlide() {
        val ready = ready() ?: return
        if (ready.displayed.sourceIds.size <= 1) return
        val index = ready.selectedSlide
        commit { project -> project.copy(sourceIds = project.sourceIds.filterIndexed { i, _ -> i != index }) }
        updateReady { it.copy(selectedSlide = index.coerceAtMost(it.displayed.sourceIds.lastIndex)) }
    }

    fun addSlides(uris: List<Uri>) {
        val ready = ready() ?: return
        if (uris.isEmpty() || ready.displayed.kind != MotionKind.SLIDESHOW) return
        val room = config.maxImageCount - ready.displayed.sourceIds.size
        if (room <= 0) return updateReady { it.copy(notice = MotionNotice.SLIDE_LIMIT) }
        viewModelScope.launch {
            val added = withContext(ioDispatcher) {
                uris.take(room).mapNotNull { uri ->
                    runCatching {
                        val id = registry.register(EditorInput.UriSource(uri))
                        aspects[id.value] = loader.stillAspect(id)
                        id.value
                    }.getOrNull()
                }
            }
            if (added.isEmpty()) return@launch
            commit { it.copy(sourceIds = it.sourceIds + added) }
            if (uris.size > room) updateReady { it.copy(notice = MotionNotice.SLIDE_LIMIT) }
            loadThumbnails(added)
        }
    }

    fun dismissNotice() = updateReady { it.copy(notice = null) }

    // ---- 배경 음악·음성 ----

    fun addMusic(uri: Uri?) {
        if (uri != null) addAudio(AudioRole.MUSIC, EditorInput.UriSource(uri))
    }

    fun addVoice(uri: Uri?) {
        if (uri != null) addAudio(AudioRole.VOICE, EditorInput.UriSource(uri))
    }

    /** 방금 녹음한 음성 파일을 넣는다. */
    fun addRecordedVoice(file: File) = addAudio(AudioRole.VOICE, EditorInput.FileSource(file.absolutePath))

    // 쓰임새마다 트랙은 하나다. 이미 있으면 바꾼다. 처음부터 시작하고, 배경 음악만 결과가 끝날 때까지 반복한다(음성은 한 번).
    private fun addAudio(role: AudioRole, input: EditorInput) {
        val ready = ready() ?: return
        if (ready.busy || ready.export != null) return
        updateReady { it.copy(busy = true) }
        viewModelScope.launch {
            val opened = withContext(ioDispatcher) {
                runCatching {
                    val id = registry.register(input).value
                    val source = describeAudio(input) ?: return@runCatching null
                    if (source.durationMs <= 0) null else id to source
                }.getOrNull()
            }
            if (opened == null) return@launch updateReady { it.copy(busy = false, notice = MotionNotice.AUDIO_FAILED) }
            val (id, source) = opened
            updateReady { it.copy(busy = false, audioSources = it.audioSources + (id to source)) }
            // 같은 줄에 조각을 더한다(바꾸지 않음). 지금 재생 위치에서 시작한다.
            val start = (ready()?.positionMs ?: 0L)
            val audio = MotionAudio(newId(), id, role, sourceStartMs = 0, sourceDurationMs = source.durationMs, timelineStartMs = start, loop = role == AudioRole.MUSIC)
            commit { project -> project.copy(audio = project.audio + audio) }
            updateReady { it.copy(selectedAudioId = audio.id) }
            loadWaveform(id)
        }
    }

    fun removeAudio(role: AudioRole) = commit { project -> project.copy(audio = project.audio.filter { it.role != role }) }

    fun setAudioLoop(role: AudioRole, loop: Boolean) = commit { project -> project.updateAudio(role) { it.copy(loop = loop) } }

    /** [role] 트랙이 지금 재생 위치에서 시작하게 한다. */
    fun startAudioHere(role: AudioRole) {
        val position = ready()?.positionMs ?: return
        commit { project -> project.updateAudio(role) { it.copy(timelineStartMs = position) } }
    }

    /** 볼륨 슬라이더(퍼센트, `0..200`). */
    fun changeAudioVolume(role: AudioRole, percent: Float) =
        drag { project -> project.updateAudio(role) { it.copy(volume = (percent / 100.0).coerceIn(0.0, MotionAudio.MAX_VOLUME)) } }

    /** 원본의 어디부터 쓸지(µs). 끝에서 1초는 남긴다. */
    fun changeAudioOffset(role: AudioRole, offsetUs: Long) = drag { project ->
        project.updateAudio(role) { it.copy(sourceStartMs = (offsetUs / 1000).coerceIn(0, (it.sourceDurationMs - MIN_AUDIO_MS).coerceAtLeast(0))) }
    }

    private fun MotionProject.updateAudio(role: AudioRole, transform: (MotionAudio) -> MotionAudio): MotionProject =
        copy(audio = audio.map { if (it.role == role) transform(it) else it })

    // ---- 소리 조각(타임라인) ----

    private fun loadWaveform(sourceId: String) {
        if (sourceId in _waveforms.value) return
        val uri = uriOf(sourceId) ?: return
        viewModelScope.launch(ioDispatcher) {
            runCatching { waveformLoader(uri) }.getOrNull()?.let { waveform -> _waveforms.update { it + (sourceId to waveform) } }
        }
    }

    /** 결과 한 번 재생 길이(µs). */
    private fun durationUs(project: MotionProject): Long = MotionTimeline.durationMs(project, media(project, previewSlides)) * 1000

    fun selectAudio(id: String?) = updateReady { it.copy(selectedAudioId = id) }

    /** 고른 조각을 재생 위치에서 둘로 나눈다. */
    fun splitSelectedAudio() {
        val ready = ready() ?: return
        val audio = ready.displayed.audio.firstOrNull { it.id == ready.selectedAudioId } ?: return
        val split = AudioEditing.split(audio.toAudioClip(), ready.positionMs * 1000, durationUs(ready.displayed), newId(), MIN_PIECE_US) ?: return
        val pieces = split.toList().map { MotionAudio.fromAudioClip(it, audio.sourceDurationMs) }
        commit { project -> project.copy(audio = project.audio.flatMap { if (it.id == audio.id) pieces else listOf(it) }) }
        updateReady { it.copy(selectedAudioId = pieces.last().id) }
    }

    fun deleteSelectedAudio() {
        val id = ready()?.selectedAudioId ?: return
        commit { project -> project.copy(audio = project.audio.filterNot { it.id == id }) }
        updateReady { it.copy(selectedAudioId = null) }
    }

    fun setSelectedAudioLoop(loop: Boolean) = updateSelected(drag = false) { it.copy(loop = loop, timelineEndMs = if (loop) null else it.timelineEndMs) }

    /** 볼륨(퍼센트, `0..200`). */
    fun changeSelectedAudioVolume(percent: Float) = updateSelected(drag = true) { it.copy(volume = (percent / 100.0).coerceIn(0.0, MotionAudio.MAX_VOLUME)) }

    /** 페이드(밀리초). 조각 길이의 절반을 넘지 않는다. */
    fun changeSelectedAudioFade(fadeInMs: Long?, fadeOutMs: Long?) {
        val ready = ready() ?: return
        val audio = ready.displayed.audio.firstOrNull { it.id == ready.selectedAudioId } ?: return
        val half = audio.toAudioClip().outputRange(durationUs(ready.displayed)).durationUs / 2000
        updateSelected(drag = true) { it.copy(fadeInMs = (fadeInMs ?: it.fadeInMs).coerceIn(0, half), fadeOutMs = (fadeOutMs ?: it.fadeOutMs).coerceIn(0, half)) }
    }

    private fun updateSelected(drag: Boolean, transform: (MotionAudio) -> MotionAudio) {
        val id = ready()?.selectedAudioId ?: return
        val change: (MotionProject) -> MotionProject = { project -> project.copy(audio = project.audio.map { if (it.id == id) transform(it) else it }) }
        if (drag) drag(change) else commit(change)
    }

    /** 조각을 끌기 시작한다. 한 번 끌기가 실행 취소 한 단계다. */
    fun beginAudioDrag(id: String, mode: MotionAudioDrag): Boolean {
        val ready = ready() ?: return false
        val audio = ready.displayed.audio.firstOrNull { it.id == id } ?: return false
        audioDrag = mode to audio
        updateReady { it.copy(selectedAudioId = id) }
        return true
    }

    /** @param deltaMs 잡은 뒤 움직인 거리(결과 시간 밀리초). */
    fun dragAudio(deltaMs: Long) {
        val (mode, origin) = audioDrag ?: return
        val ready = ready() ?: return
        val duration = durationUs(ready.displayed)
        val clip = origin.toAudioClip()
        val range = clip.outputRange(duration)
        val delta = deltaMs * 1000
        val next = when (mode) {
            MotionAudioDrag.MOVE -> AudioEditing.move(clip, range.startUs + delta, duration)
            MotionAudioDrag.START -> AudioEditing.trimStart(clip, range.startUs + delta, duration, MIN_PIECE_US)
            MotionAudioDrag.END -> AudioEditing.trimEnd(clip, range.endExclusiveUs + delta, duration, MIN_PIECE_US)
        }
        val piece = MotionAudio.fromAudioClip(next, origin.sourceDurationMs)
        drag { project -> project.copy(audio = project.audio.map { if (it.id == origin.id) piece else it }) }
    }

    fun endAudioDrag() {
        if (audioDrag == null) return
        audioDrag = null
        finishDrag()
    }

    // 소리 원본 id를 미리보기·저장이 읽을 수 있는 Uri로 바꾼다.
    internal fun uriOf(id: String): Uri? = runCatching {
        when (val input = registry.describe(SourceId(id))) {
            is EditorInput.UriSource -> input.uri
            is EditorInput.FileSource -> Uri.fromFile(File(input.absolutePath))
            else -> null
        }
    }.getOrNull()

    // ---- 텍스트·스티커 ----

    private fun openText(existingId: String?) {
        val ready = ready() ?: return
        val begun = ready.transaction.begin()
        val draft = checkNotNull(begun.draft)
        val id = existingId ?: newId()
        val project = if (existingId != null) draft else draft.copy(overlays = draft.overlays + MotionOverlay(ImageOverlay.Text(id, "")))
        updateReady { it.copy(activeTool = MotionTool.TEXT, transaction = begun.update(project), editingTextId = id, selectedOverlayId = id) }
        pause()
    }

    /** 캔버스의 텍스트를 두 번 눌러 고친다. */
    fun editText(id: String) {
        val ready = ready() ?: return
        if (ready.activeTool != null && ready.activeTool != MotionTool.STICKER) return
        if (ready.displayed.overlays.none { it.overlay.id == id && it.overlay is ImageOverlay.Text }) return
        if (ready.activeTool == MotionTool.STICKER) applyTool()
        openText(id)
    }

    fun updateText(text: String) = updateEditingText { it.copy(text = text) }

    fun updateTextStyle(transform: (TextStyleSpec) -> TextStyleSpec) = updateEditingText { it.copy(style = transform(it.style)) }

    fun updateTextOpacity(opacity: Double) = updateEditingText { it.copy(transform = it.transform.copy(opacity = opacity.coerceIn(0.0, 1.0))) }

    private fun updateEditingText(transform: (ImageOverlay.Text) -> ImageOverlay.Text) {
        updateReady { ready ->
            val id = ready.editingTextId ?: return@updateReady ready
            val draft = ready.transaction.draft ?: return@updateReady ready
            val overlays = draft.overlays.map { item ->
                val text = item.overlay as? ImageOverlay.Text
                if (item.overlay.id == id && text != null) item.copy(overlay = transform(text)) else item
            }
            ready.copy(transaction = ready.transaction.update(draft.copy(overlays = overlays)))
        }
        requestRender()
    }

    /** 고른 텍스트·스티커의 움직임을 바꾼다. */
    fun setOverlayAnimation(animation: OverlayAnimation) {
        val ready = ready() ?: return
        val id = ready.selectedOverlayId ?: return
        val change: (MotionProject) -> MotionProject = { project -> project.copy(overlays = project.overlays.map { if (it.overlay.id == id) it.copy(animation = animation) else it }) }
        if (ready.transaction.isActive) {
            updateReady { it.copy(transaction = it.transaction.update(change(checkNotNull(it.transaction.draft)))) }
            requestRender()
        } else {
            commit(change)
        }
    }

    fun selectStickerCategory(category: EmojiCatalog.Category?) = updateReady { it.copy(stickerCategory = category) }

    fun addSticker(assetId: String) {
        val id = newId()
        commit { it.copy(overlays = it.overlays + MotionOverlay(ImageOverlay.Sticker(id, assetId))) }
        updateReady { it.copy(selectedOverlayId = id) }
    }

    fun deleteSelectedOverlay() {
        val id = ready()?.selectedOverlayId ?: return
        commit { project -> project.copy(overlays = project.overlays.filterNot { it.overlay.id == id }) }
        updateReady { it.copy(selectedOverlayId = null) }
    }

    /**
     * 결과 화면 비율 좌표([x], [y] 모두 `0..1`)를 눌렀다. 텍스트·스티커 도구에서는 그 아래 항목을 고르고, 도구가 없으면 재생을 멈추거나 이어 간다.
     *
     * @param canvas 미리보기 크기(px). 글자 크기 계산에 쓴다.
     */
    fun tapCanvas(x: Double, y: Double, canvas: PixelSize) {
        val ready = ready() ?: return
        if (ready.activeTool != MotionTool.STICKER && ready.activeTool != MotionTool.TEXT) {
            val hit = hitTest(ready, x, y, canvas)
            if (hit != null && ready.activeTool == null) {
                // 도구가 없을 때 항목을 누르면 스티커 도구를 열어 바로 고칠 수 있게 한다.
                selectTool(MotionTool.STICKER)
                updateReady { it.copy(selectedOverlayId = hit) }
                return
            }
            if (ready.activeTool == null) togglePlayback()
            return
        }
        if (ready.activeTool == MotionTool.TEXT) return
        updateReady { it.copy(selectedOverlayId = hitTest(ready, x, y, canvas)) }
    }

    private fun hitTest(ready: MotionEditorUiState.Ready, x: Double, y: Double, canvas: PixelSize): String? =
        overlayRenderer.hitTest(ready.displayed.overlays.map { it.overlay }, x * canvas.width, y * canvas.height, canvas, HIT_SLOP_PX)

    /** 고른 항목을 끌기·확대·회전하기 시작한다. */
    fun beginOverlayGesture(x: Double, y: Double, canvas: PixelSize): Boolean {
        val ready = ready() ?: return false
        if (ready.activeTool != MotionTool.STICKER && ready.activeTool != MotionTool.TEXT) return false
        val id = if (ready.activeTool == MotionTool.TEXT) ready.editingTextId else hitTest(ready, x, y, canvas) ?: ready.selectedOverlayId
        val overlay = ready.displayed.overlays.firstOrNull { it.overlay.id == id }?.overlay ?: return false
        gestureStart = overlay
        updateReady { it.copy(selectedOverlayId = overlay.id, transaction = it.transaction.begin()) }
        return true
    }

    /**
     * @param panX 시작점에서 움직인 거리(결과 화면 너비 비율).
     * @param panY 움직인 거리(높이 비율).
     * @param zoom 시작 대비 배율.
     * @param rotation 시작 대비 회전(도, 시계 방향).
     */
    fun updateOverlayGesture(panX: Double, panY: Double, zoom: Double, rotation: Double) {
        val start = gestureStart ?: return
        val transform = start.transform
        val next = transform.copy(
            center = PointN((transform.center.x + panX).coerceIn(0.0, 1.0), (transform.center.y + panY).coerceIn(0.0, 1.0)),
            scale = (transform.scale * zoom).coerceIn(OverlayTransform.MIN_SCALE, OverlayTransform.MAX_SCALE),
            rotationDegrees = transform.rotationDegrees + rotation,
        )
        updateReady { ready ->
            val draft = ready.transaction.draft ?: return@updateReady ready
            ready.copy(transaction = ready.transaction.update(draft.copy(overlays = draft.overlays.map { if (it.overlay.id == start.id) it.copy(overlay = it.overlay.withTransform(next)) else it })))
        }
        requestRender()
    }

    fun finishOverlayGesture() {
        if (gestureStart == null) return
        gestureStart = null
        // 텍스트는 초안 그대로 두었다가 적용할 때 기록한다.
        updateReady { ready -> if (ready.activeTool == MotionTool.TEXT || !ready.transaction.isActive) ready else ready.copy(transaction = ready.transaction.commit()) }
        requestRender()
    }

    // ---- 닫기·저장 ----

    fun requestClose() {
        val ready = ready()
        when {
            ready == null -> finish(FrameKitResult.Cancelled)
            ready.export is MotionExportUiState.Running -> cancelExport()
            ready.isDirty -> updateReady { it.copy(showDiscardDialog = true) }
            else -> finish(FrameKitResult.Cancelled)
        }
    }

    fun confirmDiscard() = finish(FrameKitResult.Cancelled)

    fun dismissDiscard() = updateReady { it.copy(showDiscardDialog = false) }

    fun requestSave() {
        val ready = ready() ?: return
        if (ready.export != null) return
        if (ready.transaction.isActive) applyTool()
        updateReady { it.copy(showSaveSheet = true) }
    }

    fun dismissSaveSheet() = updateReady { it.copy(showSaveSheet = false) }

    /** 고른 형식·크기로 저장한다. 움직이는 원본은 원본 프레임 간격에 맞춰 초당 프레임 수를 정한다. */
    fun save(format: MotionFormat, size: MotionSize) {
        val ready = ready() ?: return
        if (ready.export != null || exportJob?.isActive == true || _result.value != null) return
        pause()
        val project = ready.transaction.history.current
        val frameRate = frameRateFor(format, project)
        val settings = MotionExportSettings(format, size.longEdge(format), frameRate, exportConfig.webpQuality, exportConfig.loopCount)
        updateReady { it.copy(showSaveSheet = false, export = MotionExportUiState.Running(0f)) }
        exportJob = viewModelScope.launch {
            val exportSlides = SlideCache(loader, settings.longEdge, EXPORT_CACHE)
            try {
                val media = withContext(ioDispatcher) { media(project, exportSlides) }
                val output = exporter.export(project, media, settings, onProgress = { progress ->
                    updateReady { current -> if (current.export is MotionExportUiState.Running && !current.export.cancelling) current.copy(export = MotionExportUiState.Running(progress)) else current }
                }, audioUri = ::uriOf)
                finish(FrameKitResult.Success(output))
            } catch (cancelled: CancellationException) {
                updateReady { it.copy(export = null) }
                throw cancelled
            } catch (error: FrameKitException) {
                Log.w(TAG, "export failed: ${error.code}", error)
                updateReady { it.copy(export = MotionExportUiState.Failed(error.code)) }
            } catch (error: Exception) {
                Log.w(TAG, "export failed", error)
                updateReady { it.copy(export = MotionExportUiState.Failed(EditorErrorCode.UNKNOWN)) }
            } finally {
                exportSlides.clear()
            }
        }
    }

    private fun frameRateFor(format: MotionFormat, project: MotionProject): Int {
        val base = exportConfig.frameRateFor(format)
        val frames = animation ?: return base
        if (project.kind != MotionKind.ANIMATION) return base
        // 원본보다 촘촘하게 뽑아도 같은 그림이 겹칠 뿐이라 원본 간격(속도 반영)에 맞춘다.
        val shortest = frames.durationsMs.minOrNull()?.coerceAtLeast(10) ?: return base
        val sourceFps = (1000.0 / shortest * project.speed).roundToLong().toInt()
        return sourceFps.coerceIn(5, base.coerceAtLeast(5))
    }

    fun cancelExport() {
        updateReady { ready -> if (ready.export is MotionExportUiState.Running) ready.copy(export = MotionExportUiState.Running(ready.export.progress, cancelling = true)) else ready }
        exportJob?.cancel()
    }

    fun dismissExportError() = updateReady { it.copy(export = null) }

    private fun finish(result: FrameKitResult) {
        if (_result.value == null) _result.value = result
    }

    private fun ready(): MotionEditorUiState.Ready? = _state.value as? MotionEditorUiState.Ready

    private fun updateReady(transform: (MotionEditorUiState.Ready) -> MotionEditorUiState.Ready) {
        _state.update { current -> if (current is MotionEditorUiState.Ready) transform(current) else current }
    }

    override fun onCleared() {
        playbackJob?.cancel()
        exportJob?.cancel()
        previewSlides.clear()
        audioPlayer?.release()
        animation?.recycle()
        colorRenderer.release()
    }

    private fun newId(): String = UUID.randomUUID().toString()

    private companion object {
        const val TAG = "FrameKitMotion"
        const val KEY_INPUTS = "framekit_motion_inputs"
        const val KEY_SESSION = "framekit_motion_session"
        const val KEY_SESSION_INPUTS = "framekit_motion_session_inputs"
        const val MIN_AUDIO_MS = 1_000L
        const val MIN_PIECE_US = 200_000L
        const val PREVIEW_LONG_EDGE = 720
        const val PREVIEW_CACHE = 6
        const val EXPORT_CACHE = 3
        const val THUMBNAIL_PX = 160
        const val FILTER_THUMBNAIL_PX = 160
        const val FRAME_MS = 33L
        const val HIT_SLOP_PX = 24.0
        const val MAX_ANIMATION_BYTES = 256L * 1024 * 1024
    }
}
