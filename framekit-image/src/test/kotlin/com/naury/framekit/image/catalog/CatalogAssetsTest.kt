package com.naury.framekit.image.catalog

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.core.graphics.createBitmap
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.naury.framekit.android.catalog.CatalogFile
import com.naury.framekit.android.catalog.CustomFilter
import com.naury.framekit.android.catalog.CustomSticker
import com.naury.framekit.android.catalog.EditorCatalog
import com.naury.framekit.core.effect.FilterCatalog
import com.naury.framekit.core.geometry.PointN
import com.naury.framekit.core.model.PixelSize
import com.naury.framekit.core.overlay.ImageOverlay
import com.naury.framekit.core.overlay.OverlayTransform
import com.naury.framekit.core.overlay.StickerCatalog
import com.naury.framekit.image.overlay.OverlayRenderer
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CatalogAssetsTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()

    @After
    fun tearDown() {
        CatalogAssets.install(context, EditorCatalog())
    }

    @Test
    fun `host sticker image is drawn with its own aspect ratio`() {
        val file = File(context.cacheDir, "logo.png")
        file.outputStream().use { createBitmap(200, 100).apply { eraseColor(Color.RED) }.compress(Bitmap.CompressFormat.PNG, 100, it) }
        CatalogAssets.install(context, EditorCatalog(stickers = listOf(CustomSticker("logo", "Logo", CatalogFile.LocalFile(file.absolutePath)))))
        val renderer = OverlayRenderer()
        val sticker = ImageOverlay.Sticker("s", StickerCatalog.assetId("logo"), widthRatio = 0.5, transform = OverlayTransform(PointN(0.5, 0.5)))

        val layout = renderer.layout(sticker, PixelSize(400, 400))
        val canvas = createBitmap(400, 400)
        renderer.draw(Canvas(canvas), PixelSize(400, 400), listOf(sticker), emptyList())

        assertThat(layout.width).isEqualTo(200f)
        assertThat(layout.height).isEqualTo(100f)
        assertThat(canvas.getPixel(200, 200)).isEqualTo(Color.RED)
        assertThat(Color.alpha(canvas.getPixel(200, 120))).isEqualTo(0)
    }

    @Test
    fun `host filters are installed and replaced by the next catalog`() {
        CatalogAssets.install(context, EditorCatalog(filters = listOf(CustomFilter("brand", "Brand", saturation = -1.0))))
        assertThat(FilterCatalog.find("brand")).isNotNull()

        CatalogAssets.install(context, EditorCatalog())
        assertThat(FilterCatalog.find("brand")).isNull()
    }

    @Test
    fun `invalid catalogs are rejected`() {
        val clash = EditorCatalog(filters = listOf(CustomFilter("mono", "Mono")))
        val outOfRange = EditorCatalog(filters = listOf(CustomFilter("x", "X", contrast = 3.0)))

        assertThat(clash.validate().isValid).isFalse()
        assertThat(outOfRange.validate().isValid).isFalse()
        assertThrows(IllegalArgumentException::class.java) { CatalogAssets.install(context, clash) }
    }

    @Test
    fun `missing sticker files draw nothing instead of failing`() {
        CatalogAssets.install(context, EditorCatalog(stickers = listOf(CustomSticker("gone", "Gone", CatalogFile.LocalFile("/nope.png")))))

        assertThat(CatalogAssets.sticker(StickerCatalog.assetId("gone"))).isNull()
    }
}
