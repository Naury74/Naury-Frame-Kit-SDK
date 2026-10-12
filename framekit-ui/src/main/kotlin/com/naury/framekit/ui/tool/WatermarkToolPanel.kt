package com.naury.framekit.ui.tool

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.naury.framekit.core.overlay.Watermark
import com.naury.framekit.core.overlay.WatermarkPattern
import com.naury.framekit.core.overlay.WatermarkPosition
import com.naury.framekit.ui.R
import com.naury.framekit.ui.component.ValueSlider
import com.naury.framekit.ui.component.horizontalFadingEdges
import com.naury.framekit.ui.design.FrameKitTheme
import kotlin.math.roundToInt

/**
 * 글자 워터마크 설정. 문구를 쓰고 무늬(한 곳·반복·대각선·띠)를 그림 카드로 고른 뒤, 무늬에 맞는 세부 설정
 * (자리, 기울기·간격, 크기, 불투명도, 색, 상자·그림자)을 조절한다.
 *
 * @param watermark 지금 편집 중인 워터마크. 문구가 비어 있으면 화면에 그려지지 않는다.
 * @param suggestions 빠르게 고를 문구 후보. 호스트가 넘긴다. 비어 있으면 숨긴다.
 * @param onChange 워터마크를 바꾼다. 슬라이더는 움직일 때마다 호출된다.
 * @param onRemove 워터마크를 없앤다. `null`이면 버튼을 숨긴다.
 */
@Composable
public fun WatermarkToolPanel(
    watermark: Watermark,
    suggestions: List<String>,
    onChange: ((Watermark) -> Watermark) -> Unit,
    modifier: Modifier = Modifier,
    onRemove: (() -> Unit)? = null,
) {
    val colors = FrameKitTheme.colors
    Column(modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = watermark.text,
                onValueChange = { value -> onChange { it.copy(text = value.replace('\n', ' ').take(Watermark.MAX_TEXT_LENGTH)) } },
                placeholder = { Text(stringResource(R.string.framekit_watermark_hint)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = colors.foreground,
                    unfocusedTextColor = colors.foreground,
                    focusedBorderColor = colors.accent,
                    unfocusedBorderColor = colors.raised,
                    cursorColor = colors.accent,
                ),
                modifier = Modifier.weight(1f),
            )
            if (onRemove != null) {
                TextButton(onClick = onRemove, modifier = Modifier.padding(start = 4.dp)) {
                    Icon(Icons.Filled.Delete, contentDescription = null, tint = colors.foregroundMuted, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.framekit_watermark_remove), color = colors.foregroundMuted)
                }
            }
        }
        if (suggestions.isNotEmpty()) SuggestionRow(suggestions, watermark.text) { text -> onChange { it.copy(text = text) } }

        PatternCards(selected = watermark.pattern, onSelect = { pattern -> onChange { it.copy(pattern = pattern) } })

        when (watermark.pattern) {
            WatermarkPattern.SINGLE, WatermarkPattern.BAND -> {
                Label(stringResource(R.string.framekit_watermark_position))
                PositionPicker(
                    selected = watermark.position,
                    rows = if (watermark.pattern == WatermarkPattern.BAND) listOf(-1, 1) else listOf(-1, 0, 1),
                    onSelect = { position -> onChange { it.copy(position = position) } },
                )
            }
            WatermarkPattern.TILE -> {
                ValueSlider(
                    label = stringResource(R.string.framekit_watermark_angle),
                    value = watermark.rotationDegrees.toFloat(),
                    valueRange = -90f..90f,
                    onValueChange = { value -> onChange { it.copy(rotationDegrees = value.roundToInt().toDouble()) } },
                    onValueChangeFinished = {},
                    formatValue = { "${it.roundToInt()}°" },
                    resetValue = DEFAULT_ANGLE,
                    snapThreshold = 3f,
                    modifier = Modifier.padding(top = 8.dp),
                )
                ValueSlider(
                    label = stringResource(R.string.framekit_watermark_density),
                    value = watermark.density.toFloat(),
                    valueRange = Watermark.MIN_DENSITY.toFloat()..Watermark.MAX_DENSITY.toFloat(),
                    onValueChange = { value -> onChange { it.copy(density = value.toDouble()) } },
                    onValueChangeFinished = {},
                    formatValue = { "${(it * 100).roundToInt()}%" },
                    resetValue = 1f,
                    snapThreshold = 0.05f,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            WatermarkPattern.DIAGONAL -> Unit
        }
        if (watermark.pattern != WatermarkPattern.DIAGONAL) {
            ValueSlider(
                label = stringResource(R.string.framekit_watermark_size),
                value = watermark.sizeRatio.toFloat(),
                valueRange = Watermark.MIN_SIZE.toFloat()..MAX_SIZE_SLIDER,
                onValueChange = { value -> onChange { it.copy(sizeRatio = value.toDouble()) } },
                onValueChangeFinished = {},
                // 기본 크기를 100으로 두고 상대값으로 보여 준다.
                formatValue = { "${(it / Watermark.DEFAULT_SIZE * 100).roundToInt()}%" },
                resetValue = Watermark.DEFAULT_SIZE.toFloat(),
                snapThreshold = 0.002f,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        ValueSlider(
            label = stringResource(R.string.framekit_watermark_opacity),
            value = watermark.opacity.toFloat(),
            valueRange = Watermark.MIN_OPACITY.toFloat()..1f,
            onValueChange = { value -> onChange { it.copy(opacity = value.toDouble()) } },
            onValueChangeFinished = {},
            formatValue = { "${(it * 100).roundToInt()}%" },
            resetValue = DEFAULT_OPACITY,
            modifier = Modifier.padding(top = 4.dp),
        )
        Label(stringResource(R.string.framekit_watermark_color))
        ColorPalette(selected = watermark.colorArgb, onSelect = { argb -> onChange { it.copy(colorArgb = argb) } })
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (watermark.pattern == WatermarkPattern.SINGLE || watermark.pattern == WatermarkPattern.TILE) {
                Toggle(stringResource(R.string.framekit_watermark_box), watermark.background) { on -> onChange { it.copy(background = on) } }
            }
            Toggle(stringResource(R.string.framekit_watermark_shadow), watermark.shadow) { on -> onChange { it.copy(shadow = on) } }
        }
    }
}

@Composable
private fun SuggestionRow(suggestions: List<String>, current: String, onPick: (String) -> Unit) {
    val colors = FrameKitTheme.colors
    val scroll = rememberScrollState()
    Row(
        Modifier.horizontalFadingEdges(scroll).horizontalScroll(scroll).padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        suggestions.forEach { text ->
            val picked = text.trim() == current.trim()
            FilterChip(
                selected = picked,
                onClick = { onPick(text.trim()) },
                label = { Text(text) },
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = colors.surface,
                    labelColor = colors.foregroundMuted,
                    selectedContainerColor = colors.raised,
                    selectedLabelColor = colors.foreground,
                ),
                border = null,
            )
        }
    }
}

