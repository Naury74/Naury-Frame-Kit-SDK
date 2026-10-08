package com.naury.framekit.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.naury.framekit.ui.design.FrameKitTheme

/** [ToolRail]의 항목 하나. */
public data class ToolRailItem<T>(val key: T, val label: String, val icon: Painter)

/** 편집 도구의 하단 행. 선택 상태는 색상과 접근성 selected 상태로 함께 표시한다. */
@Composable
public fun <T> ToolRail(
    items: List<ToolRailItem<T>>,
    selected: T?,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 다섯 개까지는 폭을 고르게 나누고, 더 많으면 가로로 스크롤한다.
    val scrolling = items.size > EVEN_ITEMS
    val scroll = rememberScrollState()
    Row(
        modifier = modifier
            .fillMaxWidth()
            // 화면 밖에 도구가 더 있으면 끝을 흐리게 해 스크롤할 수 있다는 것을 알린다.
            .then(if (scrolling) Modifier.horizontalFadingEdges(scroll).horizontalScroll(scroll) else Modifier)
            .padding(vertical = 8.dp, horizontal = if (scrolling) 8.dp else 0.dp)
            .selectableGroup(),
        horizontalArrangement = if (scrolling) Arrangement.spacedBy(4.dp) else Arrangement.SpaceEvenly,
    ) {
        items.forEach { item ->
            val isSelected = item.key == selected
            val colors = FrameKitTheme.colors
            Column(
                modifier = Modifier
                    .widthIn(min = 72.dp)
                    .selectable(selected = isSelected, role = Role.Tab, onClick = { onSelect(item.key) })
                    .padding(vertical = 6.dp, horizontal = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    item.icon,
                    contentDescription = null,
                    tint = if (isSelected) colors.accent else colors.foreground,
                    modifier = Modifier
                        .background(if (isSelected) colors.raised else colors.background, MaterialTheme.shapes.small)
                        .padding(8.dp)
                        .size(24.dp),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    item.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isSelected) colors.foreground else colors.foregroundMuted,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

private const val EVEN_ITEMS = 5

/**
 * 모든 도구를 [columns]열씩 한꺼번에 보여준다. 넓은 화면의 사이드 패널에서 스크롤 행을 쓰면
 * 도구가 가려지고 패널 대부분이 비기 때문이다.
 */
@Composable
public fun <T> ToolGrid(
    items: List<ToolRailItem<T>>,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    columns: Int = 3,
) {
    val colors = FrameKitTheme.colors
    Column(modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.chunked(columns).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { item ->
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .background(colors.surface, MaterialTheme.shapes.medium)
                            .clip(MaterialTheme.shapes.medium)
                            .clickable(role = Role.Button) { onSelect(item.key) }
                            .padding(vertical = 14.dp, horizontal = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(item.icon, contentDescription = null, tint = colors.foreground, modifier = Modifier.size(24.dp))
                        Spacer(Modifier.height(6.dp))
                        Text(item.label, style = MaterialTheme.typography.labelMedium, color = colors.foregroundMuted, textAlign = TextAlign.Center)
                    }
                }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}
