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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
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
@OptIn(ExperimentalMaterial3Api::class)
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
            // 값은 작은 배지로 보여 준다. 기준값에서 벗어나면 프라이머리 색으로 강조한다.
            Text(
                formatValue(value),
                color = if (value != resetValue) colors.accent else colors.foreground,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .background(colors.raised, CircleShape)
                    .padding(horizontal = 10.dp, vertical = 3.dp),
            )
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
                thumbColor = Color.White,
                activeTrackColor = colors.accent,
                inactiveTrackColor = colors.raised,
            ),
            // 얇은 트랙과 그림자가 있는 흰 원형 손잡이(사진 앱의 슬라이더 모양).
            thumb = {
                Box(
                    Modifier
                        .size(22.dp)
                        .shadow(3.dp, CircleShape)
                        .background(Color.White, CircleShape),
                )
            },
            track = { sliderState ->
                SliderDefaults.Track(
                    sliderState = sliderState,
                    modifier = Modifier.height(4.dp),
                    colors = SliderDefaults.colors(activeTrackColor = colors.accent, inactiveTrackColor = colors.raised),
                    thumbTrackGapSize = 0.dp,
                    drawStopIndicator = null,
                )
            },
            modifier = Modifier
                // 화면 가장자리에서 시작한 드래그가 시스템 뒤로 가기로 처리되지 않게 슬라이더 영역을 제외한다.
                .systemGestureExclusion()
                .semantics {
                    // 화면 읽기 프로그램이 어느 값을 조절하는지 알 수 있도록 라벨을 이름으로 붙인다.
                    contentDescription = label
                    stateDescription = formatValue(value)
                }
                .pointerInput(resetValue) {
                    detectTapGestures(onDoubleTap = {
                        onValueChange(resetValue)
                        onValueChangeFinished()
                    })
                },
        )
    }
}