// 무늬마다 실제 모양을 줄인 그림 카드. 글자 대신 둥근 막대로 그린다.
@Composable
private fun PatternCards(selected: WatermarkPattern, onSelect: (WatermarkPattern) -> Unit) {
    val colors = FrameKitTheme.colors
    val scroll = rememberScrollState()
    Row(
        Modifier
            .horizontalFadingEdges(scroll)
            .horizontalScroll(scroll)
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        WatermarkPattern.entries.forEach { pattern ->
            val isSelected = pattern == selected
            val ink = if (isSelected) colors.accent else colors.foregroundMuted
            Column(
                Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .selectable(selected = isSelected, role = Role.RadioButton, onClick = { onSelect(pattern) })
                    .padding(4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Canvas(
                    Modifier
                        .size(width = 76.dp, height = 54.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(colors.surface)
                        .border(if (isSelected) 2.dp else 1.dp, if (isSelected) colors.accent else colors.raised, RoundedCornerShape(10.dp)),
                ) { drawPattern(pattern, ink, colors.raised) }
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(patternLabel(pattern)),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (isSelected) colors.foreground else colors.foregroundMuted,
                )
            }
        }
    }
}

private fun DrawScope.drawPattern(pattern: WatermarkPattern, ink: Color, band: Color) {
    val w = size.width
    val h = size.height
    val bar = h * 0.09f
    fun pill(x: Float, y: Float, length: Float, alpha: Float = 1f) =
        drawRoundRect(ink.copy(alpha = alpha), Offset(x - length / 2, y - bar / 2), Size(length, bar), CornerRadius(bar / 2))
    when (pattern) {
        WatermarkPattern.SINGLE -> pill(w * 0.72f, h * 0.8f, w * 0.34f)
        WatermarkPattern.TILE -> rotate(-30f) {
            for (row in -3..3) {
                for (col in -3..3) {
                    val shift = if (row % 2 == 0) 0f else w * 0.16f
                    pill(w / 2 + col * w * 0.32f + shift, h / 2 + row * h * 0.24f, w * 0.2f, 0.75f)
                }
            }
        }
        WatermarkPattern.DIAGONAL -> rotate(-Math.toDegrees(kotlin.math.atan2(h.toDouble(), w.toDouble())).toFloat()) {
            drawRoundRect(ink, Offset(w * 0.18f, h / 2 - bar * 0.9f), Size(w * 0.64f, bar * 1.8f), CornerRadius(bar))
        }
        WatermarkPattern.BAND -> {
            drawRect(band, Offset(0f, h * 0.7f), Size(w, h * 0.3f))
            pill(w / 2, h * 0.85f, w * 0.42f)
        }
    }
}

// 화면을 줄인 사각형 안의 점을 눌러 자리를 고른다. 띠는 위·아래 줄만 보인다.
@Composable
private fun PositionPicker(selected: WatermarkPosition, rows: List<Int>, onSelect: (WatermarkPosition) -> Unit) {
    val colors = FrameKitTheme.colors
    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), contentAlignment = Alignment.CenterStart) {
        Column(
            Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(colors.surface)
                .border(1.dp, colors.raised, RoundedCornerShape(12.dp))
                .padding(6.dp)
                .selectableGroup(),
        ) {
            rows.forEach { vertical ->
                Row {
                    listOf(-1, 0, 1).forEach { horizontal ->
                        val position = WatermarkPosition.entries.first { it.horizontal == horizontal && it.vertical == vertical }
                        val isSelected = position == selected
                        val description = stringResource(positionLabel(position))
                        Box(
                            Modifier
                                .size(width = 52.dp, height = 36.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .selectable(selected = isSelected, role = Role.RadioButton, onClick = { onSelect(position) })
                                .semantics { contentDescription = description },
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(
                                Modifier
                                    .size(if (isSelected) 16.dp else 8.dp)
                                    .background(if (isSelected) colors.accent else colors.foregroundMuted.copy(alpha = 0.5f), CircleShape),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Toggle(label: String, on: Boolean, onChange: (Boolean) -> Unit) {
    val colors = FrameKitTheme.colors
    FilterChip(
        selected = on,
        onClick = { onChange(!on) },
        label = { Text(label) },
        leadingIcon = if (on) {
            { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
        } else {
            null
        },
        colors = FilterChipDefaults.filterChipColors(
            containerColor = colors.surface,
            labelColor = colors.foregroundMuted,
            selectedContainerColor = colors.raised,
            selectedLabelColor = colors.foreground,
            selectedLeadingIconColor = colors.accent,
        ),
        border = null,
        modifier = Modifier.semantics { role = Role.Checkbox },
    )
}

@Composable
private fun Label(text: String) {
    Text(
        text,
        color = FrameKitTheme.colors.foregroundMuted,
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 2.dp),
    )
}

private fun patternLabel(pattern: WatermarkPattern): Int = when (pattern) {
    WatermarkPattern.SINGLE -> R.string.framekit_watermark_single
    WatermarkPattern.TILE -> R.string.framekit_watermark_tile
    WatermarkPattern.DIAGONAL -> R.string.framekit_watermark_diagonal
    WatermarkPattern.BAND -> R.string.framekit_watermark_band
}

private fun positionLabel(position: WatermarkPosition): Int = when (position) {
    WatermarkPosition.TOP_START -> R.string.framekit_watermark_top_start
    WatermarkPosition.TOP_CENTER -> R.string.framekit_watermark_top_center
    WatermarkPosition.TOP_END -> R.string.framekit_watermark_top_end
    WatermarkPosition.CENTER_START -> R.string.framekit_watermark_center_start
    WatermarkPosition.CENTER -> R.string.framekit_watermark_center
    WatermarkPosition.CENTER_END -> R.string.framekit_watermark_center_end
    WatermarkPosition.BOTTOM_START -> R.string.framekit_watermark_bottom_start
    WatermarkPosition.BOTTOM_CENTER -> R.string.framekit_watermark_bottom_center
    WatermarkPosition.BOTTOM_END -> R.string.framekit_watermark_bottom_end
}

private const val DEFAULT_ANGLE = -30f
private const val DEFAULT_OPACITY = 0.7f

// 슬라이더로는 큰 쪽을 짧은 변의 12%까지만 둔다(그 이상은 호스트 설정으로만).
private const val MAX_SIZE_SLIDER = 0.12f
