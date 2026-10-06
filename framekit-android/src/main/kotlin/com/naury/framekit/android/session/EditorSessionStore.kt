package com.naury.framekit.android.session

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.net.toUri
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * Persists editing sessions under `files/framekit/sessions/<sessionId>/` so that committed edits
 * survive process death.
 *
 * Each session holds `descriptor.json` (how to reopen the source and its fingerprint) and
 * `project.snapshot` (the last committed edits). Files are written to a temporary name and renamed,
 * so a crash during a write leaves the previous snapshot intact. Only the Activity's
 * `SavedStateHandle` keeps the session id; no project data goes into a Bundle.
 *
 * The store only manages files in its own directory and read grants it took itself.
 */
public class EditorSessionStore(
    context: Context,
    private val clock: () -> Long = System::currentTimeMillis,
    private val background: Executor = sharedBackground,
) {
    private val contentResolver: ContentResolver = context.applicationContext.contentResolver

    /** Root directory of all sessions. */
    public val directory: File = File(context.applicationContext.filesDir, SESSIONS_DIRECTORY)

    /**
     * Creates a session for a source that was just opened successfully.
     *
     * For a `content://` source the store tries to take a persistable read grant so the source can
     * be reopened after process death. A grant the host already persisted is left alone and is never
     * released by the store.
     *
     * @return the new session id, or `null` when the session could not be written. Editing still
     *   works without a session; only restoration is unavailable.
     */
    public fun create(source: SourceReference, fingerprint: SourceFingerprint): String? {
        val sessionId = UUID.randomUUID().toString()
        val persisted = (source as? SourceReference.Content)?.let { takeGrantIfNeeded(it.uri.toUri()) } ?: false
        val descriptor = SessionDescriptor(SCHEMA_VERSION, sessionId, source, fingerprint, persisted, clock())
        return try {
            val dir = sessionDir(sessionId)
            if (!dir.mkdirs() && !dir.isDirectory) throw IOException("Could not create session directory")
            writeAtomically(File(dir, DESCRIPTOR_FILE), json.encodeToString(SessionDescriptor.serializer(), descriptor))
            writeAtomically(File(dir, SNAPSHOT_FILE), json.encodeToString(SessionSnapshotFile.serializer(), emptySnapshot()))
            activeSessions += sessionId
            sessionId
        } catch (error: IOException) {
            log("create", error)
            if (persisted) releaseGrant(source)
            null
        }
    }

    /**
     * Reads a session. Returns `null` when it does not exist, is from an unknown schema version or
     * cannot be parsed; a damaged session is deleted so it is not offered again.
     */
    public fun load(sessionId: String): SessionRecord? {
        val dir = sessionDir(sessionId)
        val descriptorFile = File(dir, DESCRIPTOR_FILE)
        if (!descriptorFile.isFile) return null
        return try {
            val descriptor = json.decodeFromString(SessionDescriptor.serializer(), descriptorFile.readText())
            val snapshot = File(dir, SNAPSHOT_FILE).takeIf(File::isFile)
                ?.let { json.decodeFromString(SessionSnapshotFile.serializer(), it.readText()) }
            if (descriptor.schemaVersion != SCHEMA_VERSION || (snapshot != null && snapshot.schemaVersion != SCHEMA_VERSION)) {
                delete(sessionId)
                return null
            }
            activeSessions += sessionId
            SessionRecord(
                sessionId = sessionId,
                source = descriptor.source,
                fingerprint = descriptor.fingerprint,
                snapshot = snapshot?.image,
                exportWasInterrupted = snapshot?.exportInProgress == true,
            )
        } catch (error: SerializationException) {
            log("load", error)
            delete(sessionId)
            null
        } catch (error: IllegalArgumentException) {
            log("load", error)
            delete(sessionId)
            null
        } catch (error: IOException) {
            log("load", error)
            null
        }
    }

    /**
     * Replaces the committed snapshot. [exportInProgress] marks a running export so that an
     * interruption by process death can be reported on the next launch.
     *
     * @return `false` when writing failed; the previous snapshot is still on disk.
     */
    public fun saveSnapshot(sessionId: String, snapshot: ImageProjectSnapshot?, exportInProgress: Boolean = false): Boolean {
        val dir = sessionDir(sessionId)
        if (!dir.isDirectory) return false
        val file = SessionSnapshotFile(SCHEMA_VERSION, clock(), exportInProgress, snapshot)
        return try {
            writeAtomically(File(dir, SNAPSHOT_FILE), json.encodeToString(SessionSnapshotFile.serializer(), file))
            true
        } catch (error: IOException) {
            log("save", error)
            false
        }
    }

    /** Deletes a session and releases the read grant the store took for it. Failures are ignored. */
    public fun delete(sessionId: String) {
        val dir = sessionDir(sessionId)
        runCatching {
            val descriptor = json.decodeFromString(SessionDescriptor.serializer(), File(dir, DESCRIPTOR_FILE).readText())
            if (descriptor.persistedGrant) releaseGrant(descriptor.source)
        }
        dir.deleteRecursively()
        activeSessions -= sessionId
    }

    /** [delete] on a background thread that outlives the calling screen. */
    public fun deleteAsync(sessionId: String) {
        activeSessions -= sessionId
        background.execute { delete(sessionId) }
    }

    /** Marks a session as no longer in use by this process without deleting it. */
    public fun release(sessionId: String) {
        activeSessions -= sessionId
    }

    /**
     * Deletes sessions that were interrupted more than [maxAgeMillis] ago. Sessions in use by this
     * process are kept. Only directories created by this store are touched.
     */
    public fun deleteStale(maxAgeMillis: Long = STALE_SESSION_AGE_MILLIS) {
        val now = clock()
        directory.listFiles()?.forEach { dir ->
            if (!dir.isDirectory || dir.name in activeSessions) return@forEach
            val lastWrite = maxOf(File(dir, SNAPSHOT_FILE).lastModified(), File(dir, DESCRIPTOR_FILE).lastModified(), dir.lastModified())
            if (now - lastWrite > maxAgeMillis) delete(dir.name)
        }
    }

    private fun sessionDir(sessionId: String): File {
        // 저장소 밖 경로를 가리키는 id로 다른 파일을 지우지 않도록 UUID 형식만 허용한다.
        require(SESSION_ID_PATTERN.matches(sessionId)) { "Invalid session id" }
        return File(directory, sessionId)
    }

    private fun writeAtomically(target: File, content: String) {
        val temp = File(target.parentFile, "${target.name}.tmp")
        temp.writeText(content)
        if (!temp.renameTo(target)) {
            temp.delete()
            throw IOException("Could not replace ${target.name}")
        }
    }

    private fun emptySnapshot() = SessionSnapshotFile(SCHEMA_VERSION, clock(), exportInProgress = false, image = null)

    private fun takeGrantIfNeeded(uri: Uri): Boolean {
        val alreadyPersisted = contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
        if (alreadyPersisted) return false
        return try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            true
        } catch (_: SecurityException) {
            // persistable grant를 주지 않는 provider다. 이 경우 프로세스가 살아 있는 동안만 다시 열 수 있다.
            false
        }
    }

    private fun releaseGrant(source: SourceReference) {
        val uri = (source as? SourceReference.Content)?.uri?.toUri() ?: return
        runCatching { contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }

    private fun log(action: String, error: Exception) {
        Log.w(TAG, "session $action failed: ${error.javaClass.simpleName}")
    }

    public companion object {
        internal const val SESSIONS_DIRECTORY = "framekit/sessions"
        internal const val DESCRIPTOR_FILE = "descriptor.json"
        internal const val SNAPSHOT_FILE = "project.snapshot"
        internal const val SCHEMA_VERSION = 1

        /** Interrupted sessions older than this are deleted: 7 days. */
        public const val STALE_SESSION_AGE_MILLIS: Long = 7L * 24 * 60 * 60 * 1000

        private const val TAG = "FrameKit"
        private val SESSION_ID_PATTERN = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        private val json = Json { ignoreUnknownKeys = true }
        private val activeSessions: MutableSet<String> = ConcurrentHashMap.newKeySet()
        private val sharedBackground: Executor by lazy {
            Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "framekit-session").apply { isDaemon = true } }
        }
    }
}
