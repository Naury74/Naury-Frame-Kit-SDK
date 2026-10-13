package com.naury.framekit.picker

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.os.Build
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max

/**
 * 갤러리 칸과 미리보기에 쓸 축소 이미지를 읽고 메모리에 담아 둔다.
 *
 * Android 10 이상은 시스템이 만들어 둔 썸네일(`loadThumbnail`)을, 그 아래는 MediaStore 썸네일이나 표본 추출 디코딩을
 * 쓴다. 동시에 여러 장을 읽되 너무 많은 디코딩이 한꺼번에 돌지 않게 [PARALLELISM]개로 제한한다. 캐시는 앱 메모리의
 * 1/8까지 쓴다.
 */
internal class ThumbnailLoader(private val resolver: ContentResolver) {

    private val dispatcher = Dispatchers.IO.limitedParallelism(PARALLELISM)

    private val cache = object : LruCache<String, Bitmap>(cacheBytes()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }

    /** 이미 읽어 둔 이미지. 없으면 `null`. */
    fun cached(item: MediaItem, sizePx: Int): Bitmap? = cache.get(key(item, sizePx))

    /** [item]에 대해 이미 읽어 둔 이미지 중 가장 큰 것. 없으면 `null`. */
    fun anyCached(item: MediaItem): Bitmap? {
        val prefix = "${item.id}:${item.isVideo}:"
        return cache.snapshot().filterKeys { it.startsWith(prefix) }.values.maxByOrNull { it.width * it.height }
    }

    /** 긴 변이 대략 [sizePx]인 이미지. 읽지 못하면 `null`. */
    suspend fun load(item: MediaItem, sizePx: Int): Bitmap? {
        cached(item, sizePx)?.let { return it }
        return withContext(dispatcher) {
            try {
                decode(item, sizePx)?.also { cache.put(key(item, sizePx), it) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            } catch (_: OutOfMemoryError) {
                cache.evictAll()
                null
            }
        }
    }

    private fun decode(item: MediaItem, sizePx: Int): Bitmap? {
        // 크게 볼 때는 시스템 썸네일이 작아 흐리므로 원본을 표본 추출해 읽는다.
        if (!item.isVideo && sizePx > LEGACY_THUMBNAIL_PX) {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) fullImage(item, sizePx) else legacyImage(item, sizePx)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return resolver.loadThumbnail(item.uri, Size(sizePx, sizePx), null)
        return if (item.isVideo) legacyVideo(item) else legacyImage(item, sizePx)
    }

    // 긴 변이 [sizePx] 이상 남는 가장 큰 표본 비율로 읽는다. 회전 정보는 ImageDecoder가 반영한다.
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.P)
    private fun fullImage(item: MediaItem, sizePx: Int): Bitmap =
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, item.uri)) { decoder, info, _ ->
            var sample = 1
            while (max(info.size.width, info.size.height) / (sample * 2) >= sizePx) sample *= 2
            decoder.setTargetSampleSize(sample)
        }

    @Suppress("DEPRECATION")
    private fun legacyVideo(item: MediaItem): Bitmap? =
        MediaStore.Video.Thumbnails.getThumbnail(resolver, item.id, MediaStore.Video.Thumbnails.MINI_KIND, null)

    // 작은 칸은 MediaStore 썸네일로 충분하고, 미리보기처럼 크게 볼 때는 원본을 표본 추출해 읽는다.
    private fun legacyImage(item: MediaItem, sizePx: Int): Bitmap? {
        val bitmap = if (sizePx <= LEGACY_THUMBNAIL_PX) {
            @Suppress("DEPRECATION")
            MediaStore.Images.Thumbnails.getThumbnail(resolver, item.id, MediaStore.Images.Thumbnails.MINI_KIND, null)
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(item.uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= sizePx) sample *= 2
            resolver.openInputStream(item.uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
        } ?: return null
        if (item.orientation % 360 == 0) return bitmap
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(item.orientation.toFloat()) }, true)
        if (rotated !== bitmap) bitmap.recycle()
        return rotated
    }

    private fun key(item: MediaItem, sizePx: Int) = "${item.id}:${item.isVideo}:$sizePx"

    private companion object {
        const val PARALLELISM = 4
        const val LEGACY_THUMBNAIL_PX = 512

        fun cacheBytes(): Int = (Runtime.getRuntime().maxMemory() / 8).coerceIn(8L * 1024 * 1024, 96L * 1024 * 1024).toInt()
    }
}
