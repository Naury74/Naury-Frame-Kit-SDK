package com.naury.framekit.image.document

import android.graphics.Bitmap
import android.graphics.Color
import com.google.common.truth.Truth.assertThat
import com.naury.framekit.core.document.ScanMode
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScanEnhancerTest {

    // 왼쪽은 그늘져 어둡고(종이 120) 오른쪽은 밝은(종이 230) 사진. 군데군데 글자 대신 어두운 막대가 있다.
    private fun shadedPage(): Bitmap = Bitmap.createBitmap(480, 240, Bitmap.Config.ARGB_8888).apply {
        for (y in 0 until height) for (x in 0 until width) {
            val paper = 120 + 110 * x / (width - 1)
            val text = y in 100..112 && (x / 30) % 2 == 0
            val v = if (text) paper / 4 else paper
            setPixel(x, y, Color.rgb(v, v, (v * 0.95).toInt()))
        }
    }

    @Test
    fun `color scan whitens shaded paper and keeps text dark`() {
        val page = shadedPage()
        ScanEnhancer.enhance(page, ScanMode.COLOR)
        // 그늘진 왼쪽 종이와 밝은 오른쪽 종이 모두 거의 흰색이 된다.
        assertThat(Color.red(page.getPixel(20, 40))).isAtLeast(235)
        assertThat(Color.red(page.getPixel(460, 40))).isAtLeast(235)
        // 글자는 어둡게 남는다(그늘 쪽도).
        assertThat(Color.red(page.getPixel(10, 106))).isAtMost(80)
        assertThat(Color.red(page.getPixel(430, 106))).isAtMost(80)
    }

    @Test
    fun `black and white scan leaves only ink`() {
        val page = shadedPage()
        ScanEnhancer.enhance(page, ScanMode.BLACK_WHITE)
        assertThat(page.getPixel(20, 40)).isEqualTo(Color.WHITE)
        assertThat(page.getPixel(10, 106)).isEqualTo(Color.BLACK)
        val gray = shadedPage().also { ScanEnhancer.enhance(it, ScanMode.GRAYSCALE) }.getPixel(460, 40)
        assertThat(Color.red(gray)).isEqualTo(Color.blue(gray))
    }

    @Test
    fun `original mode changes nothing`() {
        val page = shadedPage()
        val before = page.getPixel(20, 40)
        ScanEnhancer.enhance(page, ScanMode.ORIGINAL)
        assertThat(page.getPixel(20, 40)).isEqualTo(before)
    }
}
