package com.naury.framekit.core.geometry

/**
 * Geometric edit applied to the upright source.
 *
 * The transform order is fixed: quarter turn, then straighten, then flip, then crop. With column
 * vectors this is `M = crop × flip × straighten × quarterTurn`.
 *
 * @property quarterTurns clockwise 90° steps, `0..3`.
 * @property straightenDegrees additional clockwise rotation in degrees, `-45.0..45.0`.
 * @property flipX mirror horizontally around the center of the rotated image bounds.
 * @property flipY mirror vertically around the center of the rotated image bounds.
 * @property crop crop rectangle in the G space: normalized to the axis-aligned bounds of the
 *   rotated and flipped image. It must lie inside the image area so that no empty corner is
 *   exported.
 */
public data class GeometryEdit(
    val quarterTurns: Int = 0,
    val straightenDegrees: Double = 0.0,
    val flipX: Boolean = false,
    val flipY: Boolean = false,
    val crop: RectN = RectN.Full,
) {
    /** `true` when the edit leaves the source unchanged. */
    public val isIdentity: Boolean
        get() = quarterTurns == 0 && straightenDegrees == 0.0 && !flipX && !flipY && crop == RectN.Full

    public companion object {
        public const val MIN_STRAIGHTEN_DEGREES: Double = -45.0
        public const val MAX_STRAIGHTEN_DEGREES: Double = 45.0
    }
}
