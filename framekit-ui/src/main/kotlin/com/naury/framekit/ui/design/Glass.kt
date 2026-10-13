package com.naury.framekit.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * FrameKit 화면의 떠 있는 버튼·묶음에 쓰는 공통 표면. 테마(어두움·밝음)에 맞춰 채움과 테두리를 고른다.
 *
 * @param tint 채움 색. `null`이면 테마 기본값. 주요 동작(저장·완료)은 강조색을 넘긴다.
 */
@Composable
public fun Modifier.frameKitGlass(shape: Shape = CircleShape, tint: Color? = null): Modifier {
    val colors = FrameKitTheme.colors
    val fill = tint ?: if (colors.isDark) Color.White.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.72f)
    val edge = if (colors.isDark) {
        Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.32f), Color.White.copy(alpha = 0.06f)))
    } else {
        Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.95f), Color.Black.copy(alpha = 0.08f)))
    }
    val sheen = Brush.verticalGradient(listOf(Color.White.copy(alpha = if (colors.isDark) 0.10f else 0.35f), Color.Transparent))
    return this
        .shadow(elevation = 6.dp, shape = shape, ambientColor = Color.Black.copy(alpha = 0.25f), spotColor = Color.Black.copy(alpha = 0.25f))
        .clip(shape)
        .background(fill)
        .background(sheen)
        .border(0.75.dp, edge, shape)
}
