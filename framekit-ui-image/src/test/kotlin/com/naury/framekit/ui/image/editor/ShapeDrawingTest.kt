package com.naury.framekit.ui.image.editor

import com.google.common.truth.Truth.assertThat
import com.naury.framekit.core.geometry.PointN
import com.naury.framekit.core.overlay.ImageOverlay
import com.naury.framekit.core.overlay.OverlayTransform
import com.naury.framekit.core.overlay.ShapeKind
import com.naury.framekit.core.overlay.ShapeStyle
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class ShapeDrawingTest {

    // 캔버스 높이를 1로 둔 단위에서 상자 틀의 왼쪽 위 모서리(회전 반영).
    private fun topLeft(shape: ImageOverlay.Shape, aspect: Double, margin: Double): PointN {
        val r = Math.toRadians(shape.transform.rotationDegrees)
        val w = shape.widthRatio * shape.transform.scale * aspect + margin
        val h = shape.heightRatio * shape.transform.scale + margin
        val cx = shape.transform.center.x * aspect
        val cy = shape.transform.center.y
        return PointN(cx - (cos(r) * w - sin(r) * h) / 2, cy - (sin(r) * w + cos(r) * h) / 2)
    }

    @Test
    fun `rotated outlined box grows along its own axes and keeps the opposite corner`() {
        val aspect = 1.5
        val stroke = 0.01
        val start = ImageOverlay.Shape(
            "r", ShapeKind.RECTANGLE, 0.2, 0.3, ShapeStyle(strokeWidthShortEdgeRatio = stroke),
            OverlayTransform(PointN(0.5, 0.5), scale = 1.5, rotationDegrees = 90.0),
        )
        // 90도 회전이라 상자의 가로 방향은 화면 아래쪽이다. 손잡이를 아래로 0.1 옮기면 가로만 0.1만큼 길어진다.
        val resized = ShapeDrawing.resize(start, 0.0, 0.1, aspect, stroke)

        assertThat(resized.transform.scale).isEqualTo(1.0)
        assertThat(resized.transform.rotationDegrees).isEqualTo(90.0)
        assertThat(resized.widthRatio * aspect).isWithin(1e-9).of(start.widthRatio * 1.5 * aspect + 0.1)
        assertThat(resized.heightRatio).isWithin(1e-9).of(start.heightRatio * 1.5)
        val before = topLeft(start, aspect, stroke)
        val after = topLeft(resized, aspect, stroke)
        assertThat(after.x).isWithin(1e-9).of(before.x)
        assertThat(after.y).isWithin(1e-9).of(before.y)
    }

    @Test
    fun `box never flips past the opposite corner`() {
        val start = ImageOverlay.Shape("r", ShapeKind.ELLIPSE, 0.2, 0.2, ShapeStyle(filled = true))
        val resized = ShapeDrawing.resize(start, -1.0, -1.0, 1.0, 0.01)
        assertThat(resized.widthRatio).isGreaterThan(0.0)
        assertThat(resized.heightRatio).isGreaterThan(0.0)
        assertThat(resized.widthRatio).isLessThan(0.05)
    }
}
