package com.naury.framekit.core.document

import com.google.common.truth.Truth.assertThat
import com.naury.framekit.core.geometry.PointN
import org.junit.Test
import kotlin.math.abs

class DocumentDetectorTest {

    // 어두운 책상(40) 위에 밝은 종이(220)를 사각형 [quad]로 그린 이미지.
    private fun scene(width: Int, height: Int, quad: List<Pair<Double, Double>>, paper: Int = 220, desk: Int = 40): IntArray {
        val pts = quad.map { (x, y) -> x * width to y * height }
        return IntArray(width * height) { i ->
            val x = i % width + 0.5
            val y = i / width + 0.5
            if (inside(pts, x, y)) paper else desk
        }
    }

    private fun inside(poly: List<Pair<Double, Double>>, x: Double, y: Double): Boolean {
        var result = false
        var j = poly.size - 1
        for (i in poly.indices) {
            val (xi, yi) = poly[i]
            val (xj, yj) = poly[j]
            if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) result = !result
            j = i
        }
        return result
    }

    private fun assertNear(actual: PointN, x: Double, y: Double) {
        assertThat(abs(actual.x - x)).isLessThan(0.03)
        assertThat(abs(actual.y - y)).isLessThan(0.03)
    }

    @Test
    fun `tilted paper on a dark desk gives its four corners`() {
        val quad = listOf(0.22 to 0.12, 0.80 to 0.18, 0.86 to 0.88, 0.14 to 0.82)
        val found = DocumentDetector.detect(scene(200, 260, quad), 200, 260)!!
        assertNear(found.topLeft, 0.22, 0.12)
        assertNear(found.topRight, 0.80, 0.18)
        assertNear(found.bottomRight, 0.86, 0.88)
        assertNear(found.bottomLeft, 0.14, 0.82)
    }

    @Test
    fun `dark document on a light background is found too`() {
        val quad = listOf(0.3 to 0.25, 0.7 to 0.25, 0.7 to 0.75, 0.3 to 0.75)
        val found = DocumentDetector.detect(scene(160, 160, quad, paper = 30, desk = 230), 160, 160)!!
        assertNear(found.topLeft, 0.3, 0.25)
        assertNear(found.bottomRight, 0.7, 0.75)
    }

    @Test
    fun `flat images or full frame documents return null`() {
        assertThat(DocumentDetector.detect(IntArray(100 * 100) { 128 }, 100, 100)).isNull()
        val full = listOf(0.0 to 0.0, 1.0 to 0.0, 1.0 to 1.0, 0.0 to 1.0)
        assertThat(DocumentDetector.detect(scene(100, 100, full), 100, 100)).isNull()
    }

    @Test
    fun `quad measures area convexity and rectified size`() {
        val quad = DocumentQuad(PointN(0.1, 0.1), PointN(0.9, 0.1), PointN(0.9, 0.6), PointN(0.1, 0.6))
        assertThat(quad.area).isWithin(1e-9).of(0.4)
        assertThat(quad.isUsable).isTrue()
        assertThat(quad.rectifiedSize(1000, 2000)).isEqualTo(800 to 1000)
        val twisted = DocumentQuad(PointN(0.1, 0.1), PointN(0.9, 0.6), PointN(0.9, 0.1), PointN(0.1, 0.6))
        assertThat(twisted.isUsable).isFalse()
    }
}
