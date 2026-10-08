package com.naury.framekit.ui.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.naury.framekit.ui.design.FrameKitTheme
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * 눈금자를 좌우로 끌어 값을 고르는 슬라이더(사진 앱의 보정 다이얼 방식).
 *
 * 가운데 기준선은 고정되고 눈금이 손가락을 따라 움직인다. [resetValue]부터 현재 값까지의 눈금은 프라이머리 색으로
 * 칠하고, 값은 위에 크게 보여 준다. [resetValue]와 양 끝을 지날 때 한 번씩 진동하며, 두 번 탭하면 [resetValue]로
 * 돌아간다. [onValueChange]는 움직일 때마다, [onValueChangeFinished]는 손을 뗄 때 한 번 호출된다.
 *
 * @param spacing 값 1만큼의 눈금 간격. 클수록 세밀하게 조절된다.
 * @param tickEvery 작은 눈금을 그리는 값 간격.
 * @param majorEvery 큰 눈금을 그리는 값 간격.
 * @param snapThreshold 값이 [resetValue]에 이만큼 가까워지면 [resetValue]로 붙는다.
 */
@Composable
public fun DialSlider(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    formatValue: (Float) -> String,
    modifier: Modifier = Modifier,
    resetValue: Float = 0f,
    spacing: Dp = 4.dp,
    tickEvery: Float = 2f,
    majorEvery: Float = 10f,
    snapThreshold: Float = 1.5f,
) {
    val colors = FrameKitTheme.colors
    val haptics = LocalHapticFeedback.current
    val hapticsEnabled = FrameKitTheme.config.enableHaptics
    val currentValue by rememberUpdatedState(value)
    val currentOnChange by rememberUpdatedState(onValueChange)
    val currentOnFinished by rememberUpdatedState(onValueChangeFinished)
    val changed = abs(value - resetValue) >= 0.5f

    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            formatValue(value),
            color = if (changed) colors.accent else colors.foreground,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(DIAL_HEIGHT)
                .padding(top = 4.dp)
                // 화면 가장자리에서 시작한 드래그가 시스템 뒤로 가기로 처리되지 않게 한다.
                .systemGestureExclusion()
                .semantics {
                    contentDescription = label
                    stateDescription = formatValue(value)
                    progressBarRangeInfo = ProgressBarRangeInfo(value, valueRange)
                    setProgress { target ->
                        currentOnChange(target.coerceIn(valueRange.start, valueRange.endInclusive))
                        currentOnFinished()
                        true
                    }
                }
                .pointerInput(valueRange, resetValue) {
                    detectTapGestures(onDoubleTap = {
                        currentOnChange(resetValue)
                        currentOnFinished()
                    })
                }
                .pointerInput(valueRange, resetValue, spacing) {
                    val pxPerUnit = spacing.toPx()
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        var raw = currentValue
                        var last = currentValue
                        var dragging = false
                        var travelled = 0f
                        do {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            val dx = change.positionChange().x
                            travelled += abs(dx)
                            if (!dragging && travelled > viewConfiguration.touchSlop) dragging = true
                            if (dragging && dx != 0f) {
                                // 눈금을 손가락 방향으로 끌면 값은 반대로 움직인다(눈금자를 미는 느낌).
                                raw = (raw - dx / pxPerUnit).coerceIn(valueRange.start, valueRange.endInclusive)
                                val next = if (abs(raw - resetValue) <= snapThreshold) resetValue else raw
                                val crossedReset = (last - resetValue) * (next - resetValue) < 0f || (next == resetValue && last != resetValue)
                                val hitEdge = (next == valueRange.start || next == valueRange.endInclusive) && next != last
                                if (hapticsEnabled && (crossedReset || hitEdge)) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                if (next != last) {
                                    last = next
                                    currentOnChange(next)
                                }
                                change.consume()
                            }
                        } while (change.pressed)
                        if (dragging) currentOnFinished()
                    }
                },
        ) {
            val pxPerUnit = spacing.toPx()
            val center = size.width / 2f
            val mid = size.height / 2f
            fun x(v: Float) = center + (v - value) * pxPerUnit
            val first = ceil(maxOf(valueRange.start, value - center / pxPerUnit) / tickEvery) * tickEvery
            val last = floor(minOf(valueRange.endInclusive, value + center / pxPerUnit) / tickEvery) * tickEvery
            val low = minOf(resetValue, value)
            val high = maxOf(resetValue, value)
            val minor = 1.dp.toPx()
            var v = first
            while (v <= last + 0.001f) {
                val major = abs(v / majorEvery - (v / majorEvery).roundToInt()) < 0.001f
                val active = changed && v >= low - 0.001f && v <= high + 0.001f
                val length = if (major) size.height * 0.55f else size.height * 0.3f
                // 가장자리로 갈수록 흐리게 해 다이얼이 휘어 보이게 한다.
                val fade = (1f - abs(x(v) - center) / center).coerceIn(0f, 1f)
                val base = if (active) colors.accent else colors.foregroundMuted
                drawLine(
                    base.copy(alpha = (if (major) 0.9f else 0.55f) * (0.25f + 0.75f * fade)),
                    Offset(x(v), mid - length / 2f),
                    Offset(x(v), mid + length / 2f),
                    if (major) minor * 1.5f else minor,
                    cap = StrokeCap.Round,
                )
                v += tickEvery
            }
            // 기준값 위치 표시(기준값이 범위 안에 있을 때).
            if (resetValue > valueRange.start && resetValue < valueRange.endInclusive) {
                drawCircle(if (changed) colors.accent else colors.foregroundMuted, 2.5.dp.toPx(), Offset(x(resetValue), 2.dp.toPx()))
            }
            // 고정된 가운데 기준선.
            drawLine(
                Brush.verticalGradient(listOf(colors.foreground, colors.foreground.copy(alpha = 0.6f))),
                Offset(center, 0f),
                Offset(center, size.height),
                2.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }
    }
}

private val DIAL_HEIGHT = 40.dp
