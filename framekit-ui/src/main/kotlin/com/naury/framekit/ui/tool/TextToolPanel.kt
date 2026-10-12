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
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.MaterialTheme
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
 * 글꼴, 글자 색, 정렬, 꾸밈(외곽선·배경·그림자), 불투명도, 자간, 행간을 지정하는 텍스트 입력.
 * 꾸밈을 켜면 그 아래에 세부 설정이 나온다. 외곽선은 색과 두께, 배경은 색·불투명도·모서리 둥글기·여백,
 * 그림자는 색·흐림·거리·불투명도를 고른다.
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
        // 글자 색과 배경 색 견본이 함께 보일 수 있으므로 각 줄에 이름을 붙인다.
        SectionLabel(stringResource(R.string.framekit_text_color))
        ColorPalette(selected = style.colorArgb, onSelect = { argb -> onStyle { it.copy(colorArgb = argb) } })
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
        style.stroke?.let { stroke -> OutlineSettings(stroke, onStyle) }
        style.background?.let { background -> BackgroundSettings(background, onStyle) }
        style.shadow?.let { shadow -> ShadowSettings(shadow, onStyle) }
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
private const val ALPHA_MASK = 0xFF000000.toInt()
private const val RGB_MASK = 0x00FFFFFF

// 꾸밈 크기는 모두 캔버스 높이에 대한 비율이다. 기본값은 꾸밈을 처음 켤 때의 값과 같다.
private const val STROKE_STEP = 0.002
private const val STROKE_LEVELS = 10f
private const val DEFAULT_STROKE_LEVEL = 3f
private const val MAX_CORNER = 0.045
private const val DEFAULT_CORNER = 0.006
private const val MIN_PADDING = 0.002
private const val MAX_PADDING = 0.03
private const val DEFAULT_PADDING = 0.008
private const val MIN_BACKGROUND_ALPHA = 0.1f
private const val DEFAULT_BACKGROUND_ALPHA = 0.8f
private const val MAX_SHADOW_BLUR = 0.03
private const val DEFAULT_SHADOW_BLUR = 0.006
private const val MAX_SHADOW_DISTANCE = 0.024
private val DEFAULT_SHADOW_DISTANCE = hypot(0.002, 0.003)
private val DEFAULT_SHADOW_ANGLE = atan2(0.003, 0.002)
private const val DEFAULT_SHADOW_ALPHA = 0.6f

/** 외곽선 색과 두께. 두께는 1~10단계로 보여 주고 캔버스 높이 비율로 저장한다. */
@Composable
private fun OutlineSettings(stroke: StrokeSpec, onStyle: ((TextStyleSpec) -> TextStyleSpec) -> Unit) {
    SectionLabel(stringResource(R.string.framekit_text_outline_color))
    ColorPalette(selected = stroke.colorArgb or ALPHA_MASK, onSelect = { argb -> onStyle { it.copy(stroke = it.stroke?.copy(colorArgb = argb or ALPHA_MASK)) } })
    ValueSlider(
        label = stringResource(R.string.framekit_text_outline_width),
        value = (stroke.widthHeightRatio / STROKE_STEP).toFloat().coerceIn(1f, STROKE_LEVELS),
        valueRange = 1f..STROKE_LEVELS,
        onValueChange = { level -> onStyle { it.copy(stroke = it.stroke?.copy(widthHeightRatio = level.roundToInt() * STROKE_STEP)) } },
        onValueChangeFinished = {},
        formatValue = { it.roundToInt().toString() },
        resetValue = DEFAULT_STROKE_LEVEL,
    )
}

/** 배경 색·불투명도·모서리 둥글기·여백. 견본은 불투명한 색이라 투명도는 따로 고른다. */
@Composable
private fun BackgroundSettings(background: BackgroundSpec, onStyle: ((TextStyleSpec) -> TextStyleSpec) -> Unit) {
    SectionLabel(stringResource(R.string.framekit_text_background_color))
    ColorPalette(
        selected = background.colorArgb or ALPHA_MASK,
        onSelect = { argb -> onStyle { it.copy(background = it.background?.let { bg -> bg.copy(colorArgb = (argb and RGB_MASK) or (bg.colorArgb and ALPHA_MASK)) }) } },
    )
    PercentSlider(R.string.framekit_text_background_opacity, alphaOf(background.colorArgb), MIN_BACKGROUND_ALPHA, DEFAULT_BACKGROUND_ALPHA) { alpha ->
        onStyle { it.copy(background = it.background?.let { bg -> bg.copy(colorArgb = withAlpha(bg.colorArgb, alpha)) }) }
    }
    PercentSlider(R.string.framekit_text_background_corner, (background.cornerHeightRatio / MAX_CORNER).toFloat(), 0f, (DEFAULT_CORNER / MAX_CORNER).toFloat()) { t ->
        onStyle { it.copy(background = it.background?.copy(cornerHeightRatio = t * MAX_CORNER)) }
    }
    PercentSlider(
        R.string.framekit_text_background_padding,
        ((background.paddingHeightRatio - MIN_PADDING) / (MAX_PADDING - MIN_PADDING)).toFloat(),
        0f,
        ((DEFAULT_PADDING - MIN_PADDING) / (MAX_PADDING - MIN_PADDING)).toFloat(),
    ) { t -> onStyle { it.copy(background = it.background?.copy(paddingHeightRatio = MIN_PADDING + t * (MAX_PADDING - MIN_PADDING))) } }
}

