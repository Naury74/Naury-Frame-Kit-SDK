package com.naury.framekit.android.output

import android.content.Context
import android.net.Uri
import android.os.storage.StorageManager
import androidx.core.content.FileProvider
import com.naury.framekit.android.result.EditorErrorCode
import com.naury.framekit.android.result.FrameKitException
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * export 결과를 `files/framekit/exports/`에 쓰고 SDK FileProvider로 노출한다.
 *
 * partial 파일을 최종 파일과 같은 위치에 두어, 확정이 같은 volume 안의 rename으로 끝나게 한다.
 * 숨김 `.partial` 파일은 호스트에 반환하지 않는다.
 *
 * @param availableBytes 플랫폼 여유 공간 조회를 대체한다. 테스트나 자체 저장 용량 한도를 둔 호스트용이다.
 *   `null`이면 `StorageManager.getAllocatableBytes`를 사용한다.
 */
public class AppFileOutputStore(
    context: Context,
    private val availableBytes: ((File) -> Long)? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val appContext = context.applicationContext

    /** 확정된 결과와 진행 중인 partial 파일을 담는 디렉터리. */
    public val directory: File = File(appContext.filesDir, EXPORT_DIRECTORY)

    private val authority: String = authorityFor(appContext)

    /**
     * export용 빈 숨김 partial 파일을 만든다.
     *
     * @throws FrameKitException 디렉터리를 만들 수 없을 때 `OUTPUT_WRITE_FAILED` 코드로 던진다.
     */
    public fun createPartial(extension: String): File {
        ensureDirectory()
        val file = File(directory, ".${UUID.randomUUID()}.$extension$PARTIAL_SUFFIX")
        if (!file.createNewFile()) throw FrameKitException(EditorErrorCode.OUTPUT_WRITE_FAILED, "Partial file exists")
        return file
    }

    /**
     * [directory]가 있는 volume에 쓸 수 있는 바이트 수. 시스템이 필요 시 비울 수 있는 캐시 공간을 포함한다.
     */
    public fun allocatableBytes(): Long {
        ensureDirectory()
        availableBytes?.let { return it(directory) }
        val storageManager = appContext.getSystemService(StorageManager::class.java)
        return try {
            storageManager.getAllocatableBytes(storageManager.getUuidForPath(directory))
        } catch (_: IOException) {
            fallbackUsableSpace()
        } catch (_: RuntimeException) {
            // 일부 기기·테스트 환경은 volume 조회에서 런타임 예외를 던진다. 저장을 막지 않도록 대체값을 쓴다.
            fallbackUsableSpace()
        }
    }

    /**
     * 완료된 partial 파일을 최종 이름으로 바꾼다.
     *
     * @throws FrameKitException rename에 실패하면 `OUTPUT_WRITE_FAILED` 코드로 던진다. 이때 partial
     *   파일은 삭제된다.
     */
    public fun publish(partial: File, extension: String): File {
        val target = uniqueFinalFile(extension)
        if (!partial.renameTo(target)) {
            partial.delete()
            throw FrameKitException(EditorErrorCode.OUTPUT_WRITE_FAILED, "Could not publish output")
        }
        return target
    }

    /** 확정된 파일의 `content://` Uri. */
    public fun uriFor(file: File): Uri = FileProvider.getUriForFile(appContext, authority, file)

    /**
     * 중단된 export가 남긴 partial 파일 중 [maxAgeMillis]보다 오래된 것을 삭제한다.
     *
     * [createPartial]이 만든 이름만 건드린다. 정리 작업이 편집기를 죽이지 않도록 실패는 무시한다.
     */
    public fun deleteStalePartials(maxAgeMillis: Long = STALE_PARTIAL_AGE_MILLIS) {
        val now = clock()
        directory.listFiles()?.forEach { file ->
            if (file.isFile && file.name.startsWith(".") && file.name.endsWith(PARTIAL_SUFFIX) &&
                now - file.lastModified() > maxAgeMillis
            ) {
                file.delete()
            }
        }
    }

    /** 확정된 결과를 삭제한다. [uri]가 이 저장소의 것이 아니면 `false`를 반환한다. */
    public fun delete(uri: Uri): Boolean {
        if (uri.authority != authority) return false
        val name = uri.lastPathSegment ?: return false
        val file = File(directory, name)
        if (file.parentFile != directory || name.startsWith(".")) return false
        return file.delete()
    }

    @Suppress("UsableSpace") // StorageManager로 volume을 확인할 수 없을 때만 쓰는 대체값이다.
    private fun fallbackUsableSpace(): Long = directory.usableSpace

    private fun uniqueFinalFile(extension: String): File {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(clock()))
        var index = 0
        while (true) {
            val suffix = if (index == 0) "" else "_$index"
            val file = File(directory, "FrameKit_$stamp$suffix.$extension")
            if (!file.exists()) return file
            index++
        }
    }

    private fun ensureDirectory() {
        if (!directory.isDirectory && !directory.mkdirs()) {
            throw FrameKitException(EditorErrorCode.OUTPUT_WRITE_FAILED, "Could not create export directory")
        }
    }

    public companion object {
        internal const val EXPORT_DIRECTORY = "framekit/exports"
        internal const val PARTIAL_SUFFIX = ".partial"
        internal const val STALE_PARTIAL_AGE_MILLIS = 24L * 60 * 60 * 1000

        /** export 파일에 쓰는 FileProvider authority: `<applicationId>.framekit.files`. */
        public fun authorityFor(context: Context): String = "${context.packageName}.framekit.files"
    }
}
