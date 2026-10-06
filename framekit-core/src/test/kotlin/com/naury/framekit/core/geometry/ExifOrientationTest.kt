package com.naury.framekit.core.geometry

import com.google.common.truth.Truth.assertThat
import com.naury.framekit.core.model.PixelSize
import org.junit.Test

/**
 * G01: each orientation stores the upright top-left corner at a different encoded corner. Mapping
 * that encoded corner must land on the upright top-left (0, 0).
 */
class ExifOrientationTest {

    private val encodedWidth = 400.0
    private val encodedHeight = 300.0

    @Test
    fun `G01 every orientation maps its labeled corner to upright top left`() {
        val uprightTopLeftInEncoded = mapOf(
            ExifOrientation.NORMAL to (0.0 to 0.0),
            ExifOrientation.FLIP_HORIZONTAL to (encodedWidth to 0.0),
            ExifOrientation.ROTATE_180 to (encodedWidth to encodedHeight),
            ExifOrientation.FLIP_VERTICAL to (0.0 to encodedHeight),
            ExifOrientation.TRANSPOSE to (0.0 to 0.0),
            ExifOrientation.ROTATE_90 to (0.0 to encodedHeight),
            ExifOrientation.TRANSVERSE to (encodedWidth to encodedHeight),
            ExifOrientation.ROTATE_270 to (encodedWidth to 0.0),
        )

        uprightTopLeftInEncoded.forEach { (orientation, corner) ->
            val matrix = orientation.encodedToUpright(encodedWidth, encodedHeight)
            assertPoint(matrix.map(corner.first, corner.second), 0.0, 0.0)
        }
    }

    @Test
    fun `G01 every orientation maps encoded pixels inside the upright bounds`() {
        ExifOrientation.entries.forEach { orientation ->
            val upright = orientation.uprightSize(PixelSize(400, 300))
            val matrix = orientation.encodedToUpright(encodedWidth, encodedHeight)
            val corners = listOf(0.0 to 0.0, encodedWidth to 0.0, encodedWidth to encodedHeight, 0.0 to encodedHeight)
                .map { matrix.map(it.first, it.second) }

            assertThat(corners.minOf { it.x }).isWithin(1e-9).of(0.0)
            assertThat(corners.maxOf { it.x }).isWithin(1e-9).of(upright.width.toDouble())
            assertThat(corners.minOf { it.y }).isWithin(1e-9).of(0.0)
            assertThat(corners.maxOf { it.y }).isWithin(1e-9).of(upright.height.toDouble())
        }
    }

    @Test
    fun `mirrored orientations flip winding while rotations keep it`() {
        ExifOrientation.entries.forEach { orientation ->
            val mirrored = orientation in setOf(
                ExifOrientation.FLIP_HORIZONTAL,
                ExifOrientation.FLIP_VERTICAL,
                ExifOrientation.TRANSPOSE,
                ExifOrientation.TRANSVERSE,
            )
            val determinant = orientation.encodedToUpright(encodedWidth, encodedHeight).determinant
            assertThat(determinant < 0).isEqualTo(mirrored)
        }
    }

    @Test
    fun `unknown tag values are treated as normal`() {
        assertThat(ExifOrientation.fromExifValue(null)).isEqualTo(ExifOrientation.NORMAL)
        assertThat(ExifOrientation.fromExifValue(0)).isEqualTo(ExifOrientation.NORMAL)
        assertThat(ExifOrientation.fromExifValue(9)).isEqualTo(ExifOrientation.NORMAL)
        assertThat(ExifOrientation.fromExifValue(6)).isEqualTo(ExifOrientation.ROTATE_90)
    }
}
