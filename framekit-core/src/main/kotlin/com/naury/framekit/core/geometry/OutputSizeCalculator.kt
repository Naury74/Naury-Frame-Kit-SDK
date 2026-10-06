package com.naury.framekit.core.geometry

import com.naury.framekit.core.model.PixelSize
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** 자르기에 대한 인코딩 출력 크기를 정한다. 출력은 절대 확대하지 않는다. */
public object OutputSizeCalculator {

    /**
     * @param cropSize 원본 픽셀 기준 자르기 크기.
     * @param maxPixels `width × height`의 상한.
     * @param maxWidth 출력 너비의 선택적 상한.
     * @param maxHeight 출력 높이의 선택적 상한.
     * @throws IllegalArgumentException 한도가 양수가 아니거나 [cropSize]가 비어 있을 때.
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
