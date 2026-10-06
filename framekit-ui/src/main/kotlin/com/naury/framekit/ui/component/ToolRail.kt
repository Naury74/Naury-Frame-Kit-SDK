package com.naury.framekit.ui.component

import androidx.compose.foundation.background
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

/** One entry of the [ToolRail]. */
public data class ToolRailItem<T>(val key: T, val label: String, val icon: Painter)

/** Bottom row of editing tools. Selection is shown by color and by the accessibility selected state. */
@Composable
public fun <T> ToolRail(
    items: List<ToolRailItem<T>>,
    selected: T?,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 다섯 개까지는 폭을 고르게 나누고, 더 많으면 가로로 스크롤한다.
    val scrolling = items.size > EVEN_ITEMS
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (scrolling) Modifier.horizontalScroll(rememberScrollState()) else Modifier)
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
