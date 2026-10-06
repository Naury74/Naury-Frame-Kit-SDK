package com.naury.framekit.core.effect

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ColorEffectProcessorTest {

    private fun spec(adjustments: Adjustments = Adjustments(), filter: FilterSelection = FilterSelection(), seed: Long = 7L) =
        ColorEffectSpec.of(adjustments, filter, seed)

    private fun gray(level: Int) = (0xFF shl 24) or (level shl 16) or (level shl 8) or level

    private fun rgb(color: Int) = Triple((color shr 16) and 0xFF, (color shr 8) and 0xFF, color and 0xFF)

    private fun process(pixels: IntArray, width: Int, height: Int, spec: ColorEffectSpec) =
        pixels.copyOf().also { ColorEffectProcessor.process(it, width, height, spec) }

    @Test
    fun `default adjustments leave every 8 bit value unchanged`() {
        val pixels = IntArray(256) { (0xFF shl 24) or (it shl 16) or ((255 - it) shl 8) or (it / 2) }

        assertThat(spec().isIdentity).isTrue()
        assertThat(process(pixels, 16, 16, spec())).isEqualTo(pixels)
    }

    @Test
    fun `one stop of exposure doubles linear light`() {
        val out = process(intArrayOf(gray(100)), 1, 1, spec(Adjustments(exposure = 1.0)))
        val linearIn = ColorEffectProcessor.srgbToLinear(100 / 255f)
        val linearOut = ColorEffectProcessor.srgbToLinear(rgb(out[0]).first / 255f)

        assertThat(linearOut).isWithin(0.01f).of(linearIn * 2)
    }

    @Test
    fun `saturation minus one removes color and keeps luminance`() {
        val out = process(intArrayOf(0xFFCC3344.toInt()), 1, 1, spec(Adjustments(saturation = -1.0)))
        val (r, g, b) = rgb(out[0])

        assertThat(r).isEqualTo(g)
        assertThat(g).isEqualTo(b)
    }

    @Test
    fun `white balance keeps white white and warms mid gray`() {
        val warm = spec(Adjustments(temperature = 1.0))
        val (wr, wg, wb) = rgb(process(intArrayOf(gray(128)), 1, 1, warm)[0])

        assertThat(wr).isGreaterThan(wb)
        val luma = 0.2126 * warm.whiteBalance.red + 0.7152 * warm.whiteBalance.green + 0.0722 * warm.whiteBalance.blue
        assertThat(luma).isWithin(1e-5).of(1.0)
        assertThat(wg).isAtLeast(0)
    }

    @Test
    fun `contrast pushes values away from middle gray`() {
        val out = process(intArrayOf(gray(64), gray(192)), 2, 1, spec(Adjustments(contrast = 0.5)))

        assertThat(rgb(out[0]).first).isLessThan(64)
        assertThat(rgb(out[1]).first).isGreaterThan(192)
    }

    @Test
    fun `filter intensity mixes linearly between original and full preset`() {
        val source = intArrayOf(0xFF7090B0.toInt())
        val none = process(source, 1, 1, spec(filter = FilterSelection("mono", 0.0)))
        val half = process(source, 1, 1, spec(filter = FilterSelection("mono", 0.5)))
        val full = process(source, 1, 1, spec(filter = FilterSelection("mono", 1.0)))

        assertThat(none).isEqualTo(source)
        val (fr, fg, fb) = rgb(full[0])
        assertThat(fr).isEqualTo(fg)
        assertThat(fg).isEqualTo(fb)
        assertThat(rgb(half[0]).third).isWithin(2).of((rgb(source[0]).third + fb) / 2)
    }

    @Test
    fun `vignette darkens corners but not the center`() {
        val size = 101
        val pixels = IntArray(size * size) { gray(200) }
        val out = process(pixels, size, size, spec(Adjustments(vignette = 1.0)))

        assertThat(rgb(out[50 * size + 50]).first).isEqualTo(200)
        assertThat(rgb(out[0]).first).isLessThan(120)
    }

    @Test
    fun `grain is deterministic and bound to the normalized canvas`() {
        val s = spec(Adjustments(grain = 1.0))
        val small = process(IntArray(72 * 72) { gray(128) }, 72, 72, s)
        val again = process(IntArray(72 * 72) { gray(128) }, 72, 72, s)
        val large = process(IntArray(720 * 720) { gray(128) }, 720, 720, s)

        assertThat(small).isEqualTo(again)
        // 720px에서는 1px이 grain 칸 하나다. 72px의 (7, 7) 칸은 720px의 (70~79, 70~79) 칸들의 샘플과 같은 위치다.
        assertThat(large.toSet().size).isGreaterThan(5)
        assertThat(ColorEffectProcessor.grainNoise(3, 5, 9)).isEqualTo(ColorEffectProcessor.grainNoise(3, 5, 9))
        assertThat(ColorEffectProcessor.grainNoise(3, 5, 9)).isNotEqualTo(ColorEffectProcessor.grainNoise(3, 5, 10))
    }

    @Test
    fun `sharpening increases edge contrast and leaves flat areas alone`() {
        val width = 40
        val pixels = IntArray(width * 10) { i -> if (i % width < width / 2) gray(80) else gray(170) }
        val out = process(pixels, width, 10, spec(Adjustments(sharpness = 1.0)))

        assertThat(rgb(out[5 * width + 2]).first).isEqualTo(80)
        assertThat(rgb(out[5 * width + width / 2 - 1]).first).isLessThan(80)
        assertThat(rgb(out[5 * width + width / 2]).first).isGreaterThan(170)
    }

    @Test
    fun `blur weights are normalized and grow with the output size`() {
        val s = spec(Adjustments(sharpness = 1.0))
        listOf(500, 4000).forEach { edge ->
            val w = s.sharpenWeights(edge)
            assertThat(w[0] + 2 * w.drop(1).sum()).isWithin(1e-5f).of(1f)
        }
        assertThat(s.sharpenWeights(4000).size).isGreaterThan(s.sharpenWeights(500).size)
    }

    @Test
    fun `alpha is preserved`() {
        val out = process(intArrayOf(0x80336699.toInt()), 1, 1, spec(Adjustments(brightness = 0.5)))

        assertThat(out[0] ushr 24).isEqualTo(0x80)
    }

    @Test
    fun `display mapping converts slider values in one place`() {
        assertThat(AdjustmentKind.EXPOSURE.fromDisplay(50f)).isEqualTo(1.0)
        assertThat(AdjustmentKind.EXPOSURE.toDisplay(-2.0)).isEqualTo(-100)
        assertThat(AdjustmentKind.SHARPNESS.fromDisplay(-30f)).isEqualTo(0.0)
        assertThat(AdjustmentKind.FADE.displayMinimum).isEqualTo(0)
        assertThat(AdjustmentKind.CONTRAST.displayMinimum).isEqualTo(-100)
    }

    @Test
    fun `every preset id is unique and resolvable`() {
        val ids = FilterCatalog.presets.map { it.id }

        assertThat(ids).containsNoDuplicates()
        ids.forEach { assertThat(FilterCatalog.find(it)).isNotNull() }
    }
}
