package com.naury.framekit.core.geometry

import com.google.common.truth.Truth.assertThat
import com.naury.framekit.core.model.PixelSize
import org.junit.Test

class GeometryFrameTest {

    private val source = PixelSize(4000, 3000)

    @Test
    fun `identity maps source corners to output corners`() {
        val frame = GeometryFrame(source, GeometryEdit())
        val matrix = frame.sourceToOutput(RectN.Full, PixelSize(400, 300))

        assertPoint(matrix.map(0.0, 0.0), 0.0, 0.0)
        assertPoint(matrix.map(4000.0, 3000.0), 400.0, 300.0)
    }

    @Test
    fun `G02 crop after a quarter turn keeps output aspect and content`() {
        val geometry = GeometryEdit(quarterTurns = 1, crop = RectN(0.0, 0.0, 1.0, 0.5))
        val frame = GeometryFrame(source, geometry)
        val cropSize = frame.cropPixelSize(geometry.crop)

        assertThat(frame.bounds.width).isWithin(1e-9).of(3000.0)
        assertThat(frame.bounds.height).isWithin(1e-9).of(4000.0)
        assertThat(cropSize.aspectRatio).isWithin(1e-9).of(3000.0 / 2000.0)
        assertThat(CropBoundsCalculator.isValidCrop(frame, geometry.crop)).isTrue()

        // 시계 방향 90°: 원본 왼쪽 아래 모서리가 출력 왼쪽 위로 온다.
        val output = PixelSize(300, 200)
        val matrix = frame.sourceToOutput(geometry.crop, output)
        assertPoint(matrix.map(0.0, 3000.0), 0.0, 0.0)
        assertPoint(matrix.map(0.0, 1000.0), 200.0, 0.0, tolerance = 1e-6)
    }

    @Test
    fun `straighten enlarges bounds and leaves corners outside the image`() {
        val frame = GeometryFrame(source, GeometryEdit(straightenDegrees = 10.0))

        assertThat(frame.bounds.width).isGreaterThan(4000.0)
        assertThat(frame.containsRect(RectN.Full)).isFalse()
        assertThat(frame.containsPoint(PointN(0.5, 0.5))).isTrue()
    }

    @Test
    fun `flip keeps containment checks consistent`() {
        val frame = GeometryFrame(source, GeometryEdit(straightenDegrees = -12.0, flipX = true))
        val crop = CropBoundsCalculator.maxCrop(frame, CropAspectRatio.Free)

        assertThat(frame.containsRect(crop)).isTrue()
    }
}
