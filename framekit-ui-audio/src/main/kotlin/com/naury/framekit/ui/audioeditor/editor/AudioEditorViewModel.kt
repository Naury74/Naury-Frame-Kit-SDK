package com.naury.framekit.ui.audioeditor.editor

import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.naury.framekit.android.input.EditorInput
import com.naury.framekit.android.output.AppFileOutputStore
import com.naury.framekit.android.result.EditedMedia
import com.naury.framekit.android.result.EditorError
import com.naury.framekit.android.result.EditorErrorCode
import com.naury.framekit.android.result.FrameKitException
import com.naury.framekit.android.result.FrameKitResult
import com.naury.framekit.android.session.AudioClipSnapshot
import com.naury.framekit.android.source.SessionSourceRegistry
import com.naury.framekit.core.audio.AudioProject
import com.naury.framekit.core.audio.AudioProjectEditing
import com.naury.framekit.core.history.HistoryTransaction
import com.naury.framekit.core.model.MediaType
import com.naury.framekit.core.model.SourceId
import com.naury.framekit.core.video.AudioClip
import com.naury.framekit.core.video.AudioEditing
import com.naury.framekit.core.video.AudioRole
import com.naury.framekit.core.video.TimeRangeUs
import com.naury.framekit.ui.audioeditor.contract.AudioEditorRequest
import com.naury.framekit.ui.audioeditor.contract.AudioTool
import com.naury.framekit.video.audio.Waveform
import com.naury.framekit.video.media3.AudioMixTrack
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.util.UUID

/** 소리 트랙들을 섞어 [File]에 AAC로 쓴다(테스트에서는 가짜로 바꾼다). */
internal typealias AudioMixer = suspend (durationUs: Long, tracks: List<AudioMixTrack>, output: File, bitrate: Int, onProgress: (Float) -> Unit) -> Unit

/**
 * 음성 편집기의 상태와 동작. 모든 편집은 실행 취소 한 단계로 기록한다(슬라이더·끌기는 손을 뗄 때 한 단계).
 */
