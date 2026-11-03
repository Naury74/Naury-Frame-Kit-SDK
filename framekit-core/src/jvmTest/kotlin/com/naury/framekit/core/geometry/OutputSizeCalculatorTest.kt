package com.naury.framekit.core.geometry

import com.google.common.truth.Truth.assertThat
import com.naury.framekit.core.model.PixelSize
import org.junit.Test

class OutputSizeCalculatorTest {

    @Test
    fun `small crops are not upscaled`() {
        assertThat(OutputSizeCalculator.compute(Size2D(800.0, 600.0), 16_000_000L)).isEqualTo(PixelSize(800, 600))
    }

    @Test
    fun `pixel budget keeps aspect and stays under the limit`() {
        val size = OutputSizeCalculator.compute(Size2D(8000.0, 6000.0), 16_000_000L)

        assertThat(size.pixelCount).isAtMost(16_000_000L)
        assertThat(size.aspectRatio).isWithin(0.001).of(4.0 / 3.0)
    }

    @Test
    fun `width and height limits are applied`() {
        assertThat(OutputSizeCalculator.compute(Size2D(4000.0, 3000.0), 16_000_000L, maxWidth = 1000)).isEqualTo(PixelSize(1000, 750))
        assertThat(OutputSizeCalculator.compute(Size2D(4000.0, 3000.0), 16_000_000L, maxHeight = 300)).isEqualTo(PixelSize(400, 300))
    }
}
