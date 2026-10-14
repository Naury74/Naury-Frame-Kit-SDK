package com.naury.framekit.ui.image.editor

import com.naury.framekit.core.geometry.PointN
import com.naury.framekit.core.overlay.ImageOverlay
import com.naury.framekit.core.overlay.ShapeStyle
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.round
import kotlin.math.sin

/** 끌어서 도형을 그릴 때의 계산. 직접 테스트할 수 있도록 ViewModel 밖에 둔다. */
internal object ShapeDrawing {

    /**
     * [anchor]에서 [end]까지 끈 도형. 좌표는 정규화 캔버스 값이다.
     *
     * 상자 도형은 시작 모서리를 고정하고 가로·세로 1:1(정사각형·정원)로 그린다. 비율은 그린 뒤 크기 손잡이로 바꾼다.
     * 선·화살표는 시작점에서 끝점까지 긋고, 각도가 45도 배수에 가까우면 그 각도로 맞춘다.
     *
     * @param aspect 출력 캔버스의 너비 ÷ 높이.
     */
    fun span(shape: ImageOverlay.Shape, anchor: PointN, end: PointN, aspect: Double): ImageOverlay.Shape {
        val ratio = validAspect(aspect)
        // 계산은 캔버스 높이를 1로 둔 단위에서 한다(가로 1은 aspect).
        val dx = (end.x - anchor.x) * ratio
        val dy = end.y - anchor.y
        return if (shape.kind.isLine) {
            line(shape, PointN(anchor.x * ratio, anchor.y), dx, dy, ratio)
        } else {
            val side = max(abs(dx), abs(dy))
            // 한 축으로만 끌어도 방향이 정해지도록, 움직이지 않은 축은 양의 방향으로 둔다.
            val sx = if (dx < 0) -1.0 else 1.0
            val sy = if (dy < 0) -1.0 else 1.0
            box(shape, PointN(anchor.x * ratio, anchor.y), 0.0, side, side, sx, sy, ratio)
        }
    }

    /**
     * 크기 손잡이(오른쪽 아래 모서리, 선은 끝점)를 [dx], [dy](정규화 캔버스 값)만큼 옮긴 도형. 반대쪽 모서리(선은 시작점)는 그대로다.
     *
     * 상자는 회전한 방향을 기준으로 가로·세로를 따로 바꾸고, 거의 정사각형이면 정사각형으로 맞춘다. 반대쪽으로 넘겨 뒤집지는 않는다.
     * 결과의 배율은 1이다(지금 배율을 크기에 반영한다).
     */
    fun resize(start: ImageOverlay.Shape, dx: Double, dy: Double, aspect: Double, strokeRatio: Double): ImageOverlay.Shape {
        val ratio = validAspect(aspect)
        val t = start.transform
        val radians = Math.toRadians(t.rotationDegrees)
        val c = cos(radians)
        val s = sin(radians)
        val center = PointN(t.center.x * ratio, t.center.y)
        val moveX = dx * ratio
        if (start.kind.isLine) {
            val length = start.widthRatio * t.scale * ratio
            val begin = PointN(center.x - c * length / 2, center.y - s * length / 2)
            val endX = center.x + c * length / 2 + moveX - begin.x
            val endY = center.y + s * length / 2 + dy - begin.y
            return line(start, begin, endX, endY, ratio)
        }
        // 선 굵기만큼 틀이 상자보다 크므로, 손잡이가 있는 틀 모서리 기준으로 계산하고 굵기를 뺀다.
        val margin = if (start.style.filled) 0.0 else strokeRatio
        val boxWidth = start.widthRatio * t.scale * ratio + margin
        val boxHeight = start.heightRatio * t.scale + margin
        val anchor = PointN(center.x - (c * boxWidth - s * boxHeight) / 2, center.y - (s * boxWidth + c * boxHeight) / 2)
        val cornerX = center.x + (c * boxWidth - s * boxHeight) / 2 + moveX - anchor.x
        val cornerY = center.y + (s * boxWidth + c * boxHeight) / 2 + dy - anchor.y
        // 회전을 되돌린 방향에서의 새 틀 크기.
        var width = (c * cornerX + s * cornerY - margin).coerceAtLeast(MIN_BOX)
        var height = (-s * cornerX + c * cornerY - margin).coerceAtLeast(MIN_BOX)
        if (abs(width - height) < SQUARE_SNAP * max(width, height)) {
            width = max(width, height)
            height = width
        }
        val corner = PointN(anchor.x + margin / 2 * (c - s), anchor.y + margin / 2 * (s + c))
        return box(start, corner, t.rotationDegrees, width, height, 1.0, 1.0, ratio)
    }