internal class AudioEditorViewModel(
    private val request: AudioEditorRequest,
    private val savedState: SavedStateHandle,
    private val registry: SessionSourceRegistry,
    private val outputStore: AppFileOutputStore,
    private val ioDispatcher: CoroutineDispatcher,
    private val describe: (EditorInput) -> AudioSource?,
    private val mixer: AudioMixer,
    private val waveformLoader: (Uri) -> Waveform? = { null },
    private val player: AudioClipPlayer? = null,
) : ViewModel() {

    private val _state = MutableStateFlow<AudioEditorUiState>(AudioEditorUiState.Loading)
    val state: StateFlow<AudioEditorUiState> = _state.asStateFlow()

    private val _result = MutableStateFlow<FrameKitResult?>(null)

    /** 최종 결과. `null`이 아니게 되면 Activity가 끝난다. */
    val result: StateFlow<FrameKitResult?> = _result.asStateFlow()

    private val _waveforms = MutableStateFlow<Map<String, Waveform>>(emptyMap())

    /** 소리 원본 id별 파형. */
    val waveforms: StateFlow<Map<String, Waveform>> = _waveforms.asStateFlow()

    val config get() = request.config

    private var playbackJob: Job? = null
    private var exportJob: Job? = null
    private var playStartedAt = 0L
    private var playStartPosition = 0L
    private var drag: Pair<DragKind, AudioClip>? = null

    init {
        val sessionSources = savedState.get<ArrayList<EditorInput>>(KEY_SESSION_SOURCES)
        val session = savedState.get<String>(KEY_SESSION)
        val inputs = savedState.get<ArrayList<EditorInput>>(KEY_INPUTS)
        when {
            sessionSources != null && session != null -> restore(sessionSources, session)
            inputs != null -> load(inputs)
            else -> when (val input = request.input) {
                is EditorInput.Pick -> _state.value = AudioEditorUiState.AwaitingPick(input.maxItems)
                is EditorInput.Capture -> _state.value = AudioEditorUiState.Recording
                is EditorInput.Multiple -> load(input.items)
                else -> load(listOf(input))
            }
        }
        // 편집이 기록될 때마다 저장해, 프로세스가 끝났다가 돌아와도 이어서 편집할 수 있게 한다.
        viewModelScope.launch {
            state.map { (it as? AudioEditorUiState.Ready)?.transaction?.history?.current }.filterNotNull().distinctUntilChanged().collect(::saveSession)
        }
    }

    // ---- 원본 ----

    /** 파일 선택기 결과. 빈 목록이면 사용자가 닫은 것이다. */
    fun onPicked(uris: List<Uri>) {
        val ready = ready()
        if (uris.isEmpty()) {
            if (ready == null) finish(FrameKitResult.Cancelled)
            return
        }
        val inputs = uris.take(config.maxSourceCount).map(EditorInput::UriSource)
        if (ready == null) load(inputs) else addFiles(inputs)
    }

    fun retry() {
        when (request.input) {
            is EditorInput.Pick -> _state.value = AudioEditorUiState.AwaitingPick((request.input as EditorInput.Pick).maxItems)
            is EditorInput.Capture -> _state.value = AudioEditorUiState.Recording
            else -> savedState.get<ArrayList<EditorInput>>(KEY_INPUTS)?.let(::load) ?: finish(FrameKitResult.Cancelled)
        }
    }

    private fun load(inputs: List<EditorInput>) {
        savedState[KEY_INPUTS] = ArrayList(inputs)
        _state.value = AudioEditorUiState.Loading
        viewModelScope.launch {
            val opened = withContext(ioDispatcher) { inputs.map { open(it) } }
            if (opened.any { it == null }) {
                _state.value = AudioEditorUiState.LoadFailed(EditorErrorCode.UNSUPPORTED_FORMAT, canRetry = request.input is EditorInput.Pick)
                return@launch
            }
            var project = AudioProject(emptyList())
            opened.filterNotNull().forEach { (id, source) -> project = AudioProjectEditing.append(project, clipOf(id, source), ::newId) }
            _state.value = AudioEditorUiState.Ready(HistoryTransaction.start(project), opened.filterNotNull().toMap())
            opened.filterNotNull().forEach { loadWaveform(it.first) }
        }
    }

    // 원본을 등록하고 길이를 읽는다. 소리가 아니거나 열 수 없으면 null.
    private fun open(input: EditorInput): Pair<String, AudioSource>? = runCatching {
        val id = registry.register(input).value
        val source = describe(input) ?: return null
        if (source.durationUs <= 0) null else id to source
    }.getOrNull()

    private fun clipOf(id: String, source: AudioSource): AudioClip =
        AudioClip(newId(), SourceId(id), TimeRangeUs(0, source.durationUs), timelineStartUs = 0, role = AudioRole.VOICE)

    private fun loadWaveform(sourceId: String) {
        if (sourceId in _waveforms.value) return
        val uri = uriOf(sourceId) ?: return
        viewModelScope.launch(ioDispatcher) {
            runCatching { waveformLoader(uri) }.getOrNull()?.let { waveform -> _waveforms.update { it + (sourceId to waveform) } }
        }
    }

    /** 소리 원본 id를 미리보기·저장이 읽을 수 있는 Uri로 바꾼다. */
    fun uriOf(id: String): Uri? = runCatching {
        when (val input = registry.describe(SourceId(id))) {
            is EditorInput.UriSource -> input.uri
            is EditorInput.FileSource -> Uri.fromFile(File(input.absolutePath))
            else -> null
        }
    }.getOrNull()

    // ---- 녹음 ----

    /**
     * 녹음을 마쳤다. 처음 녹음 화면이면 편집 화면으로 넘어가거나(설정) 바로 돌려주고, 편집 중이면 [RecordTarget]대로 넣는다.
     */
    fun onRecorded(file: File) {
        val input = EditorInput.FileSource(file.absolutePath)
        val current = _state.value
        if (current is AudioEditorUiState.Recording) {
            if (!config.editAfterRecording) return deliverRecording(file)
            return load(listOf(input))
        }
        val ready = ready() ?: return
        val target = ready.recordSheet ?: RecordTarget.INSERT
        updateReady { it.copy(recordSheet = null, busy = true) }
        viewModelScope.launch {
            val opened = withContext(ioDispatcher) { open(input) }
            if (opened == null) return@launch updateReady { it.copy(busy = false, notice = AudioNotice.ADD_FAILED) }
            place(opened, target)
        }
    }

    fun onRecordingTooShort() = updateReady { it.copy(notice = AudioNotice.TOO_SHORT) }

    /** 녹음을 그만두고 닫는다(처음 녹음 화면에서). */
    fun cancelRecording() {
        if (_state.value is AudioEditorUiState.Recording) finish(FrameKitResult.Cancelled) else updateReady { it.copy(recordSheet = null) }
    }

    fun openRecordSheet(target: RecordTarget) {
        pause()
        updateReady { it.copy(recordSheet = target, selectedClipId = null, range = null) }
    }

    fun setRecordTarget(target: RecordTarget) = updateReady { if (it.recordSheet != null) it.copy(recordSheet = target) else it }

    fun closeRecordSheet() = updateReady { it.copy(recordSheet = null) }

    // 편집 없이 녹음 파일만 돌려준다(녹음기로만 쓰기). 결과 폴더에 복사해 호스트가 소유하게 한다.
    private fun deliverRecording(file: File) {
        viewModelScope.launch {
            val media = withContext(ioDispatcher) {
                runCatching {
                    val partial = outputStore.createPartial(EXTENSION)
                    file.copyTo(partial, overwrite = true)
                    val published = outputStore.publish(partial, EXTENSION)
                    val duration = describe(EditorInput.FileSource(published.absolutePath))?.durationUs?.div(1000)
                    file.delete()
                    EditedMedia(outputStore.uriFor(published), MediaType.AUDIO, 0, 0, duration, MIME, published.length())
                }.getOrNull()
            }
            finish(if (media != null) FrameKitResult.Success(media) else FrameKitResult.Failure(EditorError(EditorErrorCode.OUTPUT_WRITE_FAILED)))
        }
    }

    // ---- 파일 더하기 ----

    /** 기기의 소리 파일을 재생 위치에 끼워 넣는다(겹친 줄이면 겹쳐 넣기). */
    fun addFiles(inputs: List<EditorInput>, target: RecordTarget = RecordTarget.INSERT) {
        val ready = ready() ?: return
        if (inputs.isEmpty() || ready.busy) return
        updateReady { it.copy(busy = true) }
        viewModelScope.launch {
            val opened = withContext(ioDispatcher) { inputs.mapNotNull { open(it) } }
            if (opened.isEmpty()) return@launch updateReady { it.copy(busy = false, notice = AudioNotice.ADD_FAILED) }
            opened.forEach { place(it, target) }
        }
    }

    private fun place(opened: Pair<String, AudioSource>, target: RecordTarget) {
        val (id, source) = opened
        updateReady { it.copy(busy = false, sources = it.sources + (id to source)) }
        val position = ready()?.positionUs ?: 0L
        val clip = clipOf(id, source)
        commit { project ->
            when (target) {
                RecordTarget.INSERT -> AudioProjectEditing.insert(project, clip, position, ::newId)
                RecordTarget.OVERLAY -> AudioProjectEditing.overlay(project, clip, position)
            }
        }
        loadWaveform(id)
        // 끼워 넣었으면 새 소리 끝으로 재생 위치를 옮겨 이어서 녹음하기 쉽게 한다.
        if (target == RecordTarget.INSERT) updateReady { it.copy(positionUs = position + source.durationUs) }
    }

    // ---- 재생 ----

    fun togglePlayback() {
        val ready = ready() ?: return
        if (ready.playing) pause() else play()
    }

    fun seekTo(positionUs: Long) {
        val ready = ready() ?: return
        playbackJob?.cancel()
        player?.pauseAll()
        updateReady { it.copy(playing = false, positionUs = positionUs.coerceIn(0, ready.project.durationUs)) }
    }

    /** 앞뒤로 [deltaUs]만큼 옮긴다. 재생 중이면 이어서 재생한다. */
    fun skip(deltaUs: Long) {
        val ready = ready() ?: return
        val wasPlaying = ready.playing
        seekTo(ready.positionUs + deltaUs)
        if (wasPlaying) play()
    }

    private fun play() {
        val ready = ready() ?: return
        if (ready.project.durationUs <= 0) return
        val start = if (ready.positionUs >= ready.project.durationUs) 0L else ready.positionUs
        playbackJob?.cancel()
        updateReady { it.copy(playing = true, positionUs = start) }
        playStartedAt = SystemClock.elapsedRealtime()
        playStartPosition = start
        playbackJob = viewModelScope.launch {
            while (isActive) {
                val current = ready() ?: break
                val duration = current.project.durationUs
                val position = playStartPosition + (SystemClock.elapsedRealtime() - playStartedAt) * 1000
                if (position >= duration) {
                    updateReady { it.copy(playing = false, positionUs = duration) }
                    player?.pauseAll()
                    break
                }
                updateReady { it.copy(positionUs = position) }
                player?.sync(current.project.clips, duration, position, playing = true)
                delay(FRAME_MS)
            }
        }
    }

    private fun pause() {
        playbackJob?.cancel()
        player?.pauseAll()
        updateReady { it.copy(playing = false) }
    }

    // ---- 편집 ----

    fun selectClip(id: String?) = updateReady { it.copy(selectedClipId = id, range = null) }

    /** 재생 위치의 본 줄 조각을 고른다(볼륨 도구). */
    fun selectClipAtPlayhead() {
        val ready = ready() ?: return
        val clip = ready.project.mainClips.firstOrNull { ready.positionUs in ready.project.rangeOf(it) } ?: ready.project.mainClips.lastOrNull() ?: return
        selectClip(clip.id)
    }

    /** 재생 위치에서 나눈다. 고른 조각이 있으면 그 조각을, 없으면 재생 위치의 본 줄 조각을 나눈다. */
    fun splitAtPlayhead() {
        val ready = ready() ?: return
        val position = ready.positionUs
        val target = ready.selectedClip ?: ready.project.mainClips.firstOrNull { position in ready.project.rangeOf(it) } ?: return
        val newId = newId()
        val next = AudioProjectEditing.split(ready.project, target.id, position, newId, MIN_PIECE_US)
        if (next.sameContentAs(ready.project)) return updateReady { it.copy(notice = AudioNotice.SPLIT_UNAVAILABLE) }
        commit { next }
        updateReady { it.copy(selectedClipId = newId) }
    }

    /** 고른 조각을 지운다. 본 줄이면 뒤를 당긴다. */
    fun deleteSelected() {
        val id = ready()?.selectedClipId ?: return
        commit { AudioProjectEditing.deleteClip(it, id) }
        updateReady { it.copy(selectedClipId = null, positionUs = it.positionUs.coerceAtMost(it.project.durationUs)) }
    }

    fun setSelectedLoop(loop: Boolean) = updateSelected(drag = false) { it.copy(loop = loop, timelineEndUs = if (loop) null else it.timelineEndUs) }

    /** 볼륨(퍼센트, `0..200`). 손을 떼면 [finishGesture]로 기록한다. */
    fun changeSelectedVolume(percent: Float) = updateSelected(drag = true) { it.copy(volume = (percent / 100.0).coerceIn(0.0, MAX_VOLUME)) }

    /** 페이드(µs). 조각 길이의 절반을 넘지 않는다. */
    fun changeSelectedFade(fadeInUs: Long?, fadeOutUs: Long?) {
        val ready = ready() ?: return
        val clip = ready.selectedClip ?: return
        val half = ready.project.rangeOf(clip).durationUs / 2
        updateSelected(drag = true) { it.copy(fadeInUs = (fadeInUs ?: it.fadeInUs).coerceIn(0, half), fadeOutUs = (fadeOutUs ?: it.fadeOutUs).coerceIn(0, half)) }
    }

    private fun updateSelected(drag: Boolean, transform: (AudioClip) -> AudioClip) {
        val id = ready()?.selectedClipId ?: return
        val change: (AudioProject) -> AudioProject = { project -> project.copy(clips = project.clips.map { if (it.id == id) transform(it) else it }) }
        if (drag) gesture(change) else commit(change)
    }

    // ---- 구간 편집 ----

    /** 재생 위치부터 3초(끝까지)를 구간으로 잡는다. */
    fun beginRange() {
        val ready = ready() ?: return
        val duration = ready.project.durationUs
        if (duration <= MIN_PIECE_US) return
        val start = ready.positionUs.coerceIn(0, duration - MIN_PIECE_US)
        pause()
        updateReady { it.copy(range = TimeRangeUs(start, minOf(duration, start + DEFAULT_RANGE_US)), selectedClipId = null) }
    }

    /** 구간 한쪽 끝을 [timeUs]로 옮긴다. */
    fun moveRangeEdge(start: Boolean, timeUs: Long) = updateReady { ready ->
        val range = ready.range ?: return@updateReady ready
        val duration = ready.project.durationUs
        val next = if (start) {
            TimeRangeUs(timeUs.coerceIn(0, range.endExclusiveUs - MIN_PIECE_US), range.endExclusiveUs)
        } else {
            TimeRangeUs(range.startUs, timeUs.coerceIn(range.startUs + MIN_PIECE_US, duration))
        }
        ready.copy(range = next)
    }

    fun cancelRange() = updateReady { it.copy(range = null) }

    /** 고른 구간을 잘라 내고 뒤를 당긴다. */
    fun deleteRange() {
        val range = ready()?.range ?: return
        commit { AudioProjectEditing.deleteRange(it, range, ::newId) }
        updateReady { it.copy(range = null, positionUs = range.startUs.coerceAtMost(it.project.durationUs)) }
    }

    /** 고른 구간만 남긴다. */
    fun keepRange() {
        val range = ready()?.range ?: return
        commit { AudioProjectEditing.keepRange(it, range, ::newId) }
        updateReady { it.copy(range = null, positionUs = 0) }
    }

    // ---- 타임라인 끌기 ----

    /** 조각 끌기를 시작한다. 본 줄은 양 끝 자르기만, 겹친 줄은 옮기기도 된다. */
    fun beginDrag(id: String, kind: DragKind): Boolean {
        val ready = ready() ?: return false
        val clip = ready.project.clips.firstOrNull { it.id == id } ?: return false
        if (kind == DragKind.MOVE && clip.role == AudioRole.VOICE) return false
        pause()
        drag = kind to clip
        updateReady { it.copy(selectedClipId = id, transaction = it.transaction.begin()) }
        return true
    }

    /** @param deltaUs 잡은 뒤 움직인 거리(µs). */
    fun dragBy(deltaUs: Long) {
        val (kind, origin) = drag ?: return
        updateReady { ready ->
            val draft = ready.transaction.draft ?: return@updateReady ready
            val duration = ready.transaction.history.current.durationUs
            // 본 줄은 결과 길이가 바뀌므로 넉넉한 길이로 계산한다.
            val limit = if (origin.role == AudioRole.VOICE) Long.MAX_VALUE / 4 else duration
            val range = origin.outputRange(limit)
            val next = when (kind) {
                DragKind.MOVE -> AudioEditing.move(origin, range.startUs + deltaUs, duration)
                DragKind.START -> AudioEditing.trimStart(origin, range.startUs + deltaUs, limit, MIN_PIECE_US)
                DragKind.END -> AudioEditing.trimEnd(origin, range.endExclusiveUs + deltaUs, limit, MIN_PIECE_US)
            }
            ready.copy(transaction = ready.transaction.update(draft.copy(clips = draft.clips.map { if (it.id == origin.id) next else it })))
        }
    }

    fun endDrag() {
        val (_, origin) = drag ?: return
        drag = null
        updateReady { ready ->
            val draft = ready.transaction.draft ?: return@updateReady ready
            // 본 줄 조각의 끝을 잘랐으면 빈틈 없이 다시 붙인다.
            val settled = if (origin.role == AudioRole.VOICE) AudioProjectEditing.closeGaps(draft) else draft
            val next = if (settled.sameContentAs(ready.transaction.history.current)) ready.transaction.cancel() else ready.transaction.update(settled).commit()
            ready.copy(transaction = next, positionUs = ready.positionUs.coerceAtMost(next.displayed.durationUs))
        }
    }

    // ---- 기록 ----

    fun undo() = updateReady { it.copy(transaction = it.transaction.undo(), selectedClipId = null, range = null) }.also { clampPosition() }

    fun redo() = updateReady { it.copy(transaction = it.transaction.redo(), selectedClipId = null, range = null) }.also { clampPosition() }

    private fun clampPosition() = updateReady { it.copy(positionUs = it.positionUs.coerceAtMost(it.project.durationUs)) }

    private fun commit(transform: (AudioProject) -> AudioProject) = updateReady { ready ->
        if (ready.transaction.isActive || ready.export != null) return@updateReady ready
        val next = transform(ready.transaction.history.current)
        if (next.sameContentAs(ready.transaction.history.current)) ready else ready.copy(transaction = ready.transaction.update(next).commit())
    }

    private fun gesture(transform: (AudioProject) -> AudioProject) = updateReady { ready ->
        if (ready.export != null) return@updateReady ready
        val begun = ready.transaction.begin()
        ready.copy(transaction = begun.update(transform(checkNotNull(begun.draft))))
    }

    fun finishGesture() = updateReady { if (it.transaction.isActive && drag == null) it.copy(transaction = it.transaction.commit()) else it }

    fun dismissNotice() = updateReady { it.copy(notice = null) }

    fun hasTool(tool: AudioTool): Boolean = tool in config.enabledTools

    // ---- 닫기·저장 ----

    fun requestClose() {
        val ready = ready()
        when {
            ready == null -> finish(FrameKitResult.Cancelled)
            ready.export is AudioExportUiState.Running -> cancelExport()
            ready.isDirty -> updateReady { it.copy(showDiscardDialog = true) }
            else -> finish(FrameKitResult.Cancelled)
        }
    }

    /** 시스템 뒤로 가기. 열린 시트·구간·선택을 먼저 닫는다. */
    fun onBack() {
        val ready = ready()
        when {
            _state.value is AudioEditorUiState.Recording -> cancelRecording()
            ready == null -> requestClose()
            ready.recordSheet != null -> closeRecordSheet()
            ready.range != null -> cancelRange()
            ready.selectedClipId != null -> selectClip(null)
            else -> requestClose()
        }
    }

    fun confirmDiscard() = finish(FrameKitResult.Cancelled)

    fun dismissDiscard() = updateReady { it.copy(showDiscardDialog = false) }

    fun save() {
        val ready = ready() ?: return
        if (ready.export != null || exportJob?.isActive == true || _result.value != null) return
        if (ready.transaction.isActive) finishGesture()
        pause()
        val project = ready.transaction.history.current
        val duration = project.durationUs
        if (duration <= 0) return
        updateReady { it.copy(export = AudioExportUiState.Running(0f), selectedClipId = null, range = null) }
        exportJob = viewModelScope.launch {
            var partial: File? = null
            try {
                val tracks = project.clips.mapNotNull { clip ->
                    val uri = uriOf(clip.source.value) ?: return@mapNotNull null
                    AudioMixTrack(uri, clip, ready.sources[clip.source.value]?.durationUs)
                }
                val output = withContext(ioDispatcher) { outputStore.createPartial(EXTENSION) }
                partial = output
                mixer(duration, tracks, output, request.export.bitrate) { progress ->
                    updateReady { current -> if (current.export is AudioExportUiState.Running && !current.export.cancelling) current.copy(export = AudioExportUiState.Running(progress)) else current }
                }
                val published = withContext(ioDispatcher) {
                    try {
                        outputStore.publish(output, EXTENSION)
                    } catch (error: IOException) {
                        throw FrameKitException(EditorErrorCode.OUTPUT_WRITE_FAILED, "Output could not be published", error)
                    }
                }
                finish(FrameKitResult.Success(EditedMedia(outputStore.uriFor(published), MediaType.AUDIO, 0, 0, duration / 1000, MIME, published.length())))
            } catch (cancelled: CancellationException) {
                updateReady { it.copy(export = null) }
                throw cancelled
            } catch (error: FrameKitException) {
                Log.w(TAG, "export failed: ${error.code}", error)
                updateReady { it.copy(export = AudioExportUiState.Failed(error.code)) }
            } catch (error: Exception) {
                Log.w(TAG, "export failed", error)
                updateReady { it.copy(export = AudioExportUiState.Failed(EditorErrorCode.ENCODE_FAILED)) }
            } finally {
                partial?.takeIf { it.exists() && _result.value == null }?.delete()
            }
        }
    }

    fun cancelExport() {
        updateReady { ready -> if (ready.export is AudioExportUiState.Running) ready.copy(export = AudioExportUiState.Running(ready.export.progress, cancelling = true)) else ready }
        exportJob?.cancel()
    }

    fun dismissExportError() = updateReady { it.copy(export = null) }

    // ---- 이어 편집 ----

    private fun saveSession(project: AudioProject) {
        runCatching {
            val ids = project.clips.map { it.source }.distinct()
            savedState[KEY_SESSION_SOURCES] = ArrayList(ids.map { registry.describe(it) })
            savedState[KEY_SESSION] = JSON.encodeToString(ListSerializer(AudioClipSnapshot.serializer()), project.clips.map { AudioClipSnapshot.of(it, ids) })
        }.onFailure { Log.w(TAG, "session save failed", it) }
    }

    private fun restore(inputs: List<EditorInput>, session: String) {
        _state.value = AudioEditorUiState.Loading
        viewModelScope.launch {
            val restored = withContext(ioDispatcher) {
                runCatching {
                    val opened = inputs.map { open(it) ?: error("source unavailable") }
                    val ids = opened.map { SourceId(it.first) }
                    val clips = JSON.decodeFromString(ListSerializer(AudioClipSnapshot.serializer()), session).map { it.toModel(ids) }
                    AudioEditorUiState.Ready(HistoryTransaction.start(AudioProject(clips)), opened.toMap(), notice = AudioNotice.RESTORED)
                }.getOrNull()
            }
            if (restored == null) {
                savedState.remove<String>(KEY_SESSION)
                return@launch retry()
            }
            _state.value = restored
            restored.sources.keys.forEach(::loadWaveform)
        }
    }

    private fun finish(result: FrameKitResult) {
        if (_result.value == null) _result.value = result
    }

    private fun ready(): AudioEditorUiState.Ready? = _state.value as? AudioEditorUiState.Ready

    private fun updateReady(transform: (AudioEditorUiState.Ready) -> AudioEditorUiState.Ready) {
        _state.update { current -> if (current is AudioEditorUiState.Ready) transform(current) else current }
    }

    override fun onCleared() {
        playbackJob?.cancel()
        exportJob?.cancel()
        player?.release()
    }

    private fun newId(): String = UUID.randomUUID().toString()

    companion object {
        private const val TAG = "FrameKitAudio"
        private const val KEY_INPUTS = "framekit_audio_inputs"
        private const val KEY_SESSION = "framekit_audio_session"
        private const val KEY_SESSION_SOURCES = "framekit_audio_session_sources"
        private const val FRAME_MS = 33L
        const val MIN_PIECE_US = 200_000L
        private const val DEFAULT_RANGE_US = 3_000_000L
        const val MAX_VOLUME = 2.0
        const val EXTENSION = "m4a"
        const val MIME = "audio/mp4"
        private val JSON = Json { ignoreUnknownKeys = true }
    }
}

/** 타임라인에서 조각을 끄는 방식. */
internal enum class DragKind { MOVE, START, END }
