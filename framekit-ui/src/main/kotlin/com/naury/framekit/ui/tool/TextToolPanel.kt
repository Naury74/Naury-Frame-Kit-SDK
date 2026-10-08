package com.naury.framekit.ui.tool

import com.naury.framekit.ui.component.horizontalFadingEdges
import com.naury.framekit.ui.catalog.LocalCatalogUi
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import com.naury.framekit.ui.component.ValueSlider
import java.util.Locale
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.naury.framekit.core.overlay.BackgroundSpec
import com.naury.framekit.core.overlay.ImageOverlay
import com.naury.framekit.core.overlay.ShadowSpec
import com.naury.framekit.core.overlay.StrokeSpec
import com.naury.framekit.core.overlay.TextAlignment
import com.naury.framekit.core.overlay.TextStyleSpec
import com.naury.framekit.ui.component.ChoiceChips
import com.naury.framekit.ui.design.FrameKitTheme
import com.naury.framekit.ui.R

/**
 * 글꼴, 색상, 정렬, 꾸밈(외곽선·배경·그림자), 불투명도, 자간, 행간을 지정하는 텍스트 입력.
 *
 * @param onOpacity 텍스트 불투명도(0.1..1)를 바꾼다. `null`이면 불투명도 슬라이더를 숨긴다.
 */
@Composable
public fun TextToolPanel(
    text: ImageOverlay.Text,
    onText: (String) -> Unit,
    onStyle: ((TextStyleSpec) -> TextStyleSpec) -> Unit,
    modifier: Modifier = Modifier,
    onOpacity: ((Double) -> Unit)? = null,
) {
    val colors = FrameKitTheme.colors
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val style = text.style
    Column(modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        OutlinedTextField(
            value = text.text,
            onValueChange = onText,
            placeholder = { Text(stringResource(R.string.framekit_text_hint)) },
            maxLines = 4,
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = colors.foreground,
                unfocusedTextColor = colors.foreground,
                focusedBorderColor = colors.accent,
                unfocusedBorderColor = colors.raised,
                cursorColor = colors.accent,
            ),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).focusRequester(focus),
        )
        val catalog = LocalCatalogUi.current
        ChoiceChips(
            options = catalog.fontIds,
            selected = style.fontId,
            label = { catalog.fontLabel(it) ?: stringResource(fontLabel(it)) },
            onSelect = { id -> onStyle { it.copy(fontId = id) } },
            modifier = Modifier.padding(top = 8.dp),
        )
        ColorPalette(selected = style.colorArgb, onSelect = { argb -> onStyle { it.copy(colorArgb = argb) } }, modifier = Modifier.padding(top = 8.dp))
        // 정렬은 하나만 고르고, 꾸밈은 각각 켜고 끈다. 두 성격을 한 줄에 섞으면 무엇이 켜졌는지 알기 어렵다.
        ChoiceChips(
            options = TextAlignment.entries,
            selected = style.alignment,
            label = { stringResource(alignmentLabel(it)) },
            onSelect = { alignment -> onStyle { it.copy(alignment = alignment) } },
            modifier = Modifier.padding(top = 8.dp),
        )
        val decorationScroll = rememberScrollState()
        Row(
            Modifier.horizontalFadingEdges(decorationScroll).horizontalScroll(decorationScroll).padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Decoration.entries.forEach { decoration ->
                val on = decoration.isOn(style)
                FilterChip(
                    selected = on,
                    onClick = { onStyle(decoration::toggle) },
                    label = { Text(stringResource(decoration.label)) },
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
        }
        if (onOpacity != null) {
            ValueSlider(
                label = stringResource(R.string.framekit_text_opacity),
                value = text.transform.opacity.toFloat(),
                valueRange = MIN_OPACITY..1f,
                onValueChange = { onOpacity(it.toDouble()) },
                onValueChangeFinished = {},
                formatValue = { "${(it * 100).roundToInt()}%" },
                resetValue = 1f,
            )
        }
        ValueSlider(
            label = stringResource(R.string.framekit_text_letter_spacing),
            value = style.letterSpacingEm.toFloat(),
            valueRange = -0.1f..0.5f,
            onValueChange = { value -> onStyle { it.copy(letterSpacingEm = value.toDouble()) } },
            onValueChangeFinished = {},
            formatValue = { String.format(Locale.ROOT, "%+.2f", it) },
            resetValue = 0f,
            snapThreshold = 0.01f,
        )
        ValueSlider(
            label = stringResource(R.string.framekit_text_line_spacing),
            value = style.lineSpacingMultiplier.toFloat(),
            valueRange = 0.8f..2f,
            onValueChange = { value -> onStyle { it.copy(lineSpacingMultiplier = value.toDouble()) } },
            onValueChangeFinished = {},
            formatValue = { String.format(Locale.ROOT, "%.1f×", it) },
            resetValue = 1f,
            snapThreshold = 0.03f,
        )
    }
}

private fun alignmentLabel(alignment: TextAlignment): Int = when (alignment) {
    TextAlignment.START -> R.string.framekit_align_start
    TextAlignment.CENTER -> R.string.framekit_align_center
    TextAlignment.END -> R.string.framekit_align_end
}

private enum class Decoration(val label: Int) {
    OUTLINE(R.string.framekit_text_outline),
    BACKGROUND(R.string.framekit_text_background),
    SHADOW(R.string.framekit_text_shadow),
    ;

    fun isOn(style: TextStyleSpec): Boolean = when (this) {
        OUTLINE -> style.stroke != null
        BACKGROUND -> style.background != null
        SHADOW -> style.shadow != null
    }

    fun toggle(style: TextStyleSpec): TextStyleSpec = when (this) {
        OUTLINE -> style.copy(stroke = if (style.stroke == null) StrokeSpec(contrastOf(style.colorArgb), 0.006) else null)
        BACKGROUND -> style.copy(background = if (style.background == null) BackgroundSpec(contrastOf(style.colorArgb) and 0xCCFFFFFF.toInt()) else null)
        SHADOW -> style.copy(shadow = if (style.shadow == null) ShadowSpec(0x99000000.toInt(), 0.002, 0.003, 0.006) else null)
    }

    // 밝은 글자에는 검정, 어두운 글자에는 흰색을 대비색으로 쓴다.
    private fun contrastOf(argb: Int): Int {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return if (0.2126 * r + 0.7152 * g + 0.0722 * b > 140) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
    }
}

private const val MIN_OPACITY = 0.1f

private fun fontLabel(id: String): Int = when (id) {
    "sans-bold" -> R.string.framekit_font_sans_bold
    "serif" -> R.string.framekit_font_serif
    "serif-bold" -> R.string.framekit_font_serif_bold
    "mono" -> R.string.framekit_font_mono
    "handwriting" -> R.string.framekit_font_handwriting
    else -> R.string.framekit_font_sans
}