    // [corner]에서 회전한 축 방향([sx], [sy])으로 [width]×[height](높이 단위)만큼 펼친 상자.
    private fun box(
        shape: ImageOverlay.Shape,
        corner: PointN,
        degrees: Double,
        width: Double,
        height: Double,
        sx: Double,
        sy: Double,
        ratio: Double,
    ): ImageOverlay.Shape {
        val radians = Math.toRadians(degrees)
        val c = cos(radians)
        val s = sin(radians)
        val hx = sx * width / 2
        val hy = sy * height / 2
        val center = PointN((corner.x + c * hx - s * hy) / ratio, corner.y + s * hx + c * hy)
        val limit = ImageOverlay.Shape.MAX_SIZE_RATIO
        return shape.copy(
            widthRatio = (width / ratio).coerceIn(MIN_RATIO, limit),
            heightRatio = height.coerceIn(MIN_RATIO, limit),
            transform = shape.transform.copy(center = center, rotationDegrees = degrees, scale = 1.0),
        )
    }

    // [begin](높이 단위)에서 ([dx], [dy])만큼 그은 선.
    private fun line(shape: ImageOverlay.Shape, begin: PointN, dx: Double, dy: Double, ratio: Double): ImageOverlay.Shape {
        val length = hypot(dx, dy)
        var degrees = Math.toDegrees(atan2(dy, dx))
        val snapped = round(degrees / ANGLE_STEP) * ANGLE_STEP
        if (abs(degrees - snapped) < ANGLE_SNAP) degrees = snapped
        val radians = Math.toRadians(degrees)
        val endX = cos(radians) * length
        val endY = sin(radians) * length
        return shape.copy(
            widthRatio = (length / ratio).coerceIn(MIN_RATIO, ImageOverlay.Shape.MAX_SIZE_RATIO),
            heightRatio = 0.0,
            transform = shape.transform.copy(
                center = PointN((begin.x + endX / 2) / ratio, begin.y + endY / 2),
                rotationDegrees = degrees,
                scale = 1.0,
            ),
        )
    }

    private fun validAspect(aspect: Double) = aspect.takeIf { it.isFinite() && it > 0 } ?: 1.0

    /** 실수로 살짝 끈 것처럼 너무 작아 남기지 않을 도형이면 `true`. */
    fun isTooSmall(shape: ImageOverlay.Shape): Boolean = if (shape.kind.isLine) {
        shape.widthRatio < MIN_LINE
    } else {
        shape.widthRatio < MIN_BOX || shape.heightRatio < MIN_BOX
    }

    private const val MIN_RATIO = 0.001
    private const val MIN_BOX = 0.01
    private const val MIN_LINE = 0.02
    private const val SQUARE_SNAP = 0.06
    private const val ANGLE_STEP = 45.0
    private const val ANGLE_SNAP = 4.0
}

/** 지금 설정으로 그릴 도형 모양. */
internal fun BrushSettings.shapeStyle(): ShapeStyle = ShapeStyle(
    colorArgb = colorArgb,
    strokeWidthShortEdgeRatio = widthShortEdgeRatio.coerceIn(0.0005, ShapeStyle.MAX_STROKE_RATIO),
    filled = shapeFill == ShapeFill.FILLED && !shape.isLine,
    dashed = shapeFill == ShapeFill.DASHED,
)

/** 고른 도형의 모양을 그대로 보여 주는 설정. */
internal fun BrushSettings.matching(shape: ImageOverlay.Shape): BrushSettings = copy(
    shapes = true,
    shape = shape.kind,
    colorArgb = shape.style.colorArgb,
    widthShortEdgeRatio = shape.style.strokeWidthShortEdgeRatio,
    opacity = shape.transform.opacity,
    shapeFill = when {
        shape.style.filled -> ShapeFill.FILLED
        shape.style.dashed -> ShapeFill.DASHED
        else -> ShapeFill.OUTLINE
    },
)

/** [settings]의 종류·색·굵기·채우기·불투명도를 적용한 도형. */
internal fun ImageOverlay.Shape.restyled(settings: BrushSettings): ImageOverlay.Shape = copy(
    kind = settings.shape,
    style = settings.shapeStyle(),
    transform = transform.copy(opacity = settings.opacity),
)
