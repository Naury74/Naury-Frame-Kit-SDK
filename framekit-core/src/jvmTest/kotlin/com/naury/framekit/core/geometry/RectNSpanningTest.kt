package com.naury.framekit.core.geometry

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RectNSpanningTest {

    @Test
    fun `same point or one-axis drag still gives a non-empty rect inside the unit square`() {
        val point = RectN.spanning(PointN(0.5, 0.5), PointN(0.5, 0.5))
        assertThat(point.width).isGreaterThan(0.0)
        assertThat(point.height).isGreaterThan(0.0)

        val vertical = RectN.spanning(PointN(0.3, 0.2), PointN(0.3, 0.8))
        assertThat(vertical.width).isWithin(1e-12).of(RectN.MIN_SPAN)
        assertThat(vertical.height).isWithin(1e-12).of(0.6)

        val corner = RectN.spanning(PointN(1.0, 1.0), PointN(1.0, 1.0))
        assertThat(corner.isValidUnitRect).isTrue()

        val reversed = RectN.spanning(PointN(0.9, 0.7), PointN(-0.2, 0.1))
        assertThat(reversed).isEqualTo(RectN(0.0, 0.1, 0.9, 0.7))
    }
}
