package com.naury.framekit.ui.image.editor

import com.naury.framekit.core.geometry.CropHandle
import com.naury.framekit.core.geometry.RectN
import kotlin.math.abs

/** Finds the crop handle under a pointer, in viewport pixels. Corners win over edges, edges over the body. */
internal object CropFrameHitTest {

    fun find(frame: RectN, x: Double, y: Double, touchRadius: Double): CropHandle? {
        val nearLeft = abs(x - frame.left) <= touchRadius
        val nearRight = abs(x - frame.right) <= touchRadius
        val nearTop = abs(y - frame.top) <= touchRadius
        val nearBottom = abs(y - frame.bottom) <= touchRadius
        val withinX = x >= frame.left - touchRadius && x <= frame.right + touchRadius
        val withinY = y >= frame.top - touchRadius && y <= frame.bottom + touchRadius
        if (!withinX || !withinY) return null
        return when {
            nearLeft && nearTop -> CropHandle.TOP_LEFT
            nearRight && nearTop -> CropHandle.TOP_RIGHT
            nearRight && nearBottom -> CropHandle.BOTTOM_RIGHT
            nearLeft && nearBottom -> CropHandle.BOTTOM_LEFT
            nearLeft -> CropHandle.LEFT
            nearRight -> CropHandle.RIGHT
            nearTop -> CropHandle.TOP
            nearBottom -> CropHandle.BOTTOM
            x > frame.left && x < frame.right && y > frame.top && y < frame.bottom -> CropHandle.MOVE
            else -> null
        }
    }
}
