package com.naury.framekit.core.geometry

import com.naury.framekit.core.model.PixelSize
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * [GeometryEdit]가 적용된 바로 세운 원본 하나의 기하. 원본 픽셀 단위다.
 *
 * G 공간은 90° 회전, 수평 보정, 뒤집기를 거친 이미지의 축 정렬 경계 상자다.
 * 그 크기가 [bounds]이며, 정규화된 G 좌표는 이 크기로 나눈 값이다.
 *
 * @throws IllegalArgumentException [sourceSize]가 양수가 아닐 때.
 */
public class GeometryFrame(
    public val sourceSize: PixelSize,
    public val geometry: GeometryEdit,
) {
    init {
        require(sourceSize.isValid) { "Source size must be positive: $sourceSize" }
    }

    /** 90° 회전 이후, 수평 보정 이전의 이미지 크기. */
    public val rotatedSize: Size2D = if (Math.floorMod(geometry.quarterTurns, 2) == 1) {
        Size2D(sourceSize.height.toDouble(), sourceSize.width.toDouble())
    } else {
        Size2D(sourceSize.width.toDouble(), sourceSize.height.toDouble())
    }

    /** 원본 픽셀 기준 G 공간의 크기. */
    public val bounds: Size2D

    /** 바로 세운 원본 픽셀(S)에서 G 픽셀로의 변환. */
    public val uprightToBounds: Affine2D

    init {
        val radians = Math.toRadians(geometry.straightenDegrees)
        val c = abs(cos(radians))
        val s = abs(sin(radians))
        bounds = Size2D(
            width = rotatedSize.width * c + rotatedSize.height * s,
            height = rotatedSize.width * s + rotatedSize.height * c,
        )
        uprightToBounds = Affine2D.translate(bounds.width / 2.0, bounds.height / 2.0) *
            Affine2D.scale(if (geometry.flipX) -1.0 else 1.0, if (geometry.flipY) -1.0 else 1.0) *
            Affine2D.rotate(geometry.straightenDegrees) *
            Affine2D.quarterTurns(geometry.quarterTurns) *
            Affine2D.translate(-sourceSize.width / 2.0, -sourceSize.height / 2.0)
    }

    /** 정규화된 G 좌표에서 이미지 영역의 모서리들. 일관된 감김 순서를 따른다. */
    public val imageQuad: List<PointN> = listOf(
        uprightToBounds.map(0.0, 0.0),
        uprightToBounds.map(sourceSize.width.toDouble(), 0.0),
        uprightToBounds.map(sourceSize.width.toDouble(), sourceSize.height.toDouble()),
        uprightToBounds.map(0.0, sourceSize.height.toDouble()),
    ).map { PointN(it.x / bounds.width, it.y / bounds.height) }

    /** 원본 픽셀 기준 자르기 크기. */
    public fun cropPixelSize(crop: RectN): Size2D = Size2D(crop.width * bounds.width, crop.height * bounds.height)

    /** 픽셀 기준 [crop]의 가로:세로 비율. */
    public fun pixelAspect(crop: RectN): Double = cropPixelSize(crop).aspectRatio

    /** 정규화된 G 점이 이미지 영역 안에 있으면 `true`. 경계 위도 안으로 친다. */
    public fun containsPoint(point: PointN): Boolean {
        var sign = 0
        for (index in imageQuad.indices) {
            val a = imageQuad[index]
            val b = imageQuad[(index + 1) % imageQuad.size]
            // 정규화 좌표의 가로세로 비율이 달라도 같은 판정이 되도록 픽셀 단위로 외적을 계산한다.
            val cross = (b.x - a.x) * bounds.width * (point.y - a.y) * bounds.height -
                (b.y - a.y) * bounds.height * (point.x - a.x) * bounds.width
            if (abs(cross) <= EDGE_EPSILON_PX2) continue
            val current = if (cross > 0) 1 else -1
            if (sign == 0) sign = current else if (sign != current) return false
        }
        return true
    }

    /** [rect]의 모든 모서리가 이미지 영역 안에 있으면 `true`. */
    public fun containsRect(rect: RectN): Boolean = rect.corners().all(::containsPoint)

    /**
     * [crop]을 [outputSize]로 렌더링할 때 바로 세운 원본 픽셀에서 출력 픽셀로의 변환.
     *
     * `M_scaleOutput × M_cropTranslate × M_flip × M_straighten × M_quarterTurn`이다. 디코더가 이미
     * 바로 세운 픽셀을 넘겨주므로 EXIF 방향은 포함하지 않는다.
     */
    public fun sourceToOutput(crop: RectN, outputSize: PixelSize): Affine2D {
        val cropSize = cropPixelSize(crop)
        return Affine2D.scale(outputSize.width / cropSize.width, outputSize.height / cropSize.height) *
            Affine2D.translate(-crop.left * bounds.width, -crop.top * bounds.height) *
            uprightToBounds
    }

    private companion object {
        // 경계 위의 점이 부동소수 오차로 바깥 판정되지 않도록 둔 허용 범위(px²).
        const val EDGE_EPSILON_PX2 = 1e-6
    }
}
