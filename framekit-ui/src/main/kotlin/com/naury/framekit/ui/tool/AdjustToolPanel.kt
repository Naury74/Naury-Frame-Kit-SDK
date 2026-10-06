package com.naury.framekit.ui.tool

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.naury.framekit.core.effect.AdjustmentKind
import com.naury.framekit.core.effect.Adjustments
import com.naury.framekit.ui.component.ValueSlider
import com.naury.framekit.ui.design.FrameKitTheme

/**
 * Horizontal list of adjustments and the slider of the selected one. Each drag is one undo step;
 * double-tapping the slider returns the value to zero.
 */
@Composable
public fun AdjustToolPanel(
    adjustments: Adjustments,
    selected: AdjustmentKind,
    onSelect: (AdjustmentKind) -> Unit,
    onChange: (Float) -> Unit,
    onChangeFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = FrameKitTheme.colors
    Column(modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        LazyRow(
            contentPadding = PaddingValues(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.selectableGroup(),
        ) {
            items(AdjustmentKind.entries) { kind ->
                val isSelected = kind == selected
                val display = kind.toDisplay(adjustments[kind])
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .widthIn(min = 64.dp)
                        .selectable(selected = isSelected, role = Role.Tab) { onSelect(kind) }
                        .padding(horizontal = 6.dp, vertical = 6.dp),
                ) {
                    Text(
                        formatAdjustment(kind, display),
                        color = if (isSelected) colors.accent else colors.foregroundMuted,
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Text(
                        stringResource(kind.labelRes()),
                        color = if (isSelected) colors.foreground else colors.foregroundMuted,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                    )
                    // 색만으로 선택을 구분하지 않도록 값이 바뀐 항목에 점을 표시한다.
                    Box(
                        Modifier
                            .padding(top = 4.dp)
                            .size(4.dp)
                            .background(if (display != 0) colors.accent else colors.background, CircleShape),
                    )
                }
            }
        }
        ValueSlider(
            label = stringResource(selected.labelRes()),
            value = selected.toDisplay(adjustments[selected]).toFloat(),
            valueRange = selected.displayMinimum.toFloat()..AdjustmentKind.DISPLAY_RANGE.toFloat(),
            onValueChange = onChange,
            onValueChangeFinished = onChangeFinished,
            formatValue = { value -> formatAdjustment(selected, Math.round(value)) },
            snapThreshold = if (selected.signed) 2f else 0f,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

// 0을 기준으로 양쪽으로 움직이는 항목만 부호를 붙인다.
private fun formatAdjustment(kind: AdjustmentKind, display: Int): String =
    if (kind.signed && display != 0) "%+d".format(display) else display.toString()
