package com.naury.framekit.core.geometry

import com.google.common.truth.Truth.assertThat
import com.naury.framekit.core.model.PixelSize
import org.junit.Test

class CropHandleDragTest {

    private val frame = GeometryFrame(PixelSize(4000, 3000), GeometryEdit())
    private val start = RectN(0.2, 0.2, 0.8, 0.8)

    @Test
    fun `free corner drag moves only that corner`() {
        val result = CropHandleDrag.drag(frame, start, CropHandle.BOTTOM_RIGHT, 0.1, -0.1, pixelAspect = null)

        assertRect(result, RectN(0.2, 0.2, 0.9, 0.7))
    }

    @Test
    fun `locked corner drag keeps the pixel aspect`() {
        // start는 4000x3000에서 정규화 0.6x0.6이므로 픽셀 비율 4:3이다.
        val result = CropHandleDrag.drag(frame, start, CropHandle.TOP_LEFT, -0.1, 0.05, pixelAspect = 4.0 / 3.0)

        assertThat(frame.pixelAspect(result)).isWithin(1e-6).of(4.0 / 3.0)
        assertThat(result.width).isWithin(1e-9).of(0.7)
        assertThat(result.right).isWithin(1e-9).of(start.right)
        assertThat(result.bottom).isWithin(1e-9).of(start.bottom)
    }

    @Test
    fun `locked edge drag resizes around the center`() {
        val result = CropHandleDrag.drag(frame, start, CropHandle.RIGHT, 0.1, 0.0, pixelAspect = 4.0 / 3.0)

        assertThat(frame.pixelAspect(result)).isWithin(1e-6).of(4.0 / 3.0)
        assertThat(result.centerY).isWithin(1e-9).of(start.centerY)
    }

    @Test
    fun `drag beyond the image stays inside`() {
        val tilted = GeometryFrame(PixelSize(4000, 3000), GeometryEdit(straightenDegrees = 20.0))
        val crop = CropBoundsCalculator.maxCrop(tilted, CropAspectRatio.Fixed(1, 1))
        val shrunk = RectN.fromCenter(0.5, 0.5, crop.width / 2, crop.height / 2)

        val result = CropHandleDrag.drag(tilted, shrunk, CropHandle.TOP_LEFT, -1.0, -1.0, pixelAspect = 1.0)

        assertThat(tilted.containsRect(result)).isTrue()
        assertThat(result.width).isGreaterThan(shrunk.width)
    }

    @Test
    fun `crossing the opposite edge stops at the minimum size`() {
        val result = CropHandleDrag.drag(frame, start, CropHandle.LEFT, 2.0, 0.0, pixelAspect = null)

        assertThat(frame.cropPixelSize(result).width).isWithin(1e-6).of(CropBoundsCalculator.MIN_CROP_SIDE_PX.toDouble())
    }

    @Test
    fun `move slides along a blocked edge`() {
        val result = CropHandleDrag.drag(frame, start, CropHandle.MOVE, 0.5, 0.1, pixelAspect = null)

        assertRect(result, RectN(0.4, 0.3, 1.0, 0.9))
    }
}