/** 그림자 색·흐림·거리·불투명도. 방향은 지금 방향(기본은 오른쪽 아래)을 유지하고 거리만 바꾼다. */
@Composable
private fun ShadowSettings(shadow: ShadowSpec, onStyle: ((TextStyleSpec) -> TextStyleSpec) -> Unit) {
    SectionLabel(stringResource(R.string.framekit_text_shadow_color))
    ColorPalette(
        selected = shadow.colorArgb or ALPHA_MASK,
        onSelect = { argb -> onStyle { it.copy(shadow = it.shadow?.let { sh -> sh.copy(colorArgb = (argb and RGB_MASK) or (sh.colorArgb and ALPHA_MASK)) }) } },
    )
    PercentSlider(R.string.framekit_text_shadow_blur, (shadow.blurHeightRatio / MAX_SHADOW_BLUR).toFloat(), 0f, (DEFAULT_SHADOW_BLUR / MAX_SHADOW_BLUR).toFloat()) { t ->
        onStyle { it.copy(shadow = it.shadow?.copy(blurHeightRatio = t * MAX_SHADOW_BLUR)) }
    }
    val distance = hypot(shadow.offsetXHeightRatio, shadow.offsetYHeightRatio)
    PercentSlider(R.string.framekit_text_shadow_distance, (distance / MAX_SHADOW_DISTANCE).toFloat(), 0f, (DEFAULT_SHADOW_DISTANCE / MAX_SHADOW_DISTANCE).toFloat()) { t ->
        onStyle { style ->
            style.copy(
                shadow = style.shadow?.let { sh ->
                    // 거리가 0이 되어 방향을 잃었으면 기본 방향(오른쪽 아래)으로 다시 민다.
                    val current = hypot(sh.offsetXHeightRatio, sh.offsetYHeightRatio)
                    val angle = if (current > 0.0) atan2(sh.offsetYHeightRatio, sh.offsetXHeightRatio) else DEFAULT_SHADOW_ANGLE
                    val length = t * MAX_SHADOW_DISTANCE
                    sh.copy(offsetXHeightRatio = cos(angle) * length, offsetYHeightRatio = sin(angle) * length)
                },
            )
        }
    }
    PercentSlider(R.string.framekit_text_shadow_opacity, alphaOf(shadow.colorArgb), 0f, DEFAULT_SHADOW_ALPHA) { alpha ->
        onStyle { it.copy(shadow = it.shadow?.let { sh -> sh.copy(colorArgb = withAlpha(sh.colorArgb, alpha)) }) }
    }
}

/** `0..1` 값을 0~100%로 보여 주는 슬라이더. [min]보다 작게는 내려가지 않는다. */
@Composable
private fun PercentSlider(label: Int, value: Float, min: Float, reset: Float, onChange: (Double) -> Unit) {
    ValueSlider(
        label = stringResource(label),
        value = value.coerceIn(min, 1f),
        valueRange = min..1f,
        onValueChange = { onChange(it.toDouble()) },
        onValueChangeFinished = {},
        formatValue = { "${(it * 100).roundToInt()}%" },
        resetValue = reset,
        snapThreshold = 0.02f,
    )
}

private fun alphaOf(argb: Int): Float = ((argb ushr 24) and 0xFF) / 255f

private fun withAlpha(argb: Int, alpha: Double): Int = ((alpha.coerceIn(0.0, 1.0) * 255).roundToInt() shl 24) or (argb and RGB_MASK)

/** 견본 줄 위의 작은 이름. */
@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        color = FrameKitTheme.colors.foregroundMuted,
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 2.dp),
    )
}

private fun fontLabel(id: String): Int = when (id) {
    "sans-bold" -> R.string.framekit_font_sans_bold
    "serif" -> R.string.framekit_font_serif
    "serif-bold" -> R.string.framekit_font_serif_bold
    "mono" -> R.string.framekit_font_mono
    "handwriting" -> R.string.framekit_font_handwriting
    else -> R.string.framekit_font_sans
}
