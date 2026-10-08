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
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Job
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.AnimationState
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
 * 칠하고, 값은 위에 크게 보여 준다. 세게 밀고 놓으면 그 속도로 이어서 미끄러지다 서서히 멈추며(다시 잡으면 멈춤),
 * [onValueChangeFinished]는 미끄러짐이 끝난 뒤 한 번 호출된다. 눈금을 지날 때마다 다이얼 톱니처럼 짧게 진동하고(큰 눈금은 조금 더 또렷하게),
 * [resetValue]와 양 끝에서는 분명하게 진동한다. 두 번 탭하면 [resetValue]로
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
    // 진행 중인 관성 애니메이션. 다시 잡으면 취소한다.
    var fling by remember { mutableStateOf<Job?>(null) }
    val ticker = remember(valueRange, tickEvery, majorEvery, resetValue, hapticsEnabled) {
        DialTicker(valueRange, tickEvery, majorEvery, resetValue) { strong ->
            if (hapticsEnabled) haptics.performHapticFeedback(if (strong) HapticFeedbackType.SegmentTick else HapticFeedbackType.SegmentFrequentTick)
        }
    }

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
                    coroutineScope {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            // 미끄러지는 중에 다시 잡으면 그 자리에서 멈추고, 손을 뗐을 때의 완료 처리를 한 번만 한다.
                            val interrupted = fling?.isActive == true
                            fling?.cancel()
                            fling = null
                            if (interrupted) currentOnFinished()
                            var raw = currentValue
                            var dragging = false
                            var travelled = 0f
                            val velocity = VelocityTracker()
                            velocity.addPointerInputChange(down)
                            ticker.reset(currentValue)
                            do {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                // 손 뗌 이벤트는 마지막 움직임보다 늦게 와 "멈춘 뒤 뗐다"로 읽힐 수 있어 속도 계산에서 뺀다.
                                if (change.pressed) velocity.addPointerInputChange(change)
                                val dx = change.positionChange().x
                                travelled += abs(dx)
                                if (!dragging && travelled > viewConfiguration.touchSlop) dragging = true
                                if (dragging && dx != 0f) {
                                    // 눈금을 손가락 방향으로 끌면 값은 반대로 움직인다(눈금자를 미는 느낌).
                                    raw = (raw - dx / pxPerUnit).coerceIn(valueRange.start, valueRange.endInclusive)
                                    val next = if (abs(raw - resetValue) <= snapThreshold) resetValue else raw
                                    ticker.step(next, change.uptimeMillis)
                                    if (next != currentValue) currentOnChange(next)
                                    change.consume()
                                }
                            } while (change.pressed)
                            if (!dragging) return@awaitEachGesture
                            // 손을 뗄 때의 속도만큼 이어서 미끄러지다 서서히 멈춘다(관성).
                            val unitsPerSecond = -velocity.calculateVelocity().x / pxPerUnit
                            if (abs(unitsPerSecond) < MIN_FLING_UNITS_PER_SECOND) {
                                currentOnFinished()
                                return@awaitEachGesture
                            }
                            fling = launch {
                                var position = raw
                                try {
                                    AnimationState(initialValue = raw, initialVelocity = unitsPerSecond).animateDecay(exponentialDecay(frictionMultiplier = FLING_FRICTION)) {
                                        // 다이얼의 매개변수 value와 이름이 겹쳐 애니메이션 값은 this로 분명히 가리킨다.
                                        position = this.value.coerceIn(valueRange.start, valueRange.endInclusive)
                                        ticker.step(position, System.currentTimeMillis())
                                        if (position != currentValue) currentOnChange(position)
                                        // 끝에 닿으면 더 갈 곳이 없으니 멈춘다.
                                        if (position == valueRange.start || position == valueRange.endInclusive) cancelAnimation()
                                    }
                                    // 기준값 근처에서 멈추면 기준값으로 붙인다.
                                    if (snapThreshold > 0f && abs(position - resetValue) <= snapThreshold && position != resetValue) {
                                        ticker.step(resetValue, System.currentTimeMillis())
                                        currentOnChange(resetValue)
                                    }
                                    currentOnFinished()
                                } finally {
                                    fling = null
                                }
                            }
                        }
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

/**
 * 값이 움직일 때 지난 눈금을 세어 톱니 햅틱을 낸다. 끌 때와 관성으로 미끄러질 때 함께 쓴다.
 * 작은 눈금은 약하게, 큰 눈금·기준값·끝은 강하게 울리며, 너무 촘촘하면 [MIN_TICK_INTERVAL_MS]만큼 거른다.
 */
private class DialTicker(
    private val range: ClosedFloatingPointRange<Float>,
    private val tickEvery: Float,
    private val majorEvery: Float,
    private val resetValue: Float,
    private val emit: (strong: Boolean) -> Unit,
) {
    private var last = 0f
    private var lastTick = 0
    private var lastAt = 0L

    fun reset(value: Float) {
        last = value
        lastTick = tickOf(value)
    }

    fun step(next: Float, now: Long) {
        if (next == last) return
        val crossedReset = (last - resetValue) * (next - resetValue) < 0f || (next == resetValue && last != resetValue)
        val hitEdge = next == range.start || next == range.endInclusive
        val tick = tickOf(next)
        when {
            crossedReset || hitEdge -> {
                emit(true)
                lastAt = now
            }
            tick != lastTick && now - lastAt >= MIN_TICK_INTERVAL_MS -> {
                val low = minOf(tick, lastTick) + 1
                val high = maxOf(tick, lastTick)
                val crossedMajor = (low..high).any { index ->
                    val v = range.start + index * tickEvery
                    abs(v / majorEvery - (v / majorEvery).roundToInt()) < 0.001f
                }
                emit(crossedMajor)
                lastAt = now
            }
        }
        last = next
        lastTick = tick
    }

    private fun tickOf(value: Float) = floor((value - range.start) / tickEvery).toInt()
}

private val DIAL_HEIGHT = 40.dp

// 이보다 느리게 놓으면 미끄러지지 않는다(값 단위/초).
private const val MIN_FLING_UNITS_PER_SECOND = 15f

// 클수록 빨리 멈춘다. 다이얼이 너무 멀리 가지 않도록 기본값(1)보다 크게 둔다.
private const val FLING_FRICTION = 2.2f

// 눈금 진동 사이 최소 간격(ms). 이보다 빠르면 진동이 이어져 한 덩어리로 느껴진다.
private const val MIN_TICK_INTERVAL_MS = 18L
