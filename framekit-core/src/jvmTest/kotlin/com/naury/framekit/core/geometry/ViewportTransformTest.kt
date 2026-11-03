package com.naury.framekit.core.geometry

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ViewportTransformTest {

    @Test
    fun `G03 canvas to viewport to canvas round trips`() {
        val transform = ViewportTransform(Size2D(4000.0, 3000.0), 1080.0, 1920.0, padding = 24.0, zoom = 1.7, panX = -35.0, panY = 80.0)

        listOf(PointN(0.0, 0.0), PointN(0.25, 0.75), PointN(1.0, 1.0), PointN(0.123456, 0.987654)).forEach { point ->
            val viewport = transform.toViewport(point)
            val back = transform.toContentUnbounded(viewport.x, viewport.y)
            assertPoint(back, point.x, point.y, tolerance = 1e-6)
        }
    }

    @Test
    fun `G04 pointer in the letterbox maps to no canvas point`() {
        // 가로로 긴 사진을 세로 화면에 맞추면 위·아래에 여백이 생긴다.
        val transform = ViewportTransform(Size2D(4000.0, 2000.0), 1000.0, 1000.0)

        assertThat(transform.toContent(500.0, 100.0)).isNull()
        assertThat(transform.toContent(500.0, 500.0)).isNotNull()
    }

    @Test
    fun `content is centered and fitted`() {
        val transform = ViewportTransform(Size2D(4000.0, 2000.0), 1000.0, 1000.0)

        assertThat(transform.fittedSize).isEqualTo(Size2D(1000.0, 500.0))
        assertPoint(transform.toViewport(PointN(0.0, 0.0)), 0.0, 250.0)
        assertPoint(transform.toViewport(PointN(1.0, 1.0)), 1000.0, 750.0)
    }
}
