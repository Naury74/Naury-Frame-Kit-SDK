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
 * Connects one editor screen to its on-disk session.
 *
 * Only the session id is kept in [SavedStateHandle]. Committed snapshots are written after a short
 * debounce so a burst of undo taps produces one write; drafts of an open tool are never written.
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

    /** Session left behind by a previous process, or `null`. Call from a background thread. */
    fun loadPrevious(): SessionRecord? {
        val id = savedState.get<String>(KEY_SESSION_ID) ?: return null
        return store.load(id).also { if (it == null) savedState.remove<String>(KEY_SESSION_ID) }
    }

    /**
     * Binds the screen to a session for the source that was just opened.
     *
     * @return [previous] when it belongs to the same image, otherwise `null`. A previous session for a
     *   different image is deleted and a new session is created, so edits are never applied to
     *   another picture.
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

    /** Writes the committed snapshot after the debounce, replacing any pending write. */
    fun saveCommitted(project: ImageProject) {
        val id = sessionId ?: return
        pendingSave?.cancel()
        pendingSave = scope.launch(ioDispatcher) {
            if (debounceMillis > 0) delay(debounceMillis)
            store.saveSnapshot(id, ImageProjectSnapshot.of(project))
        }
    }

    /** Records whether an export of [project] is running, immediately. */
    suspend fun markExport(project: ImageProject, running: Boolean) {
        val id = sessionId ?: return
        pendingSave?.cancel()
        withContext(ioDispatcher) { store.saveSnapshot(id, ImageProjectSnapshot.of(project), exportInProgress = running) }
    }

    /** The editor finished: the session is no longer needed. */
    fun end() {
        pendingSave?.cancel()
        sessionId?.let(store::deleteAsync)
        sessionId = null
        savedState.remove<String>(KEY_SESSION_ID)
    }

    /** Asset store of the current session, or `null` when no session could be created. */
    fun assets(): ProjectAssetStore? = sessionId?.let(store::assets)

    /** Deletes a previous session that could not be reopened and will not be restored. */
    fun discard(record: SessionRecord) {
        store.deleteAsync(record.sessionId)
    }

    /** The screen went away without a result; keep the files for the stale-session cleanup. */
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
