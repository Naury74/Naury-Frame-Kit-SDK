package com.naury.framekit.core.geometry

import com.naury.framekit.core.model.PixelSize

/**
 * EXIF `Orientation` tag values 1 to 8.
 *
 * Mirrored orientations are not equivalent to a rotation and must not be approximated by one.
 */
public enum class ExifOrientation(public val exifValue: Int) {
    NORMAL(1),
    FLIP_HORIZONTAL(2),
    ROTATE_180(3),
    FLIP_VERTICAL(4),
    TRANSPOSE(5),
    ROTATE_90(6),
    TRANSVERSE(7),
    ROTATE_270(8),
    ;

    /** `true` when the upright image has width and height exchanged relative to the encoded pixels. */
    public val swapsDimensions: Boolean get() = exifValue >= 5

    public fun uprightSize(encoded: PixelSize): PixelSize = if (swapsDimensions) encoded.transposed() else encoded

    /**
     * Transform from encoded pixel coordinates to upright pixel coordinates.
     *
     * @param encodedWidth width of the stored pixels before orientation is applied.
     * @param encodedHeight height of the stored pixels before orientation is applied.
     */
    public fun encodedToUpright(encodedWidth: Double, encodedHeight: Double): Affine2D {
        val w = encodedWidth
        val h = encodedHeight
        return when (this) {
            NORMAL -> Affine2D.Identity
            FLIP_HORIZONTAL -> Affine2D(-1.0, 0.0, w, 0.0, 1.0, 0.0)
            ROTATE_180 -> Affine2D(-1.0, 0.0, w, 0.0, -1.0, h)
            FLIP_VERTICAL -> Affine2D(1.0, 0.0, 0.0, 0.0, -1.0, h)
            TRANSPOSE -> Affine2D(0.0, 1.0, 0.0, 1.0, 0.0, 0.0)
            ROTATE_90 -> Affine2D(0.0, -1.0, h, 1.0, 0.0, 0.0)
            TRANSVERSE -> Affine2D(0.0, -1.0, h, -1.0, 0.0, w)
            ROTATE_270 -> Affine2D(0.0, 1.0, 0.0, -1.0, 0.0, w)
        }
    }

    public companion object {
        /** Maps a raw tag value. Missing, zero or unknown values are treated as [NORMAL]. */
        public fun fromExifValue(value: Int?): ExifOrientation = entries.firstOrNull { it.exifValue == value } ?: NORMAL
    }
}
