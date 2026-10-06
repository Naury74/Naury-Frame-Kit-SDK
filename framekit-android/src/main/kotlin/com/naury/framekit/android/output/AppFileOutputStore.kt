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
 * Writes exports to `files/framekit/exports/` and exposes them through the SDK FileProvider.
 *
 * Partial files live next to the final files so that publishing is a rename on the same volume.
 * Hidden `.partial` files are never returned to the host.
 *
 * @param availableBytes replaces the platform free-space query, for tests and hosts with their own
 *   storage quota. `null` uses `StorageManager.getAllocatableBytes`.
 */
public class AppFileOutputStore(
    context: Context,
    private val availableBytes: ((File) -> Long)? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val appContext = context.applicationContext

    /** Directory that holds published results and in-progress partial files. */
    public val directory: File = File(appContext.filesDir, EXPORT_DIRECTORY)

    private val authority: String = authorityFor(appContext)

    /**
     * Creates an empty hidden partial file for an export.
     *
     * @throws FrameKitException with `OUTPUT_WRITE_FAILED` when the directory cannot be created.
     */
    public fun createPartial(extension: String): File {
        ensureDirectory()
        val file = File(directory, ".${UUID.randomUUID()}.$extension$PARTIAL_SUFFIX")
        if (!file.createNewFile()) throw FrameKitException(EditorErrorCode.OUTPUT_WRITE_FAILED, "Partial file exists")
        return file
    }

    /**
     * Bytes that can be written to the volume that holds [directory], including cache space the
     * system can free on demand.
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
     * Renames a finished partial file to its final name.
     *
     * @throws FrameKitException with `OUTPUT_WRITE_FAILED` when the rename fails. The partial file is
     *   deleted in that case.
     */
    public fun publish(partial: File, extension: String): File {
        val target = uniqueFinalFile(extension)
        if (!partial.renameTo(target)) {
            partial.delete()
            throw FrameKitException(EditorErrorCode.OUTPUT_WRITE_FAILED, "Could not publish output")
        }
        return target
    }

    /** `content://` Uri for a published file. */
    public fun uriFor(file: File): Uri = FileProvider.getUriForFile(appContext, authority, file)

    /**
     * Deletes partial files older than [maxAgeMillis] that an interrupted export left behind.
     *
     * Only names created by [createPartial] are touched. Failures are ignored so that cleanup never
     * crashes the editor.
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

    /** Deletes a published result. Returns `false` when [uri] does not belong to this store. */
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

        /** FileProvider authority used for exported files: `<applicationId>.framekit.files`. */
        public fun authorityFor(context: Context): String = "${context.packageName}.framekit.files"
    }
}
