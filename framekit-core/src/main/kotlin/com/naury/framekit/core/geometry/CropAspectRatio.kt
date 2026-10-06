package com.naury.framekit.core.geometry

/** Aspect constraint for the crop tool. The project stores only the resulting rectangle. */
public sealed interface CropAspectRatio {
    /** No constraint. Initial and reset crops use the image's own aspect. */
    public data object Free : CropAspectRatio

    /** Aspect of the source after quarter turns, before straighten. */
    public data object Original : CropAspectRatio

    /**
     * Fixed width-to-height ratio in output pixels.
     *
     * @throws IllegalArgumentException when either side is not positive.
     */
    public data class Fixed(val width: Int, val height: Int) : CropAspectRatio {
        init {
            require(width > 0 && height > 0) { "Aspect sides must be positive: $width:$height" }
        }

        val ratio: Double get() = width.toDouble() / height.toDouble()
    }

    public companion object {
        /** Ratios offered by the default crop panel, in display order. */
        public val presets: List<CropAspectRatio> = listOf(
            Free,
            Original,
            Fixed(1, 1),
            Fixed(4, 3),
            Fixed(3, 4),
            Fixed(16, 9),
            Fixed(9, 16),
            Fixed(3, 2),
            Fixed(2, 3),
        )
    }
}
