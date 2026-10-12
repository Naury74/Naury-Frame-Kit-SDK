package com.naury.framekit.ui.motion.editor

import android.graphics.Bitmap
import android.util.LruCache
import com.naury.framekit.core.model.SourceId
import com.naury.framekit.motion.source.MotionSourceLoader

/**
 * 사진을 긴 변 [longEdge](px)로 풀어 몇 장만 기억해 둔다. 밀려난 비트맵은 그리는 중일 수 있어 recycle하지 않고 GC에 맡긴다.
 */
internal class SlideCache(private val loader: MotionSourceLoader, private val longEdge: Int, capacity: Int) {

    private val cache = object : LruCache<String, Bitmap>(capacity) {}

    @Synchronized
    fun get(id: String): Bitmap = cache.get(id) ?: loader.decodeStill(SourceId(id), longEdge).also { cache.put(id, it) }

    @Synchronized
    fun contains(id: String): Boolean = cache.get(id) != null

    @Synchronized
    fun clear() = cache.evictAll()
}
