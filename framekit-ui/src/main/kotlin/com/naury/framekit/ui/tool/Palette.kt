package com.naury.framekit.ui.tool

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.naury.framekit.ui.design.FrameKitTheme
import com.naury.framekit.ui.R

/** 텍스트와 그리기에 제공하는 색상. */
public val PaletteColors: List<Int> = listOf(
    0xFFFFFFFF, 0xFF000000, 0xFF8E8E93, 0xFFFF3B30, 0xFFFF9500, 0xFFFFCC00,
    0xFF34C759, 0xFF00C7BE, 0xFF007AFF, 0xFF5856D6, 0xFFAF52DE, 0xFFFF2D55,
).map { it.toInt() }

// PaletteColors와 같은 순서의 색 이름. 화면 읽기 프로그램이 번호 대신 색 이름을 읽는다.
private val PaletteNames = listOf(
    R.string.framekit_color_white, R.string.framekit_color_black, R.string.framekit_color_gray,
    R.string.framekit_color_red, R.string.framekit_color_orange, R.string.framekit_color_yellow,
    R.string.framekit_color_green, R.string.framekit_color_teal, R.string.framekit_color_blue,
    R.string.framekit_color_indigo, R.string.framekit_color_purple, R.string.framekit_color_pink,
)

/**
 * 색상 견본 행. 선택 상태가 색상에만 의존하지 않도록 선택된 견본에 링과 체크를 표시한다.
 * 견본은 36dp로 보이지만 누르는 영역은 48dp다.
 */
@Composable
public fun ColorPalette(selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val colors = FrameKitTheme.colors
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp).selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PaletteColors.forEachIndexed { index, argb ->
            val isSelected = argb == selected
            val description = PaletteNames.getOrNull(index)?.let { stringResource(it) } ?: stringResource(R.string.framekit_color_swatch, index + 1)
            Box(
                Modifier
                    .size(48.dp)
                    .semantics { contentDescription = description }
                    .selectable(selected = isSelected, role = Role.RadioButton) { onSelect(argb) },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(36.dp)
                        .border(if (isSelected) 2.dp else 1.dp, if (isSelected) colors.accent else colors.raised, CircleShape)
                        .padding(4.dp)
                        .background(Color(argb), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    if (isSelected) {
                        Icon(Icons.Filled.Check, contentDescription = null, tint = if (isLight(argb)) Color.Black else Color.White, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
    }
}

private fun isLight(argb: Int): Boolean {
    val r = (argb shr 16) and 0xFF
    val g = (argb shr 8) and 0xFF
    val b = argb and 0xFF
    return r * 299 + g * 587 + b * 114 > 150_000
}
