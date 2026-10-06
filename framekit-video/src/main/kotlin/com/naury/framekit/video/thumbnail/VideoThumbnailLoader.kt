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
 * [MediaMetadataRetriever]로 추출하는 타임라인 썸네일이다.
 *
 * 프레임은 가장 가까운 sync frame이라 위치 파악에는 충분하지만 정확한 편집에는 맞지 않는다. seek와
 * 내보내기는 이 이미지를 쓰지 않는다. 결과는 원본·시간 구간·크기를 키로 하는 24 MiB LRU에 캐시하고,
 * 빠르게 스크롤해도 디코더가 쌓이지 않도록 한 번에 한 프레임씩 추출한다.
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
     * @param sourceKey 원본의 안정적인 키(예: fingerprint).
     * @param timeUs 원본 시간. 캐시를 위해 [BUCKET_US] 단위로 내림한다.
     * @param heightPx 썸네일 높이(픽셀), 최대 [MAX_HEIGHT_PX].
     * @return 프레임. 원본을 읽을 수 없으면 `null`.
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
