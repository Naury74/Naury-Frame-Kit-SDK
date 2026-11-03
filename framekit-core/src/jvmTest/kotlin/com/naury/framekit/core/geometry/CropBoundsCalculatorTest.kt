package com.naury.framekit.core.geometry

import com.google.common.truth.Truth.assertThat
import com.naury.framekit.core.model.PixelSize
import org.junit.Test

class CropBoundsCalculatorTest {

    private val source = PixelSize(4000, 3000)

    @Test
    fun `max crop without straighten is the full image for free aspect`() {
        val frame = GeometryFrame(source, GeometryEdit())

        assertRect(CropBoundsCalculator.maxCrop(frame, CropAspectRatio.Free), RectN.Full)
    }

    @Test
    fun `max crop for a fixed ratio has that pixel aspect`() {
        val frame = GeometryFrame(source, GeometryEdit())

        val square = CropBoundsCalculator.maxCrop(frame, CropAspectRatio.Fixed(1, 1))

        assertThat(frame.pixelAspect(square)).isWithin(1e-9).of(1.0)
        assertRect(square, RectN(0.125, 0.0, 0.875, 1.0))
    }

    @Test
    fun `max crop with straighten touches the image edge`() {
        listOf(-45.0, -20.0, 3.0, 17.5, 45.0).forEach { degrees ->
            val frame = GeometryFrame(source, GeometryEdit(straightenDegrees = degrees, quarterTurns = 1))
            val crop = CropBoundsCalculator.maxCrop(frame, CropAspectRatio.Fixed(16, 9))
            val larger = RectN.fromCenter(0.5, 0.5, crop.width * 1.01, crop.height * 1.01)

            assertThat(frame.containsRect(crop)).isTrue()
            assertThat(frame.containsRect(larger)).isFalse()
            assertThat(frame.pixelAspect(crop)).isWithin(1e-6).of(16.0 / 9.0)
        }
    }

    @Test
    fun `clamp shrinks an invalid crop toward the center keeping aspect`() {
        val frame = GeometryFrame(source, GeometryEdit(straightenDegrees = 15.0))
        val requested = RectN(0.0, 0.0, 0.6, 0.6)

        val clamped = CropBoundsCalculator.clampCrop(frame, requested)

        assertThat(frame.containsRect(clamped)).isTrue()
        assertThat(frame.pixelAspect(clamped)).isWithin(1e-6).of(frame.pixelAspect(requested))
    }

    @Test
    fun `clamp returns a valid crop unchanged`() {
        val frame = GeometryFrame(source, GeometryEdit())
        val crop = RectN(0.1, 0.2, 0.7, 0.9)

        assertThat(CropBoundsCalculator.clampCrop(frame, crop)).isEqualTo(crop)
    }

    @Test
    fun `minimum side is reduced for tiny sources`() {
        val frame = GeometryFrame(PixelSize(10, 40), GeometryEdit())

        assertThat(CropBoundsCalculator.minimumSide(frame)).isEqualTo(10.0)
        assertThat(CropBoundsCalculator.isValidCrop(frame, RectN.Full)).isTrue()
    }
}
