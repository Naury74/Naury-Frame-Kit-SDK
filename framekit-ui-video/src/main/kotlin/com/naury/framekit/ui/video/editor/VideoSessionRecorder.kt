package com.naury.framekit.ui.video.editor

import androidx.core.net.toUri
import androidx.lifecycle.SavedStateHandle
import com.naury.framekit.android.input.EditorInput
import com.naury.framekit.android.session.EditorSessionStore
import com.naury.framekit.android.session.SessionRecord
import com.naury.framekit.android.session.SourceFingerprint
import com.naury.framekit.android.session.SourceReference
import com.naury.framekit.android.session.VideoProjectSnapshot
import com.naury.framekit.core.model.SourceId
import com.naury.framekit.core.video.VideoProject
import com.naury.framekit.video.source.VideoSourceInfo
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 영상 편집 화면 하나를 디스크의 세션에 연결한다.
 *
 * [SavedStateHandle]에는 세션 id만 둔다. 확정된 편집은 짧게 모았다가 한 번에 쓰고, 열려 있는 도구의
 * 초안은 쓰지 않는다. 프로세스가 종료돼도 다시 열면 마지막으로 확정한 편집부터 이어 간다.
 */
internal class VideoSessionRecorder(
    private val store: EditorSessionStore,
    private val savedState: SavedStateHandle,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
    private val debounceMillis: Long,
) {
    private var sessionId: String? = null
    private var sources: List<SourceId> = emptyList()
    private var pendingSave: Job? = null

    /** 이전 프로세스가 남긴 세션. 없으면 `null`. 백그라운드 스레드에서 호출한다. */
    fun loadPrevious(): SessionRecord? {
        val id = savedState.get<String>(KEY_SESSION_ID) ?: return null
        return store.load(id).also { if (it == null) savedState.remove<String>(KEY_SESSION_ID) }
    }

    /**
     * 방금 연 원본에 세션을 연결한다.
     *
     * @return 같은 영상의 이전 세션이면 [previous], 아니면 `null`. 다른 영상의 세션은 지우고 새로 만들어
     *   편집 내용이 엉뚱한 영상에 적용되지 않게 한다.
     */
    fun attach(previous: SessionRecord?, input: EditorInput, sourceId: SourceId, info: VideoSourceInfo): SessionRecord? {
        val fingerprint = info.fingerprint()
        val matched = previous?.takeIf { it.fingerprint == fingerprint }
        sources = listOf(sourceId)
        if (matched != null) {
            sessionId = matched.sessionId
        } else {
            previous?.let { store.delete(it.sessionId) }
            sessionId = input.toReference()?.let { store.create(it, fingerprint) }
        }
        savedState[KEY_SESSION_ID] = sessionId
        store.deleteStale()
        return matched
    }

    /** 이전 세션의 편집을 지금 등록한 원본 키로 바꿔 돌려준다. 맞지 않으면 `null`. */
    fun restoredProject(record: SessionRecord?): VideoProject? =
        record?.videoSnapshot?.let { runCatching { it.toProject(sources) }.getOrNull() }

    /** 확정된 편집을 debounce 뒤에 쓴다. 대기 중인 쓰기는 대체된다. */
    fun saveCommitted(project: VideoProject) {
        val id = sessionId ?: return
        val snapshot = snapshotOf(project) ?: return
        pendingSave?.cancel()
        pendingSave = scope.launch(ioDispatcher) {
            if (debounceMillis > 0) delay(debounceMillis)
            store.saveVideoSnapshot(id, snapshot)
        }
    }

    /** [project]의 저장이 진행 중인지 즉시 기록한다. */
    suspend fun markExport(project: VideoProject, running: Boolean) {
        val id = sessionId ?: return
        val snapshot = snapshotOf(project) ?: return
        pendingSave?.cancel()
        withContext(ioDispatcher) { store.saveVideoSnapshot(id, snapshot, exportInProgress = running) }
    }

    /** 편집이 끝났다. 세션은 더 이상 필요 없다. */
    fun end() {
        pendingSave?.cancel()
        sessionId?.let(store::deleteAsync)
        sessionId = null
        savedState.remove<String>(KEY_SESSION_ID)
    }

    /** 다시 열 수 없어 복원하지 않을 이전 세션을 지운다. */
    fun discard(record: SessionRecord) = store.deleteAsync(record.sessionId)

    /** 결과 없이 화면이 사라졌다. 오래된 세션 정리가 지울 때까지 파일은 남긴다. */
    fun detach() {
        sessionId?.let(store::release)
    }

    private fun snapshotOf(project: VideoProject) = runCatching { VideoProjectSnapshot.of(project, sources) }.getOrNull()

    private fun EditorInput.toReference(): SourceReference? = when (this) {
        is EditorInput.UriSource -> SourceReference.Content(uri.toString())
        is EditorInput.FileSource -> SourceReference.LocalFile(absolutePath)
        is EditorInput.Pick -> null
    }

    private fun VideoSourceInfo.fingerprint() = SourceFingerprint(
        mimeType = metadata.mimeType,
        encodedWidth = encodedSize.width,
        encodedHeight = encodedSize.height,
        orientation = rotationDegrees,
        durationUs = metadata.durationUs,
    )

    companion object {
        const val KEY_SESSION_ID = "framekit_video_session_id"

        fun SourceReference.toInput(): EditorInput = when (this) {
            is SourceReference.Content -> EditorInput.UriSource(uri.toUri())
            is SourceReference.LocalFile -> EditorInput.FileSource(absolutePath)
        }
    }
}
