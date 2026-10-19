package com.naury.framekit.ui.component

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.naury.framekit.ui.design.FrameKitTheme
import com.naury.kinetica.ui.KChipColors
import com.naury.kinetica.ui.KChipGroup
import com.naury.kinetica.ui.KChipSelection

/**
 * 가로로 스크롤되는 단일 선택 칩. 자르기 비율과 프리셋에 쓴다.
 *
 * 선택된 칩은 프라이머리 색으로 채우고 체크 표시를 둬 색만으로 선택을 구분하지 않게 한다. 화면 밖에 칩이 더 있으면 그쪽 끝을 흐린다.
 * 햅틱은 [FrameKitTheme]의 햅틱 설정을 따른다.
 */
@Composable
public fun <T> ChoiceChips(
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = FrameKitTheme.colors
    val chipColors = remember(colors) {
        KChipColors(selected = colors.accent, onSelected = colors.onAccent, unselected = colors.surface, onUnselected = colors.foregroundMuted)
    }
    val selectedIndex = options.indexOf(selected)
    KChipGroup(
        chips = options.map { label(it) },
        selected = if (selectedIndex >= 0) setOf(selectedIndex) else emptySet(),
        // 고른 칩을 다시 눌러도 선택을 풀지 않고 그 값을 다시 알린다.
        onSelectedChange = { picked -> picked.firstOrNull()?.let { onSelect(options[it]) } },
        modifier = modifier,
        selection = KChipSelection.Single,
        wrap = false,
        contentPadding = PaddingValues(horizontal = 16.dp),
        colors = chipColors,
        fadeEdges = true,
        // Kinetica 칩은 34dp라 그대로 두면 손가락으로 누를 영역이 48dp보다 작다.
        minTouchTarget = true,
        // 칩마다 48dp 여유가 있어 그림자가 잘리지 않으므로, 위아래 여유를 더하지 않아 줄 높이를 48dp로 둔다.
        shadowRoom = false,
    )
}
