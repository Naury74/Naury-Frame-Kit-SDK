package com.naury.framekit.ui.component

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.naury.framekit.ui.design.FrameKitTheme
import kotlin.math.abs

/**
 * Slider with a numeric label, double-tap reset and a single haptic tick when it snaps to [resetValue].
 *
 * [onValueChange] is called for every movement and [onValueChangeFinished] once when the gesture ends,
 * so callers can map one drag to one history step.
 *
 * @param snapThreshold distance from [resetValue] within which the value snaps to it.
 */
@Composable
public fun ValueSlider(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    formatValue: (Float) -> String,
    modifier: Modifier = Modifier,
    resetValue: Float = 0f,
    snapThreshold: Float = 0f,
) {
    val colors = FrameKitTheme.colors
    val haptics = LocalHapticFeedback.current
    val hapticsEnabled = FrameKitTheme.config.enableHaptics
    var snapped by remember { mutableStateOf(value == resetValue) }

    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = colors.foregroundMuted, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            Text(formatValue(value), color = colors.foreground, style = MaterialTheme.typography.labelLarge)
        }
        Slider(
            value = value,
            valueRange = valueRange,
            onValueChange = { raw ->
                val next = if (snapThreshold > 0f && abs(raw - resetValue) <= snapThreshold) resetValue else raw
                val isSnapped = next == resetValue
                // 기준값 구간에 들어가는 순간 한 번만 진동하고, 머무는 동안은 반복하지 않는다.
                if (isSnapped && !snapped && hapticsEnabled) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                snapped = isSnapped
                onValueChange(next)
            },
            onValueChangeFinished = onValueChangeFinished,
            colors = SliderDefaults.colors(
                thumbColor = colors.foreground,
                activeTrackColor = colors.accent,
                inactiveTrackColor = colors.raised,
            ),
            modifier = Modifier
                .semantics { stateDescription = formatValue(value) }
                .pointerInput(resetValue) {
                    detectTapGestures(onDoubleTap = {
                        onValueChange(resetValue)
                        onValueChangeFinished()
                    })
                },
        )
    }
}
