package com.naury.framekit.ui.canvas

import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.naury.framekit.core.geometry.RectN

/**
 * 가장자리 근처의 crop 핸들을 끌 때 시스템 뒤로 가기 제스처가 먼저 반응하지 않도록 핸들 주변만 제외한다.
 * 시스템은 가장자리마다 제외 높이를 제한하므로 화면 전체가 아니라 모서리와 좌우 중앙 핸들만 등록한다.
 */
public fun Modifier.excludeCropHandleGestures(frame: RectN, radius: Float): Modifier {
    val left = frame.left.toFloat()
    val right = frame.right.toFloat()
    val top = frame.top.toFloat()
    val bottom = frame.bottom.toFloat()
    val centerY = (top + bottom) / 2f
    return listOf(
        Offset(left, top), Offset(right, top), Offset(left, bottom), Offset(right, bottom),
        Offset(left, centerY), Offset(right, centerY),
    ).fold(this) { modifier, point ->
        modifier.systemGestureExclusion { Rect(point.x - radius, point.y - radius, point.x + radius, point.y + radius) }
    }
}

/**
 * Crop frame shared by the photo and video editors: dimmed outside, optional thirds grid and handles.
 * [frame] is in viewport pixels.
 */
public fun DrawScope.drawCropFrame(frame: RectN, accent: Color, showGrid: Boolean, showHandles: Boolean) {
    val left = frame.left.toFloat()
    val top = frame.top.toFloat()
    val right = frame.right.toFloat()
    val bottom = frame.bottom.toFloat()
    val scrim = Color.Black.copy(alpha = 0.55f)
    drawRect(scrim, Offset.Zero, Size(size.width, top))
    drawRect(scrim, Offset(0f, bottom), Size(size.width, size.height - bottom))
    drawRect(scrim, Offset(0f, top), Size(left, bottom - top))
    drawRect(scrim, Offset(right, top), Size(size.width - right, bottom - top))

    val line = 1.dp.toPx()
    if (showGrid) {
        val gridColor = Color.White.copy(alpha = 0.45f)
        for (index in 1..2) {
            val x = left + (right - left) * index / 3f
            val y = top + (bottom - top) * index / 3f
            drawLine(gridColor, Offset(x, top), Offset(x, bottom), line)
            drawLine(gridColor, Offset(left, y), Offset(right, y), line)
        }
    }
    drawRect(Color.White, Offset(left, top), Size(right - left, bottom - top), style = Stroke(line))

    if (showHandles) {
        val length = 18.dp.toPx()
        val thickness = 3.dp.toPx()
        val handleColor = Color.White
        listOf(
            Triple(left, top, 1f to 1f),
            Triple(right, top, -1f to 1f),
            Triple(right, bottom, -1f to -1f),
            Triple(left, bottom, 1f to -1f),
        ).forEach { (x, y, direction) ->
            drawLine(handleColor, Offset(x, y), Offset(x + length * direction.first, y), thickness)
            drawLine(handleColor, Offset(x, y), Offset(x, y + length * direction.second), thickness)
        }
        val midLength = 14.dp.toPx()
        val centerX = (left + right) / 2
        val centerY = (top + bottom) / 2
        drawLine(accent, Offset(centerX - midLength / 2, top), Offset(centerX + midLength / 2, top), thickness)
        drawLine(accent, Offset(centerX - midLength / 2, bottom), Offset(centerX + midLength / 2, bottom), thickness)
        drawLine(accent, Offset(left, centerY - midLength / 2), Offset(left, centerY + midLength / 2), thickness)
        drawLine(accent, Offset(right, centerY - midLength / 2), Offset(right, centerY + midLength / 2), thickness)
    }
}
