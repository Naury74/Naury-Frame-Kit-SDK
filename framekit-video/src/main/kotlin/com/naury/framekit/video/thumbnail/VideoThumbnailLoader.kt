package com.naury.framekit.video.thumbnail

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.os.Build
import android.util.LruCache
import androidx.core.graphics.scale
import com.naury.framekit.android.source.SourceLocation
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable

/**
 * Timeline thumbnails extracted with [MediaMetadataRetriever].
 *
 * Frames are the nearest sync frame, which is fine for orientation but not for exact editing; seeks
 * and export never use these images. Results are cached in a 24 MiB LRU keyed by source, time bucket
 * and size, and extraction runs one frame at a time so a fast scroll cannot pile up decoders.
 */
public class VideoThumbnailLoader(
    context: Context,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1),
) : Closeable {

    private val appContext = context.applicationContext
    private val retrievers = mutableMapOf<String, MediaMetadataRetriever>()
    private val cache = object : LruCache<String, Bitmap>(CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    /**
     * @param sourceKey stable key of the source, for example its fingerprint.
     * @param timeUs source time; rounded to [BUCKET_US] for caching.
     * @param heightPx thumbnail height in pixels, at most [MAX_HEIGHT_PX].
     * @return the frame, or `null` when the source cannot be read.
     */
    public suspend fun load(location: SourceLocation, sourceKey: String, timeUs: Long, heightPx: Int): Bitmap? {
        val height = heightPx.coerceIn(1, MAX_HEIGHT_PX)
        val bucket = timeUs / BUCKET_US * BUCKET_US
        val key = "$sourceKey/$bucket/$height"
        cache.get(key)?.let { return it }
        return withContext(dispatcher) {
            cache.get(key) ?: runCatching { extract(location, sourceKey, bucket, height) }.getOrNull()?.also { cache.put(key, it) }
        }
    }

    override fun close() {
        synchronized(retrievers) {
            retrievers.values.forEach { runCatching { it.release() } }
            retrievers.clear()
        }
        cache.evictAll()
    }

    private fun extract(location: SourceLocation, sourceKey: String, timeUs: Long, height: Int): Bitmap? {
        val retriever = synchronized(retrievers) {
            retrievers.getOrPut(sourceKey) {
                MediaMetadataRetriever().apply {
                    when (location) {
                        is SourceLocation.Content -> setDataSource(appContext, location.uri)
                        is SourceLocation.LocalFile -> setDataSource(location.file.absolutePath)
                    }
                }
            }
        }
        val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: return null
        val sourceHeight = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: return null
        val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        val swapped = rotation == 90 || rotation == 270
        val uprightWidth = if (swapped) sourceHeight else width
        val uprightHeight = if (swapped) width else sourceHeight
        val targetWidth = maxOf(1, uprightWidth * height / uprightHeight)
        val frame = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            retriever.getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, targetWidth, height)
        } else {
            retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        } ?: return null
        return upright(frame, uprightWidth > uprightHeight, rotation).let { image ->
            if (image.height != height) image.scale(maxOf(1, image.width * height / image.height), height).also { if (it !== image) image.recycle() } else image
        }
    }

    // 기기에 따라 회전 메타데이터가 적용되지 않은 프레임이 오므로 가로·세로 방향이 다르면 직접 돌린다.
    private fun upright(frame: Bitmap, uprightLandscape: Boolean, rotation: Int): Bitmap {
        val frameLandscape = frame.width > frame.height
        if (rotation == 0 || frameLandscape == uprightLandscape) return frame
        val rotated = Bitmap.createBitmap(frame, 0, 0, frame.width, frame.height, Matrix().apply { postRotate(rotation.toFloat()) }, true)
        if (rotated !== frame) frame.recycle()
        return rotated
    }

    public companion object {
        public const val MAX_HEIGHT_PX: Int = 160
        public const val BUCKET_US: Long = 250_000L
        private const val CACHE_BYTES = 24 * 1024 * 1024
    }
}
