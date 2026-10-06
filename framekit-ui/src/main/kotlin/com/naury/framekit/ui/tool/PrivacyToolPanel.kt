package com.naury.framekit.ui.tool

import com.naury.framekit.core.overlay.PrivacyEffect
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.naury.framekit.ui.component.ChoiceChips
import com.naury.framekit.ui.component.ValueSlider
import com.naury.framekit.ui.design.FrameKitTheme
import com.naury.framekit.ui.R
import kotlin.math.roundToInt

/** Mosaic or blur, mask shape and strength. Masks are drawn with one finger on the canvas. */
@Composable
public fun PrivacyToolPanel(
    settings: PrivacySettings,
    onChange: ((PrivacySettings) -> PrivacySettings) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        ChoiceChips(
            options = listOf(true, false),
            selected = settings.mosaic,
            label = { stringResource(if (it) R.string.framekit_privacy_mosaic else R.string.framekit_privacy_blur) },
            onSelect = { mosaic -> onChange { it.copy(mosaic = mosaic) } },
        )
        ChoiceChips(
            options = PrivacyShape.entries,
            selected = settings.shape,
            label = {
                stringResource(
                    when (it) {
                        PrivacyShape.BRUSH -> R.string.framekit_privacy_brush
                        PrivacyShape.RECTANGLE -> R.string.framekit_privacy_rectangle
                        PrivacyShape.ELLIPSE -> R.string.framekit_privacy_ellipse
                    },
                )
            },
            onSelect = { shape -> onChange { it.copy(shape = shape) } },
            modifier = Modifier.padding(top = 8.dp),
        )
        ValueSlider(
            label = stringResource(R.string.framekit_privacy_strength),
            value = (settings.strength * 100).toFloat(),
            valueRange = 0f..100f,
            onValueChange = { value -> onChange { it.copy(strength = value / 100.0) } },
            onValueChangeFinished = {},
            formatValue = { it.roundToInt().toString() },
            resetValue = 40f,
            modifier = Modifier.padding(top = 4.dp),
        )
        if (settings.shape == PrivacyShape.BRUSH) {
            ValueSlider(
                label = stringResource(R.string.framekit_privacy_brush_size),
                value = (settings.brushWidthShortEdgeRatio * 1000).toFloat(),
                valueRange = 20f..150f,
                onValueChange = { value -> onChange { it.copy(brushWidthShortEdgeRatio = value / 1000.0) } },
                onValueChangeFinished = {},
                formatValue = { it.roundToInt().toString() },
                resetValue = 60f,
            )
        }
        if (!settings.mosaic) {
            Text(
                stringResource(R.string.framekit_privacy_help),
                color = FrameKitTheme.colors.foregroundMuted,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
    }
}

/** Mask kind for the privacy tool. */
public enum class PrivacyShape { BRUSH, RECTANGLE, ELLIPSE }

/**
 * Privacy tool settings.
 *
 * @property strength `0..1`, mapped to the mosaic block or blur radius.
 * @property brushWidthShortEdgeRatio brush diameter relative to the canvas short edge.
 */
public data class PrivacySettings(
    val mosaic: Boolean = true,
    val shape: PrivacyShape = PrivacyShape.BRUSH,
    val strength: Double = 0.4,
    val brushWidthShortEdgeRatio: Double = 0.06,
) {
    public fun effect(): PrivacyEffect = if (mosaic) {
        PrivacyEffect.Mosaic(MIN_BLOCK + strength * (MAX_BLOCK - MIN_BLOCK))
    } else {
        PrivacyEffect.Blur(MIN_BLUR + strength * (MAX_BLUR - MIN_BLUR))
    }

    private companion object {
        const val MIN_BLOCK = 0.01
        const val MAX_BLOCK = 0.08
        const val MIN_BLUR = 0.005
        const val MAX_BLUR = 0.05
    }
}
