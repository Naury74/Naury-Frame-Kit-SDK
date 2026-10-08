package com.naury.framekit.ui.tool

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.naury.framekit.core.effect.AdjustmentKind
import com.naury.framekit.core.effect.Adjustments
import com.naury.framekit.ui.component.DialSlider
import com.naury.framekit.ui.component.horizontalFadingEdges
import com.naury.framekit.ui.design.FrameKitTheme

/**
 * 선택한 보정 항목의 눈금 다이얼과 보정 항목의 원형 버튼 목록. 드래그 한 번이 실행 취소 한 단계이며,
 * 다이얼을 두 번 탭하면 값이 0으로 돌아간다.
 *
 * 각 버튼의 테두리 호는 그 항목의 값(0에서 얼마나 바뀌었는지)을 보여 주고, 선택한 항목은 아이콘 대신 숫자를 띄운다.
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
    Column(modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp)) {
        DialSlider(
            label = stringResource(selected.labelRes()),
            value = selected.toDisplay(adjustments[selected]).toFloat(),
            valueRange = selected.displayMinimum.toFloat()..AdjustmentKind.DISPLAY_RANGE.toFloat(),
            onValueChange = onChange,
            onValueChangeFinished = onChangeFinished,
            formatValue = { value -> formatAdjustment(selected, Math.round(value)) },
            snapThreshold = if (selected.signed) 1.5f else 0f,
        )
        val listState = rememberLazyListState()
        // 선택한 항목이 화면 밖에 있으면 보이도록 스크롤한다.
        LaunchedEffect(selected) {
            val index = AdjustmentKind.entries.indexOf(selected)
            val visible = listState.layoutInfo.visibleItemsInfo.any { it.index == index && it.offset >= 0 }
            if (!visible) listState.animateScrollToItem((index - 1).coerceAtLeast(0))
        }
        LazyRow(
            state = listState,
            contentPadding = PaddingValues(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.padding(top = 12.dp).horizontalFadingEdges(listState).selectableGroup(),
        ) {
            items(AdjustmentKind.entries) { kind ->
                AdjustmentItem(
                    kind = kind,
                    display = kind.toDisplay(adjustments[kind]),
                    selected = kind == selected,
                    onClick = { onSelect(kind) },
                )
            }
        }
    }
}

@Composable
private fun AdjustmentItem(kind: AdjustmentKind, display: Int, selected: Boolean, onClick: () -> Unit) {
    val colors = FrameKitTheme.colors
    val label = stringResource(kind.labelRes())
    val valueText = formatAdjustment(kind, display)
    // 값이 바뀌면 호가 부드럽게 늘고 줄어든다.
    val fraction by animateFloatAsState(display / AdjustmentKind.DISPLAY_RANGE.toFloat(), label = "adjust-ring")
    val ringColor by animateColorAsState(if (selected) colors.foreground.copy(alpha = 0.85f) else colors.raised, label = "adjust-ring-bg")
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(64.dp)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .semantics { stateDescription = valueText }
            .padding(vertical = 4.dp),
    ) {
        Box(Modifier.size(52.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(52.dp)) {
                val stroke = 2.5.dp.toPx()
                val inset = stroke / 2f
                val arcSize = Size(size.width - stroke, size.height - stroke)
                drawCircle(ringColor, size.minDimension / 2f - inset, style = Stroke(stroke))
                if (display != 0) {
                    // 12시에서 시작해 + 값은 시계 방향, − 값은 반시계 방향으로 호를 그린다.
                    drawArc(colors.accent, -90f, 360f * fraction, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
                }
                if (!selected || display == 0) {
                    drawAdjustmentGlyph(kind, center, size.minDimension * 0.42f, if (selected) colors.foreground else colors.foregroundMuted)
                }
            }
            if (selected && display != 0) {
                Text(valueText, color = colors.foreground, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        Text(
            label,
            color = if (selected) colors.foreground else colors.foregroundMuted,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

// 0을 기준으로 양쪽으로 움직이는 항목만 부호를 붙인다.
private fun formatAdjustment(kind: AdjustmentKind, display: Int): String =
    if (kind.signed && display != 0) "%+d".format(display) else display.toString()
