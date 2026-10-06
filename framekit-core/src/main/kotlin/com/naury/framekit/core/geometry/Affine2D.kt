package com.naury.framekit.core.geometry

import kotlin.math.cos
import kotlin.math.sin

/**
 * 2D affine transform for column vectors.
 *
 * ```
 * | scaleX  skewX   translateX |
 * | skewY   scaleY  translateY |
 * | 0       0       1          |
 * ```
 *
 * The layout matches `android.graphics.Matrix` so adapters can copy the six values directly.
 * `a * b` applies `b` first, then `a`. All rotations are clockwise in y-down screen coordinates.
 */
public data class Affine2D(
    val scaleX: Double,
    val skewX: Double,
    val translateX: Double,
    val skewY: Double,
    val scaleY: Double,
    val translateY: Double,
) {
    public val determinant: Double get() = scaleX * scaleY - skewX * skewY

    public operator fun times(other: Affine2D): Affine2D = Affine2D(
        scaleX = scaleX * other.scaleX + skewX * other.skewY,
        skewX = scaleX * other.skewX + skewX * other.scaleY,
        translateX = scaleX * other.translateX + skewX * other.translateY + translateX,
        skewY = skewY * other.scaleX + scaleY * other.skewY,
        scaleY = skewY * other.skewX + scaleY * other.scaleY,
        translateY = skewY * other.translateX + scaleY * other.translateY + translateY,
    )

    /** Maps a point. The result uses whatever unit the transform produces. */
    public fun map(x: Double, y: Double): PointN = PointN(
        scaleX * x + skewX * y + translateX,
        skewY * x + scaleY * y + translateY,
    )

    public fun map(point: PointN): PointN = map(point.x, point.y)

    /**
     * Returns the inverse transform.
     *
     * @throws IllegalStateException when the transform is singular.
     */
    public fun inverted(): Affine2D {
        val det = determinant
        check(det != 0.0 && det.isFinite()) { "Transform is not invertible" }
        val invScaleX = scaleY / det
        val invSkewX = -skewX / det
        val invSkewY = -skewY / det
        val invScaleY = scaleX / det
        return Affine2D(
            scaleX = invScaleX,
            skewX = invSkewX,
            translateX = -(invScaleX * translateX + invSkewX * translateY),
            skewY = invSkewY,
            scaleY = invScaleY,
            translateY = -(invSkewY * translateX + invScaleY * translateY),
        )
    }

    public companion object {
        public val Identity: Affine2D = Affine2D(1.0, 0.0, 0.0, 0.0, 1.0, 0.0)

        public fun translate(dx: Double, dy: Double): Affine2D = Affine2D(1.0, 0.0, dx, 0.0, 1.0, dy)

        public fun scale(sx: Double, sy: Double = sx): Affine2D = Affine2D(sx, 0.0, 0.0, 0.0, sy, 0.0)

        /** Clockwise rotation around the origin. */
        public fun rotate(degrees: Double): Affine2D {
            val radians = Math.toRadians(degrees)
            val c = cos(radians)
            val s = sin(radians)
            return Affine2D(c, -s, 0.0, s, c, 0.0)
        }

        /** Exact clockwise rotation by `turns × 90°`, free of trigonometric rounding. */
        public fun quarterTurns(turns: Int): Affine2D = when (Math.floorMod(turns, 4)) {
            0 -> Identity
            1 -> Affine2D(0.0, -1.0, 0.0, 1.0, 0.0, 0.0)
            2 -> Affine2D(-1.0, 0.0, 0.0, 0.0, -1.0, 0.0)
            else -> Affine2D(0.0, 1.0, 0.0, -1.0, 0.0, 0.0)
        }
    }
}

/** Size with fractional components, used for intermediate geometry in pixels. */
public data class Size2D(val width: Double, val height: Double) {
    public val aspectRatio: Double get() = width / height
}
