package com.naury.framekit.ui.image.editor

import androidx.core.net.toUri
import androidx.lifecycle.SavedStateHandle
import com.naury.framekit.android.input.EditorInput
import com.naury.framekit.android.session.EditorSessionStore
import com.naury.framekit.android.session.ImageProjectSnapshot
import com.naury.framekit.android.session.ProjectAssetStore
import com.naury.framekit.android.session.SessionRecord
import com.naury.framekit.android.session.SourceFingerprint
import com.naury.framekit.android.session.SourceReference
import com.naury.framekit.core.model.ImageProject
import com.naury.framekit.image.decode.ImageSourceInfo
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 에디터 화면 하나를 디스크의 세션과 연결한다.
 *
 * [SavedStateHandle]에는 세션 id만 보관한다. 커밋된 스냅샷은 짧은 디바운스 후 기록하므로 실행 취소를
 * 연달아 눌러도 한 번만 쓴다. 열린 도구의 초안은 절대 기록하지 않는다.
 */
internal class ImageSessionRecorder(
    private val store: EditorSessionStore,
    private val savedState: SavedStateHandle,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
    private val debounceMillis: Long,
) {
    private var sessionId: String? = null
    private var pendingSave: Job? = null

    /** 이전 프로세스가 남긴 세션, 없으면 `null`. 백그라운드 스레드에서 호출한다. */
    fun loadPrevious(): SessionRecord? {
        val id = savedState.get<String>(KEY_SESSION_ID) ?: return null
        return store.load(id).also { if (it == null) savedState.remove<String>(KEY_SESSION_ID) }
    }

    /**
     * 방금 연 소스의 세션에 화면을 연결한다.
     *
     * @return 같은 이미지의 세션이면 [previous], 아니면 `null`. 다른 이미지의 이전 세션은 삭제하고
     *   새 세션을 만들므로 편집이 다른 사진에 적용되는 일은 없다.
     */
    fun attach(previous: SessionRecord?, input: EditorInput, info: ImageSourceInfo): SessionRecord? {
        val fingerprint = info.fingerprint()
        val matched = previous?.takeIf { it.fingerprint == fingerprint }
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

    /** 디바운스 후 커밋된 스냅샷을 기록한다. 대기 중인 기록이 있으면 대체한다. */
    fun saveCommitted(project: ImageProject) {
        val id = sessionId ?: return
        pendingSave?.cancel()
        pendingSave = scope.launch(ioDispatcher) {
            if (debounceMillis > 0) delay(debounceMillis)
            store.saveSnapshot(id, ImageProjectSnapshot.of(project))
        }
    }

    /** [project]의 내보내기 진행 여부를 즉시 기록한다. */
    suspend fun markExport(project: ImageProject, running: Boolean) {
        val id = sessionId ?: return
        pendingSave?.cancel()
        withContext(ioDispatcher) { store.saveSnapshot(id, ImageProjectSnapshot.of(project), exportInProgress = running) }
    }

    /** 에디터가 종료되어 세션이 더 이상 필요 없다. */
    fun end() {
        pendingSave?.cancel()
        sessionId?.let(store::deleteAsync)
        sessionId = null
        savedState.remove<String>(KEY_SESSION_ID)
    }

    /** 현재 세션의 에셋 저장소. 세션을 만들지 못했으면 `null`. */
    fun assets(): ProjectAssetStore? = sessionId?.let(store::assets)

    /** 다시 열 수 없어 복원하지 않을 이전 세션을 삭제한다. */
    fun discard(record: SessionRecord) {
        store.deleteAsync(record.sessionId)
    }

    /** 결과 없이 화면이 사라졌다. 오래된 세션 정리를 위해 파일은 남겨 둔다. */
    fun detach() {
        sessionId?.let(store::release)
    }

    private fun EditorInput.toReference(): SourceReference? = when (this) {
        is EditorInput.UriSource -> SourceReference.Content(uri.toString())
        is EditorInput.FileSource -> SourceReference.LocalFile(absolutePath)
        is EditorInput.Pick -> null
    }

    private fun ImageSourceInfo.fingerprint() = SourceFingerprint(
        mimeType = metadata.mimeType,
        encodedWidth = encodedSize.width,
        encodedHeight = encodedSize.height,
        orientation = orientation.exifValue,
    )

    companion object {
        const val KEY_SESSION_ID = "framekit_session_id"

        fun SourceReference.toInput(): EditorInput = when (this) {
            is SourceReference.Content -> EditorInput.UriSource(uri.toUri())
            is SourceReference.LocalFile -> EditorInput.FileSource(absolutePath)
        }
    }
}
