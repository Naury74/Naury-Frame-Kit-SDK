package com.naury.framekit.core.geometry

import com.naury.framekit.core.model.PixelSize
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Chooses the encoded output size for a crop. Output is never upscaled. */
public object OutputSizeCalculator {

    /**
     * @param cropSize crop size in source pixels.
     * @param maxPixels upper bound of `width × height`.
     * @param maxWidth optional upper bound of the output width.
     * @param maxHeight optional upper bound of the output height.
     * @throws IllegalArgumentException when a limit is not positive or [cropSize] is empty.
     */
    public fun compute(cropSize: Size2D, maxPixels: Long, maxWidth: Int? = null, maxHeight: Int? = null): PixelSize {
        require(cropSize.width > 0.0 && cropSize.height > 0.0) { "Crop size must be positive" }
        require(maxPixels > 0) { "maxPixels must be positive" }
        require(maxWidth == null || maxWidth > 0) { "maxWidth must be positive" }
        require(maxHeight == null || maxHeight > 0) { "maxHeight must be positive" }

        var scale = 1.0
        scale = min(scale, sqrt(maxPixels / (cropSize.width * cropSize.height)))
        if (maxWidth != null) scale = min(scale, maxWidth / cropSize.width)
        if (maxHeight != null) scale = min(scale, maxHeight / cropSize.height)

        var width = max(1, (cropSize.width * scale).roundToInt())
        var height = max(1, (cropSize.height * scale).roundToInt())
        // 반올림으로 한도를 1px 넘는 경우를 다시 맞춘다.
        if (maxWidth != null) width = min(width, maxWidth)
        if (maxHeight != null) height = min(height, maxHeight)
        while (width.toLong() * height > maxPixels) {
            if (width >= height) width-- else height--
        }
        return PixelSize(width, height)
    }
}
