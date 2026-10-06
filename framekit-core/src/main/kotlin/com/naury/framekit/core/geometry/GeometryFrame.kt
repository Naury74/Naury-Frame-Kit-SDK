package com.naury.framekit.core.geometry

import com.naury.framekit.core.model.PixelSize
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Geometry of one upright source under a [GeometryEdit], in source pixel units.
 *
 * The G space is the axis-aligned bounding box of the image after quarter turn, straighten and flip.
 * Its size is [bounds]; normalized G coordinates divide by it.
 *
 * @throws IllegalArgumentException when [sourceSize] is not positive.
 */
public class GeometryFrame(
    public val sourceSize: PixelSize,
    public val geometry: GeometryEdit,
) {
    init {
        require(sourceSize.isValid) { "Source size must be positive: $sourceSize" }
    }

    /** Image size after quarter turns, before straighten. */
    public val rotatedSize: Size2D = if (Math.floorMod(geometry.quarterTurns, 2) == 1) {
        Size2D(sourceSize.height.toDouble(), sourceSize.width.toDouble())
    } else {
        Size2D(sourceSize.width.toDouble(), sourceSize.height.toDouble())
    }

    /** Size of the G space in source pixels. */
    public val bounds: Size2D

    /** Transform from upright source pixels (S) to G pixels. */
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

    /** Corners of the image area in normalized G coordinates, in a consistent winding order. */
    public val imageQuad: List<PointN> = listOf(
        uprightToBounds.map(0.0, 0.0),
        uprightToBounds.map(sourceSize.width.toDouble(), 0.0),
        uprightToBounds.map(sourceSize.width.toDouble(), sourceSize.height.toDouble()),
        uprightToBounds.map(0.0, sourceSize.height.toDouble()),
    ).map { PointN(it.x / bounds.width, it.y / bounds.height) }

    /** Crop size in source pixels. */
    public fun cropPixelSize(crop: RectN): Size2D = Size2D(crop.width * bounds.width, crop.height * bounds.height)

    /** Width-to-height ratio of [crop] in pixels. */
    public fun pixelAspect(crop: RectN): Double = cropPixelSize(crop).aspectRatio

    /** `true` when the normalized G point lies inside the image area. Edges count as inside. */
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

    /** `true` when every corner of [rect] lies inside the image area. */
    public fun containsRect(rect: RectN): Boolean = rect.corners().all(::containsPoint)

    /**
     * Transform from upright source pixels to output pixels for [crop] rendered at [outputSize].
     *
     * This is `M_scaleOutput × M_cropTranslate × M_flip × M_straighten × M_quarterTurn`. The EXIF
     * orientation is not part of it because decoders hand over upright pixels.
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
