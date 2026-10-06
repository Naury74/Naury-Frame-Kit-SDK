package com.naury.framekit.ui.canvas

import com.naury.framekit.core.geometry.CropHandle
import com.naury.framekit.core.geometry.RectN
import kotlin.math.abs

/** 포인터 아래의 자르기 핸들을 viewport 픽셀 기준으로 찾는다. 모서리가 변보다, 변이 본체보다 우선한다. */
public object CropFrameHitTest {

    public fun find(frame: RectN, x: Double, y: Double, touchRadius: Double): CropHandle? {
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
