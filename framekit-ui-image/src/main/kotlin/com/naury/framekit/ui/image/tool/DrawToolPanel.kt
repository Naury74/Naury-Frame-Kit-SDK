package com.naury.framekit.ui.image.tool

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.naury.framekit.core.overlay.BrushKind
import com.naury.framekit.ui.component.ChoiceChips
import com.naury.framekit.ui.component.ValueSlider
import com.naury.framekit.ui.image.R
import com.naury.framekit.ui.image.editor.BrushSettings
import kotlin.math.roundToInt

/** Brush, color, width and opacity of the drawing tool. Strokes are drawn with one finger on the canvas. */
@Composable
internal fun DrawToolPanel(
    brush: BrushSettings,
    onBrush: ((BrushSettings) -> BrushSettings) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        ChoiceChips(
            options = BrushKind.entries,
            selected = brush.kind,
            label = { stringResource(it.label()) },
            onSelect = { kind -> onBrush { it.copy(kind = kind) } },
        )
        if (brush.kind != BrushKind.ERASER) {
            ColorPalette(selected = brush.colorArgb, onSelect = { argb -> onBrush { it.copy(colorArgb = argb) } }, modifier = Modifier.padding(top = 8.dp))
        }
        ValueSlider(
            label = stringResource(R.string.framekit_brush_width),
            value = widthToDisplay(brush.widthShortEdgeRatio),
            valueRange = 1f..100f,
            onValueChange = { value -> onBrush { it.copy(widthShortEdgeRatio = displayToWidth(value)) } },
            onValueChangeFinished = {},
            formatValue = { it.roundToInt().toString() },
            resetValue = widthToDisplay(BrushSettings().widthShortEdgeRatio),
            modifier = Modifier.padding(top = 4.dp),
        )
        if (brush.kind != BrushKind.ERASER) {
            ValueSlider(
                label = stringResource(R.string.framekit_brush_opacity),
                value = (brush.opacity * 100).toFloat(),
                valueRange = 10f..100f,
                onValueChange = { value -> onBrush { it.copy(opacity = value / 100.0) } },
                onValueChangeFinished = {},
                formatValue = { "${it.roundToInt()}%" },
                resetValue = 100f,
            )
        }
    }
}

// 굵기는 짧은 변 대비 0.2%~5%를 1~100에 대응시킨다.
private fun widthToDisplay(ratio: Double): Float = (((ratio - MIN_WIDTH) / (MAX_WIDTH - MIN_WIDTH)) * 99 + 1).toFloat()

private fun displayToWidth(display: Float): Double = MIN_WIDTH + (display - 1) / 99.0 * (MAX_WIDTH - MIN_WIDTH)

private const val MIN_WIDTH = 0.002
private const val MAX_WIDTH = 0.05

private fun BrushKind.label(): Int = when (this) {
    BrushKind.PEN -> R.string.framekit_brush_pen
    BrushKind.MARKER -> R.string.framekit_brush_marker
    BrushKind.HIGHLIGHTER -> R.string.framekit_brush_highlighter
    BrushKind.ERASER -> R.string.framekit_brush_eraser
}
