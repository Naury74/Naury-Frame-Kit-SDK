package com.naury.framekit.core.model

/**
 * Size in physical pixels.
 *
 * Instances may hold zero or negative values so that invalid host input can be reported through
 * validation instead of a construction crash. Use [isValid] before doing arithmetic with them.
 */
public data class PixelSize(val width: Int, val height: Int) {
    /** `true` when both dimensions are positive. */
    public val isValid: Boolean get() = width > 0 && height > 0

    /** Total pixel count as [Long] so that large images do not overflow. */
    public val pixelCount: Long get() = width.toLong() * height.toLong()

    /** Width divided by height. Only meaningful when [isValid]. */
    public val aspectRatio: Double get() = width.toDouble() / height.toDouble()

    /** Size with width and height exchanged, as produced by a 90° or 270° rotation. */
    public fun transposed(): PixelSize = PixelSize(height, width)
}
