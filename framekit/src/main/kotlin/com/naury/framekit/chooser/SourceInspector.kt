package com.naury.framekit.chooser

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.util.Size
import android.webkit.MimeTypeMap
import androidx.core.graphics.scale
import androidx.exifinterface.media.ExifInterface
import com.naury.framekit.android.input.EditorInput
import com.naury.framekit.android.result.EditedMedia
import com.naury.framekit.core.model.MediaType
import com.naury.framekit.motion.codec.AnimatedImageProbe
import java.io.File
import java.io.InputStream
import java.util.Locale

/**
 * 선택 시트를 위해 원본을 살펴본다(종류, 작은 미리보기, 편집 없이 보낼 때의 정보). 디스크를 읽으므로 메인 스레드에서 부르지 않는다.
 */
internal class SourceInspector(private val context: Context) {

    private val resolver: ContentResolver = context.contentResolver

    /** 원본 종류. 사진·영상이 아니면 `null`. */
    fun kind(input: EditorInput): SourceKind? {
        val mime = mimeOf(input) ?: return null
        return when {
            mime.startsWith("video/") -> SourceKind.VIDEO
            mime.startsWith("audio/") -> SourceKind.AUDIO
            mime.startsWith("image/") -> if (open(input)?.let(AnimatedImageProbe::isAnimated) == true) SourceKind.ANIMATED else SourceKind.IMAGE
            else -> null
        }
    }

    /** 긴 변 [px] 안팎의 미리보기. 읽지 못하면 `null`. */
    fun thumbnail(input: EditorInput, px: Int): Bitmap? = runCatching {
        val uri = uriOf(input) ?: return null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && input is EditorInput.UriSource) {
            runCatching { return resolver.loadThumbnail(uri, Size(px, px), null) }
        }
        if (mimeOf(input)?.startsWith("audio/") == true) return null
        if (mimeOf(input)?.startsWith("video/") == true) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                retriever.getFrameAtTime(0)?.let { scaled(it, px) }
            } finally {
                runCatching { retriever.release() }
            }
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            open(input)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= px) sample *= 2
            val bitmap = open(input)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
            bitmap?.let { rotated(it, orientation(input)) }
        }
    }.getOrNull()

    /** 편집 없이 보낼 때 돌려줄 정보. */
    fun original(input: EditorInput, kind: SourceKind): EditedMedia? {
        val uri = uriOf(input) ?: return null
        val mime = mimeOf(input) ?: return null
        var width = 0
        var height = 0
        var duration: Long? = null
        if (kind == SourceKind.VIDEO || kind == SourceKind.AUDIO) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                if (rotation % 180 != 0) width = height.also { height = width }
                duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            } catch (_: Exception) {
                return null
            } finally {
                runCatching { retriever.release() }
            }
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            // 크기만 읽을 때 decodeStream은 늘 null을 돌려주므로 읽은 크기로 판단한다.
            (open(input) ?: return null).use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            width = bounds.outWidth
            height = bounds.outHeight
            val o = orientation(input)
            if (o == ExifInterface.ORIENTATION_ROTATE_90 || o == ExifInterface.ORIENTATION_ROTATE_270 ||
                o == ExifInterface.ORIENTATION_TRANSPOSE || o == ExifInterface.ORIENTATION_TRANSVERSE
            ) {
                width = height.also { height = width }
            }
        }
        val type = when (kind) {
            SourceKind.VIDEO -> MediaType.VIDEO
            SourceKind.AUDIO -> MediaType.AUDIO
            SourceKind.ANIMATED -> MediaType.ANIMATED_IMAGE
            SourceKind.IMAGE -> MediaType.IMAGE
        }
        return EditedMedia(uri, type, width, height, duration, mime, sizeOf(input), isOriginal = true)
    }

    private fun uriOf(input: EditorInput): Uri? = when (input) {
        is EditorInput.UriSource -> input.uri
        is EditorInput.FileSource -> Uri.fromFile(File(input.absolutePath))
        else -> null
    }

    private fun open(input: EditorInput): InputStream? = runCatching {
        when (input) {
            is EditorInput.UriSource -> resolver.openInputStream(input.uri)
            is EditorInput.FileSource -> File(input.absolutePath).inputStream()
            else -> null
        }
    }.getOrNull()

    private fun mimeOf(input: EditorInput): String? {
        val reported = (input as? EditorInput.UriSource)?.let { runCatching { resolver.getType(it.uri) }.getOrNull() }
        if (reported != null) return reported
        val name = when (input) {
            is EditorInput.UriSource -> input.uri.lastPathSegment
            is EditorInput.FileSource -> input.absolutePath
            else -> null
        } ?: return null
        val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT).ifEmpty { return null }
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
    }

    private fun sizeOf(input: EditorInput): Long = when (input) {
        is EditorInput.FileSource -> File(input.absolutePath).length()
        is EditorInput.UriSource -> runCatching {
            resolver.query(input.uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
            }
        }.getOrNull() ?: 0L
        else -> 0L
    }

    private fun orientation(input: EditorInput): Int =
        runCatching { open(input)?.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) } }.getOrNull()
            ?: ExifInterface.ORIENTATION_NORMAL

    private fun rotated(bitmap: Bitmap, orientation: Int): Bitmap {
        val degrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> return bitmap
        }
        val matrix = android.graphics.Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun scaled(bitmap: Bitmap, px: Int): Bitmap {
        val ratio = px.toFloat() / maxOf(bitmap.width, bitmap.height)
        if (ratio >= 1f) return bitmap
        return bitmap.scale(maxOf(1, (bitmap.width * ratio).toInt()), maxOf(1, (bitmap.height * ratio).toInt()))
    }
}
