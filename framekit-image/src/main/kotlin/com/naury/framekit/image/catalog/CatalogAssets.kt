package com.naury.framekit.image.catalog

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.util.Log
import android.util.LruCache
import com.naury.framekit.android.catalog.CatalogFile
import com.naury.framekit.android.catalog.EditorCatalog
import com.naury.framekit.core.effect.FilterCatalog
import com.naury.framekit.core.overlay.FontCatalog
import com.naury.framekit.core.overlay.StickerCatalog
import java.io.File
import java.io.InputStream

/**
 * 호스트 카탈로그를 프로세스 전체에 등록하고, 스티커 이미지와 폰트를 필요할 때 불러온다.
 *
 * 편집기·headless 처리기가 시작할 때 [install]을 부른다. 미리보기·저장·영상 오버레이가 모두 같은
 * 렌더러를 거치므로 어디서든 같은 이미지와 폰트로 그려진다. 읽지 못한 파일은 앱을 멈추지 않고 그리지
 * 않거나 기본 폰트로 대신한다.
 */
public object CatalogAssets {

    private val lock = Any()
    private var appContext: Context? = null
    private var catalog: EditorCatalog = EditorCatalog()
    private val stickers = object : LruCache<String, Bitmap>(STICKER_CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val typefaces = mutableMapOf<String, Typeface?>()

    /** 지금 등록된 카탈로그. UI가 이름과 보일 항목을 정할 때 쓴다. */
    public val current: EditorCatalog get() = synchronized(lock) { catalog }

    /**
     * [catalog]를 등록한다. 이전 등록은 대체된다. 파일은 여기서 읽지 않는다.
     *
     * @throws IllegalArgumentException 카탈로그가 [EditorCatalog.validate]를 통과하지 못할 때.
     */
    public fun install(context: Context, catalog: EditorCatalog) {
        require(catalog.validate().isValid) { "Invalid editor catalog" }
        synchronized(lock) {
            appContext = context.applicationContext
            if (catalog != this.catalog) {
                this.catalog = catalog
                stickers.evictAll()
                typefaces.clear()
            }
        }
        FilterCatalog.install(catalog.filters.map { it.toPreset() })
        FontCatalog.install(catalog.fonts.map { it.id })
        StickerCatalog.install(catalog.stickers.map { it.id })
    }

    /** 등록한 스티커 에셋 id([StickerCatalog.assetId])의 이미지. 없거나 읽지 못하면 `null`. */
    public fun sticker(assetId: String): Bitmap? {
        if (!assetId.startsWith(StickerCatalog.ASSET_PREFIX)) return null
        stickers.get(assetId)?.takeIf { !it.isRecycled }?.let { return it }
        val (context, entry) = synchronized(lock) {
            appContext to catalog.stickers.firstOrNull { StickerCatalog.assetId(it.id) == assetId }
        }
        if (context == null || entry == null) return null
        return runCatching { decodeSticker(context, entry.file) }
            .onFailure { Log.w(TAG, "sticker load failed: ${it.javaClass.simpleName}") }
            .getOrNull()
            ?.also { stickers.put(assetId, it) }
    }

    /** 등록한 폰트 id의 Typeface. 내장 폰트이거나 읽지 못하면 `null`. */
    public fun typeface(fontId: String): Typeface? = synchronized(lock) {
        if (fontId in typefaces) return typefaces[fontId]
        val context = appContext ?: return null
        val entry = catalog.fonts.firstOrNull { it.id == fontId } ?: return null
        val loaded = runCatching { loadTypeface(context, entry.file) }
            .onFailure { Log.w(TAG, "font load failed: ${it.javaClass.simpleName}") }
            .getOrNull()
        typefaces[fontId] = loaded
        loaded
    }

    private fun open(context: Context, file: CatalogFile): InputStream = when (file) {
        is CatalogFile.Asset -> context.assets.open(file.path)
        is CatalogFile.LocalFile -> File(file.absolutePath).inputStream()
        is CatalogFile.UriFile -> checkNotNull(context.contentResolver.openInputStream(file.uri)) { "No stream" }
    }

    private fun decodeSticker(context: Context, file: CatalogFile): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open(context, file).use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_STICKER_PX) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return open(context, file).use { BitmapFactory.decodeStream(it, null, options) }
    }

    private fun loadTypeface(context: Context, file: CatalogFile): Typeface? = when (file) {
        is CatalogFile.Asset -> Typeface.createFromAsset(context.assets, file.path)
        is CatalogFile.LocalFile -> Typeface.createFromFile(file.absolutePath)
        is CatalogFile.UriFile -> context.contentResolver.openFileDescriptor(file.uri, "r")?.use { Typeface.Builder(it.fileDescriptor).build() }
    }

    private const val TAG = "FrameKit"
    private const val MAX_STICKER_PX = 512
    private const val STICKER_CACHE_BYTES = 16 * 1024 * 1024
}
