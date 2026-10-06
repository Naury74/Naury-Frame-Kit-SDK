package com.naury.framekit.core.geometry

import com.google.common.truth.Truth.assertThat
import com.naury.framekit.core.model.PixelSize
import org.junit.Test

class GeometryOperationsTest {

    private val source = PixelSize(400, 300)
    private val probe = PointN(30.0, 20.0)

    @Test
    fun `rotate clockwise turns the visible result clockwise for every flip combination`() {
        for (flipX in listOf(false, true)) {
            for (flipY in listOf(false, true)) {
                for (turns in 0..3) {
                    val before = GeometryEdit(quarterTurns = turns, flipX = flipX, flipY = flipY, crop = RectN(0.1, 0.2, 0.8, 0.7))
                    val after = GeometryOperations.rotateClockwise(before)

                    val beforePoint = normalizedOutput(before)
                    val afterPoint = normalizedOutput(after)
                    // 출력 정규화 좌표에서 시계 방향 90°는 (x, y) → (1 - y, x).
                    assertPoint(afterPoint, 1.0 - beforePoint.y, beforePoint.x)
                }
            }
        }
    }

    @Test
    fun `four clockwise turns restore the geometry`() {
        val start = GeometryEdit(flipX = true, crop = RectN(0.1, 0.2, 0.8, 0.7))
        var geometry = start
        repeat(4) { geometry = GeometryOperations.rotateClockwise(geometry) }

        assertThat(geometry.quarterTurns).isEqualTo(start.quarterTurns)
        assertRect(geometry.crop, start.crop)
    }

    @Test
    fun `flip horizontal mirrors the visible result`() {
        val before = GeometryEdit(quarterTurns = 1, straightenDegrees = 0.0, crop = RectN(0.1, 0.2, 0.8, 0.7))
        val after = GeometryOperations.flipHorizontal(before)

        val beforePoint = normalizedOutput(before)
        val afterPoint = normalizedOutput(after)
        assertPoint(afterPoint, 1.0 - beforePoint.x, beforePoint.y)
    }

    @Test
    fun `flip vertical mirrors the visible result`() {
        val before = GeometryEdit(quarterTurns = 3, crop = RectN(0.1, 0.2, 0.8, 0.7))
        val after = GeometryOperations.flipVertical(before)

        val beforePoint = normalizedOutput(before)
        val afterPoint = normalizedOutput(after)
        assertPoint(afterPoint, beforePoint.x, 1.0 - beforePoint.y)
    }

    @Test
    fun `straighten from gesture start returns to the original crop at zero`() {
        val start = GeometryEdit(crop = RectN(0.05, 0.1, 0.9, 0.95))
        val tilted = GeometryOperations.withStraighten(source, start, 30.0)
        assertThat(GeometryFrame(source, tilted).containsRect(tilted.crop)).isTrue()

        val back = GeometryOperations.withStraighten(source, start, 0.0)

        assertRect(back.crop, start.crop)
    }

    @Test
    fun `straighten is clamped to the documented range`() {
        val geometry = GeometryOperations.withStraighten(source, GeometryEdit(), 80.0)

        assertThat(geometry.straightenDegrees).isEqualTo(45.0)
    }

    @Test
    fun `aspect selection produces the largest centered crop`() {
        val geometry = GeometryOperations.withAspect(source, GeometryEdit(), CropAspectRatio.Fixed(1, 1))

        assertRect(geometry.crop, RectN(0.125, 0.0, 0.875, 1.0))
    }

    private fun normalizedOutput(geometry: GeometryEdit): PointN {
        val frame = GeometryFrame(source, geometry)
        val output = PixelSize(1000, 1000)
        val point = frame.sourceToOutput(geometry.crop, output).map(probe)
        return PointN(point.x / output.width, point.y / output.height)
    }
}
