package com.naury.framekit.image.overlay

import android.graphics.Bitmap
import android.graphics.Color
import com.google.common.truth.Truth.assertThat
import com.naury.framekit.core.geometry.PointN
import com.naury.framekit.core.geometry.RectN
import com.naury.framekit.core.overlay.MaskShape
import com.naury.framekit.core.overlay.PrivacyEffect
import com.naury.framekit.core.overlay.PrivacyMask
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PrivacyRendererTest {

    private fun gradient(width: Int, height: Int): Bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
        for (y in 0 until height) for (x in 0 until width) setPixel(x, y, Color.rgb(x * 255 / width, y * 255 / height, 128))
    }

    @Test
    fun `Q06 mosaic blocks sit on a grid anchored at the canvas origin`() {
        val bitmap = gradient(100, 100)
        val original = bitmap.copy(Bitmap.Config.ARGB_8888, false)
        val mask = PrivacyMask("m", MaskShape.Rectangle(RectN(0.13, 0.13, 0.57, 0.57)), PrivacyEffect.Mosaic(0.1))

        PrivacyRenderer.apply(bitmap, listOf(mask))

        // 블록은 10px 격자. (20..29, 20..29) 블록 안은 한 색이고, 경계 너머 블록과는 다르다.
        val block = bitmap.getPixel(20, 20)
        for (y in 20 until 30) for (x in 20 until 30) assertThat(bitmap.getPixel(x, y)).isEqualTo(block)
        assertThat(bitmap.getPixel(30, 20)).isNotEqualTo(block)
        // 마스크 밖은 그대로다.
        assertThat(bitmap.getPixel(80, 80)).isEqualTo(original.getPixel(80, 80))
        assertThat(bitmap.getPixel(5, 5)).isEqualTo(original.getPixel(5, 5))
    }

    @Test
    fun `Q06 mosaic block size follows the canvas so preview and export match`() {
        val small = gradient(100, 100)
        val large = gradient(400, 400)
        val mask = PrivacyMask("m", MaskShape.Rectangle(RectN(0.0, 0.0, 1.0, 1.0)), PrivacyEffect.Mosaic(0.1))

        PrivacyRenderer.apply(small, listOf(mask))
        PrivacyRenderer.apply(large, listOf(mask))

        // 같은 정규화 위치의 블록 평균색은 해상도와 관계없이 거의 같다.
        listOf(0.05 to 0.05, 0.35 to 0.75, 0.95 to 0.45).forEach { (u, v) ->
            val a = small.getPixel((u * 100).toInt(), (v * 100).toInt())
            val b = large.getPixel((u * 400).toInt(), (v * 400).toInt())
            assertThat(Color.red(a)).isWithin(3).of(Color.red(b))
            assertThat(Color.green(a)).isWithin(3).of(Color.green(b))
        }
    }

    @Test
    fun `blur changes only the masked area and overlapping masks do not blur twice`() {
        val stripes = Bitmap.createBitmap(120, 120, Bitmap.Config.ARGB_8888).apply {
            for (y in 0 until 120) for (x in 0 until 120) setPixel(x, y, if ((x / 4) % 2 == 0) Color.BLACK else Color.WHITE)
        }
        val once = stripes.copy(Bitmap.Config.ARGB_8888, true)
        val twice = stripes.copy(Bitmap.Config.ARGB_8888, true)
        val mask = PrivacyMask("a", MaskShape.Ellipse(RectN(0.25, 0.25, 0.75, 0.75)), PrivacyEffect.Blur(0.03))

        PrivacyRenderer.apply(once, listOf(mask))
        PrivacyRenderer.apply(twice, listOf(mask, mask.copy(id = "b")))

        val center = once.getPixel(60, 60)
        assertThat(Color.red(center)).isIn(40..215)
        assertThat(once.getPixel(2, 2)).isEqualTo(stripes.getPixel(2, 2))
        for (y in 40 until 80 step 3) for (x in 40 until 80 step 3) {
            assertThat(Color.red(twice.getPixel(x, y))).isWithin(1).of(Color.red(once.getPixel(x, y)))
        }
    }

    @Test
    fun `large blur radius is processed without huge kernels`() {
        val bitmap = gradient(800, 600)
        val mask = PrivacyMask("m", MaskShape.Brush(listOf(PointN(0.2, 0.5), PointN(0.8, 0.5)), 0.1), PrivacyEffect.Blur(0.15))

        PrivacyRenderer.apply(bitmap, listOf(mask))

        assertThat(bitmap.width).isEqualTo(800)
    }
}
