package com.naury.framekit.image.effect

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertWithMessage
import com.naury.framekit.core.effect.AdjustmentKind
import com.naury.framekit.core.effect.Adjustments
import com.naury.framekit.core.effect.ColorEffectSpec
import com.naury.framekit.core.effect.FilterCatalog
import com.naury.framekit.core.effect.FilterSelection
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs
import kotlin.random.Random

/**
 * Preview/export equivalence on a real GPU: the GL renderer must match the CPU reference for every
 * adjustment, every preset and the spatial effects. Target from the QA plan: MAE ≤ 3/255.
 */
@RunWith(AndroidJUnit4::class)
class GlColorEffectRendererTest {

    private val gl = GlColorEffectRenderer()

    @After
    fun release() = gl.release()

    @Test
    fun everyAdjustmentAtBothEndsMatchesTheCpuReference() {
        AdjustmentKind.entries.forEach { kind ->
            listOf(kind.minimum, kind.maximum).filter { it != 0.0 }.forEach { value ->
                assertMatches("$kind=$value", ColorEffectSpec.of(Adjustments().with(kind, value), FilterSelection(), 11L))
            }
        }
    }

    @Test
    fun everyPresetMatchesTheCpuReference() {
        FilterCatalog.presets.filter { it.id != FilterCatalog.ORIGINAL_ID }.forEach { preset ->
            listOf(0.4, 1.0).forEach { intensity ->
                assertMatches("${preset.id}@$intensity", ColorEffectSpec.of(Adjustments(), FilterSelection(preset.id, intensity), 3L))
            }
        }
    }

    @Test
    fun combinedAdjustmentsWithSpatialEffectsMatch() {
        val adjustments = Adjustments(
            exposure = 0.4, temperature = 0.3, tint = -0.2, shadows = 0.5, highlights = -0.4, contrast = 0.2,
            saturation = 0.3, sharpness = 0.8, fade = 0.3, vignette = 0.6, grain = 0.5,
        )
        assertMatches("combined", ColorEffectSpec.of(adjustments, FilterSelection("cinema", 0.8), 99L))
        assertMatches("combined without canvas", ColorEffectSpec.of(adjustments, FilterSelection("film01", 1.0), 99L), canvas = false)
    }

    @Test
    fun transparentPixelsKeepTheirAlpha() {
        assertMatches("alpha", ColorEffectSpec.of(Adjustments(brightness = 0.3, saturation = -0.5), FilterSelection(), 1L), alpha = true)
    }

    private fun assertMatches(label: String, spec: ColorEffectSpec, canvas: Boolean = true, alpha: Boolean = false) {
        val source = testImage(alpha)
        val expected = source.copy(Bitmap.Config.ARGB_8888, true).also { CpuColorEffectRenderer.apply(it, spec, canvas) }
        val actual = source.copy(Bitmap.Config.ARGB_8888, true).also { gl.apply(it, spec, canvas) }

        var total = 0L
        var worst = 0
        for (y in 0 until HEIGHT) for (x in 0 until WIDTH) {
            val e = expected.getPixel(x, y)
            val a = actual.getPixel(x, y)
            for (shift in intArrayOf(16, 8, 0)) {
                val diff = abs(((e shr shift) and 0xFF) - ((a shr shift) and 0xFF))
                total += diff
                worst = maxOf(worst, diff)
            }
            assertWithMessage("$label alpha at $x,$y").that(Color.alpha(a)).isEqualTo(Color.alpha(e))
        }
        val mae = total.toDouble() / (WIDTH * HEIGHT * 3)
        assertWithMessage("$label mean error").that(mae).isAtMost(1.0)
        assertWithMessage("$label max error").that(worst).isAtMost(8)
    }

    // 계조와 경계가 모두 있는 입력: 가로·세로 그라데이션, 대각선 경계, 약한 노이즈.
    private fun testImage(alpha: Boolean): Bitmap {
        val random = Random(5)
        val pixels = IntArray(WIDTH * HEIGHT) { i ->
            val x = i % WIDTH
            val y = i / WIDTH
            val edge = if (x + y > WIDTH / 2) 40 else 0
            val r = (x * 255 / WIDTH + random.nextInt(-6, 7)).coerceIn(0, 255)
            val g = (y * 255 / HEIGHT + edge).coerceIn(0, 255)
            val b = ((x + y) * 255 / (WIDTH + HEIGHT)).coerceIn(0, 255)
            val a = if (alpha) 64 + (x * 191 / WIDTH) else 255
            Color.argb(a, r, g, b)
        }
        return Bitmap.createBitmap(pixels, WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
    }

    private companion object {
        const val WIDTH = 257
        const val HEIGHT = 193
    }
}
