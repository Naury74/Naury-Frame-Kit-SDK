package com.naury.framekit.core.model

/** Integer rectangle in pixels, `[left, right) × [top, bottom)`. */
public data class PixelRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    public val width: Int get() = right - left
    public val height: Int get() = bottom - top
    public val size: PixelSize get() = PixelSize(width, height)
    public val isEmpty: Boolean get() = width <= 0 || height <= 0

    public companion object {
        public fun of(size: PixelSize): PixelRect = PixelRect(0, 0, size.width, size.height)
    }
}
