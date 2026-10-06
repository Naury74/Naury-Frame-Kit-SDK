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
 * 확정된 편집이 프로세스 종료 후에도 남도록 편집 세션을 `files/framekit/sessions/<sessionId>/`에 저장한다.
 *
 * 각 세션에는 `descriptor.json`(원본을 다시 여는 방법과 fingerprint)과 `project.snapshot`(마지막으로
 * 확정된 편집)이 있다. 파일은 임시 이름으로 쓴 뒤 rename하므로, 쓰는 도중 크래시가 나도 이전 snapshot은
 * 그대로 남는다. Activity의 `SavedStateHandle`에는 세션 id만 두고, 프로젝트 데이터는 Bundle에 넣지 않는다.
 *
 * 저장소는 자신의 디렉터리에 있는 파일과 자신이 직접 얻은 읽기 권한만 관리한다.
 */
public class EditorSessionStore(
    context: Context,
    private val clock: () -> Long = System::currentTimeMillis,
    private val background: Executor = sharedBackground,
) {
    private val contentResolver: ContentResolver = context.applicationContext.contentResolver

    /** 모든 세션의 루트 디렉터리. */
    public val directory: File = File(context.applicationContext.filesDir, SESSIONS_DIRECTORY)

    /**
     * 방금 성공적으로 연 원본에 대한 세션을 만든다.
     *
     * `content://` 원본이면 프로세스 종료 후에도 다시 열 수 있도록 persistable 읽기 권한을 얻으려고 시도한다.
     * 호스트가 이미 영구 보존한 권한은 건드리지 않으며, 저장소가 해제하지도 않는다.
     *
     * @return 새 세션 id. 세션을 쓸 수 없으면 `null`이다. 세션이 없어도 편집은 가능하며 복원만 할 수 없다.
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
     * 세션을 읽는다. 세션이 없거나, 알 수 없는 schema 버전이거나, 파싱할 수 없으면 `null`을 반환한다.
     * 손상된 세션은 다시 제시되지 않도록 삭제한다.
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
                videoSnapshot = snapshot?.video,
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
     * 확정된 snapshot을 교체한다. [exportInProgress]는 실행 중인 export를 표시해, 프로세스 종료로
     * 중단되었을 때 다음 실행에서 알릴 수 있게 한다.
     *
     * @return 쓰기에 실패하면 `false`. 이전 snapshot은 디스크에 그대로 남는다.
     */
    public fun saveSnapshot(sessionId: String, snapshot: ImageProjectSnapshot?, exportInProgress: Boolean = false): Boolean =
        write(sessionId, SessionSnapshotFile(SCHEMA_VERSION, clock(), exportInProgress, snapshot))

    /** [saveSnapshot]의 영상 버전. */
    public fun saveVideoSnapshot(sessionId: String, snapshot: VideoProjectSnapshot?, exportInProgress: Boolean = false): Boolean =
        write(sessionId, SessionSnapshotFile(SCHEMA_VERSION, clock(), exportInProgress, image = null, video = snapshot))

    private fun write(sessionId: String, file: SessionSnapshotFile): Boolean {
        val dir = sessionDir(sessionId)
        if (!dir.isDirectory) return false
        return try {
            writeAtomically(File(dir, SNAPSHOT_FILE), json.encodeToString(SessionSnapshotFile.serializer(), file))
            true
        } catch (error: IOException) {
            log("save", error)
            false
        }
    }

    /** 세션 폴더 안의 asset 저장소. 세션과 함께 삭제된다. */
    public fun assets(sessionId: String): ProjectAssetStore = ProjectAssetStore(File(sessionDir(sessionId), ASSETS_DIRECTORY))

    /** 세션을 삭제하고 저장소가 그 세션을 위해 얻은 읽기 권한을 해제한다. 실패는 무시한다. */
    public fun delete(sessionId: String) {
        val dir = sessionDir(sessionId)
        runCatching {
            val descriptor = json.decodeFromString(SessionDescriptor.serializer(), File(dir, DESCRIPTOR_FILE).readText())
            if (descriptor.persistedGrant) releaseGrant(descriptor.source)
        }
        dir.deleteRecursively()
        activeSessions -= sessionId
    }

    /** 호출한 화면보다 오래 사는 백그라운드 스레드에서 [delete]를 수행한다. */
    public fun deleteAsync(sessionId: String) {
        activeSessions -= sessionId
        background.execute { delete(sessionId) }
    }

    /** 세션을 삭제하지 않고 이 프로세스에서 더 이상 사용하지 않는다고 표시한다. */
    public fun release(sessionId: String) {
        activeSessions -= sessionId
    }

    /**
     * 중단된 지 [maxAgeMillis]보다 오래된 세션을 삭제한다. 이 프로세스가 사용 중인 세션은 남긴다.
     * 이 저장소가 만든 디렉터리만 건드린다.
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
        internal const val ASSETS_DIRECTORY = "assets"
        internal const val SCHEMA_VERSION = 1

        /** 이보다 오래된 중단 세션은 삭제한다: 7일. */
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
