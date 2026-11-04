package com.naury.framekit.core.geometry

import com.google.common.truth.Truth.assertWithMessage
import com.naury.framekit.core.model.PixelSize
import org.junit.Test
import kotlin.random.Random

/** 손잡이를 가장자리까지 끌어도 저장 검사를 통과하는 자르기가 나오는지 무작위로 확인한다. */
class CropHandleDragRandomTest {

    @Test
    fun `dragged crop stays strictly inside the unit square and passes save validation`() {
        val random = Random(7)
        repeat(3000) {
            val frame = GeometryFrame(
                PixelSize(random.nextInt(17, 6000), random.nextInt(17, 6000)),
                GeometryEdit(
                    quarterTurns = random.nextInt(4),
                    straightenDegrees = if (random.nextBoolean()) 0.0 else random.nextDouble(-45.0, 45.0),
                ),
            )
            val aspect = listOf(null, 1.0, 4.0 / 3.0, 9.0 / 16.0, frame.rotatedSize.aspectRatio)[random.nextInt(5)]
            var crop = CropBoundsCalculator.maxCropForPixelAspect(frame, aspect ?: frame.rotatedSize.aspectRatio)
            repeat(6) {
                val handle = CropHandle.entries[random.nextInt(CropHandle.entries.size)]
                val reach = if (random.nextBoolean()) 2.0 else 0.2
                crop = CropHandleDrag.drag(frame, crop, handle, random.nextDouble(-reach, reach), random.nextDouble(-reach, reach), aspect)
                val strict = crop.left >= 0.0 && crop.top >= 0.0 && crop.right <= 1.0 && crop.bottom <= 1.0
                assertWithMessage("${frame.sourceSize} ${frame.geometry.quarterTurns} ${frame.geometry.straightenDegrees} a=$aspect $handle $crop strict=$strict contains=${frame.containsRect(crop)} min=${CropBoundsCalculator.meetsMinimumSize(frame, crop)} px=${frame.cropPixelSize(crop)}").that(strict && CropBoundsCalculator.isValidCrop(frame, crop)).isTrue()
            }
        }
    }

    @Test
    fun `tiny float overshoot from older edits still counts as a unit rect`() {
        assertWithMessage("overshoot").that(RectN(-2.5e-13, 0.0, 1.0000000000002502, 0.85).isValidUnitRect).isTrue()
        assertWithMessage("real overflow").that(RectN(0.0, 0.0, 1.001, 1.0).isValidUnitRect).isFalse()
    }
}
