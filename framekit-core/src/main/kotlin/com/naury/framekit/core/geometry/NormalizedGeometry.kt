package com.naury.framekit.core.geometry

/**
 * Point in a normalized coordinate space where `0.0..1.0` spans the space's width and height.
 *
 * The space (S, G or C) is defined by the property that holds the point, never by the point itself.
 */
public data class PointN(val x: Double, val y: Double) {
    public val isFinite: Boolean get() = x.isFinite() && y.isFinite()
}

/**
 * Axis-aligned rectangle in a normalized coordinate space.
 *
 * A valid rectangle satisfies `0 <= left < right <= 1` and `0 <= top < bottom <= 1`. Construction
 * does not enforce this so that host input can be rejected by validation with a clear error.
 */
public data class RectN(val left: Double, val top: Double, val right: Double, val bottom: Double) {
    public val width: Double get() = right - left
    public val height: Double get() = bottom - top
    public val centerX: Double get() = (left + right) / 2.0
    public val centerY: Double get() = (top + bottom) / 2.0

    public val isFinite: Boolean
        get() = left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite()

    /** `true` when the rectangle is finite, non-empty and inside the unit square. */
    public val isValidUnitRect: Boolean
        get() = isFinite && left >= 0.0 && top >= 0.0 && right <= 1.0 && bottom <= 1.0 &&
            left < right && top < bottom

    public fun corners(): List<PointN> = listOf(
        PointN(left, top),
        PointN(right, top),
        PointN(right, bottom),
        PointN(left, bottom),
    )

    public companion object {
        /** The whole space. */
        public val Full: RectN = RectN(0.0, 0.0, 1.0, 1.0)

        public fun fromCenter(centerX: Double, centerY: Double, width: Double, height: Double): RectN =
            RectN(centerX - width / 2.0, centerY - height / 2.0, centerX + width / 2.0, centerY + height / 2.0)
    }
}
