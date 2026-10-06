package com.naury.framekit.ui.tool

import androidx.compose.foundation.layout.Column
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
import com.naury.framekit.core.overlay.FontCatalog
import com.naury.framekit.core.overlay.ImageOverlay
import com.naury.framekit.core.overlay.ShadowSpec
import com.naury.framekit.core.overlay.StrokeSpec
import com.naury.framekit.core.overlay.TextAlignment
import com.naury.framekit.core.overlay.TextStyleSpec
import com.naury.framekit.ui.component.ChoiceChips
import com.naury.framekit.ui.design.FrameKitTheme
import com.naury.framekit.ui.R

/** 글꼴, 색상, 정렬, 외곽선, 배경, 그림자를 지정하는 텍스트 입력. */
@Composable
public fun TextToolPanel(
    text: ImageOverlay.Text,
    onText: (String) -> Unit,
    onStyle: ((TextStyleSpec) -> TextStyleSpec) -> Unit,
    modifier: Modifier = Modifier,
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
        ChoiceChips(
            options = FontCatalog.fontIds,
            selected = style.fontId,
            label = { stringResource(fontLabel(it)) },
            onSelect = { id -> onStyle { it.copy(fontId = id) } },
            modifier = Modifier.padding(top = 8.dp),
        )
        ColorPalette(selected = style.colorArgb, onSelect = { argb -> onStyle { it.copy(colorArgb = argb) } }, modifier = Modifier.padding(top = 8.dp))
        val options = TextOption.entries
        ChoiceChips(
            options = options,
            selected = options.firstOrNull { it.isOn(style) } ?: options.first(),
            label = { stringResource(it.label) },
            onSelect = { option -> onStyle(option::toggle) },
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

// 한 줄에 정렬과 꾸밈을 함께 두기 위해 토글형 선택지로 묶는다. 정렬은 하나만, 꾸밈은 켜고 끈다.
private enum class TextOption(val label: Int) {
    ALIGN_START(R.string.framekit_align_start),
    ALIGN_CENTER(R.string.framekit_align_center),
    ALIGN_END(R.string.framekit_align_end),
    OUTLINE(R.string.framekit_text_outline),
    BACKGROUND(R.string.framekit_text_background),
    SHADOW(R.string.framekit_text_shadow),
    ;

    fun isOn(style: TextStyleSpec): Boolean = when (this) {
        ALIGN_START -> style.alignment == TextAlignment.START
        ALIGN_CENTER -> style.alignment == TextAlignment.CENTER
        ALIGN_END -> style.alignment == TextAlignment.END
        OUTLINE -> style.stroke != null
        BACKGROUND -> style.background != null
        SHADOW -> style.shadow != null
    }

    fun toggle(style: TextStyleSpec): TextStyleSpec = when (this) {
        ALIGN_START -> style.copy(alignment = TextAlignment.START)
        ALIGN_CENTER -> style.copy(alignment = TextAlignment.CENTER)
        ALIGN_END -> style.copy(alignment = TextAlignment.END)
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

private fun fontLabel(id: String): Int = when (id) {
    "sans-bold" -> R.string.framekit_font_sans_bold
    "serif" -> R.string.framekit_font_serif
    "serif-bold" -> R.string.framekit_font_serif_bold
    "mono" -> R.string.framekit_font_mono
    "handwriting" -> R.string.framekit_font_handwriting
    else -> R.string.framekit_font_sans
}
