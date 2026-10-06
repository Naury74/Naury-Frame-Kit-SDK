package com.naury.framekit.ui.component

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.systemGestureExclusion
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
 * 숫자 라벨, 더블탭 초기화, [resetValue]에 스냅될 때 한 번의 햅틱을 갖춘 슬라이더.
 *
 * [onValueChange]는 움직일 때마다, [onValueChangeFinished]는 제스처가 끝날 때 한 번 호출되므로
 * 호출자는 드래그 한 번을 히스토리 한 단계로 대응시킬 수 있다.
 *
 * @param snapThreshold 값이 [resetValue]로 스냅되는 [resetValue]로부터의 거리.
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
                // 화면 가장자리에서 시작한 드래그가 시스템 뒤로 가기로 처리되지 않게 슬라이더 영역을 제외한다.
                .systemGestureExclusion()
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
