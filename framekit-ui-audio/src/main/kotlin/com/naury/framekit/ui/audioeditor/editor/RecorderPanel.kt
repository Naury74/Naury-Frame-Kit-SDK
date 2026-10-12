package com.naury.framekit.ui.audioeditor.editor

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.naury.framekit.ui.audio.VoiceRecorder
import com.naury.framekit.ui.audioeditor.R
import com.naury.framekit.ui.audioeditor.contract.RecordMode
import com.naury.framekit.ui.component.ChoiceChips
import com.naury.framekit.ui.design.FrameKitTheme
import kotlinx.coroutines.delay
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt
import com.naury.framekit.ui.R as UiR

/** 녹음기 상태. */
private enum class RecState { IDLE, RECORDING, PAUSED, HOLDING }

/**
 * 녹음 화면. 큰 시간 표시, 소리에 맞춰 흐르는 파형, 녹음 버튼으로 이뤄진다.
 *
 * - 탭: 눌러서 시작, 녹음 중에는 버리기·잠시 멈춤/이어 녹음·완료.
 * - 누르고 말하기: 누르는 동안만 녹음하고 떼면 끝난다. 위로 밀면 손을 떼도 계속 녹음하고(고정), 왼쪽으로 밀면 취소한다.
 *
 * @param compact 시트 안에 넣을 때 `true`(조금 작게).
 * @param onRecorded 녹음을 마친 파일.
 * @param onTooShort 너무 짧아 버렸을 때.
 */
@Composable
internal fun RecorderPanel(
    mode: RecordMode,
    maxRecordingMs: Long,
    compact: Boolean,
    onRecorded: (File) -> Unit,
    onTooShort: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val colors = FrameKitTheme.colors
    val haptics = LocalHapticFeedback.current
    val hapticsOn = FrameKitTheme.config.enableHaptics
    val recorder = remember { VoiceRecorder(context.applicationContext) }
    var state by remember { mutableStateOf(RecState.IDLE) }
    var activeMode by remember { mutableStateOf(if (mode == RecordMode.HOLD) RecordMode.HOLD else RecordMode.TAP) }
    var elapsed by remember { mutableLongStateOf(0L) }
    var error by remember { mutableStateOf<Int?>(null) }
    val levels = remember { mutableStateListOf<Float>() }
    val recorded by rememberUpdatedState(onRecorded)
    val tooShort by rememberUpdatedState(onTooShort)

    fun hasPermission() = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        error = if (granted) null else R.string.framekit_audio_permission
    }

    fun start(next: RecState): Boolean {
        if (!hasPermission()) {
            permission.launch(Manifest.permission.RECORD_AUDIO)
            return false
        }
        return try {
            recorder.start()
            levels.clear()
            error = null
            state = next
            if (hapticsOn) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            true
        } catch (_: Exception) {
            error = R.string.framekit_audio_mic_failed
            false
        }
    }

    fun finish() {
        val file = recorder.stop()
        state = RecState.IDLE
        elapsed = 0
        if (file != null) recorded(file) else tooShort()
    }

    fun discard() {
        recorder.cancel()
        state = RecState.IDLE
        elapsed = 0
        levels.clear()
    }

    LaunchedEffect(state) {
        while (state == RecState.RECORDING || state == RecState.HOLDING || state == RecState.PAUSED) {
            elapsed = recorder.elapsedMs
            if (state != RecState.PAUSED) {
                levels.add(recorder.level())
                if (levels.size > MAX_BARS) levels.removeAt(0)
            }
            if (elapsed >= maxRecordingMs) {
                finish()
                break
            }
            delay(TICK_MS)
        }
    }
    // 화면이 사라지면(뒤로 가기, 시트 닫기) 녹음을 버려 마이크를 놓는다.
    DisposableEffect(Unit) { onDispose { recorder.cancel() } }

    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            formatClock(elapsed),
            color = if (state == RecState.IDLE) colors.foregroundMuted else colors.foreground,
            style = if (compact) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.displayMedium,
            fontWeight = FontWeight.Light,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        Text(
            stringResource(
                when {
                    error != null -> error!!
                    state == RecState.PAUSED -> R.string.framekit_audio_paused
                    state == RecState.RECORDING || state == RecState.HOLDING -> R.string.framekit_audio_recording
                    activeMode == RecordMode.HOLD -> R.string.framekit_audio_hold_hint
                    else -> R.string.framekit_audio_tap_hint
                },
            ),
            color = if (error != null) colors.error else colors.foregroundMuted,
            style = MaterialTheme.typography.bodySmall,
        )
        LiveWave(levels, recording = state == RecState.RECORDING || state == RecState.HOLDING, modifier = Modifier.fillMaxWidth().height(if (compact) 64.dp else 120.dp).padding(vertical = 12.dp))
        if (mode == RecordMode.BOTH && state == RecState.IDLE) {
            ChoiceChips(
                options = listOf(RecordMode.TAP, RecordMode.HOLD),
                selected = activeMode,
                label = { stringResource(if (it == RecordMode.TAP) R.string.framekit_audio_mode_tap else R.string.framekit_audio_mode_hold) },
                onSelect = { activeMode = it },
            )
            Spacer(Modifier.height(8.dp))
        }
        Box(Modifier.fillMaxWidth().height(if (compact) 128.dp else 168.dp), contentAlignment = Alignment.Center) {
            when {
                state == RecState.RECORDING || state == RecState.PAUSED -> Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SideButton(rememberVectorPainter(Icons.Filled.Delete), stringResource(R.string.framekit_audio_discard), colors.foregroundMuted) { discard() }
                    BigButton(
                        icon = painterResource(if (state == RecState.PAUSED) UiR.drawable.framekit_ic_mic else UiR.drawable.framekit_ic_pause),
                        description = stringResource(if (state == RecState.PAUSED) R.string.framekit_audio_resume else R.string.framekit_audio_pause),
                        color = RECORD_RED,
                        level = if (state == RecState.RECORDING) levels.lastOrNull() ?: 0f else 0f,
                    ) {
                        if (state == RecState.PAUSED) {
                            recorder.resume()
                            state = RecState.RECORDING
                        } else {
                            recorder.pause()
                            state = RecState.PAUSED
                        }
                    }
                    SideButton(rememberVectorPainter(Icons.Filled.Check), stringResource(R.string.framekit_audio_done), colors.accent) { finish() }
                }
                activeMode == RecordMode.HOLD -> HoldButton(
                    holding = state == RecState.HOLDING,
                    level = levels.lastOrNull() ?: 0f,
                    onPress = { start(RecState.HOLDING) },
                    onRelease = { finish() },
                    onLock = {
                        state = RecState.RECORDING
                        if (hapticsOn) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    },
                    onCancel = {
                        discard()
                        if (hapticsOn) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    },
                )
                else -> BigButton(painterResource(UiR.drawable.framekit_ic_mic), stringResource(R.string.framekit_audio_record), RECORD_RED, 0f) { start(RecState.RECORDING) }
            }
        }
    }
}

/** 소리 크기에 맞춰 오른쪽에서 흘러 들어오는 막대 파형. */
@Composable
private fun LiveWave(levels: List<Float>, recording: Boolean, modifier: Modifier) {
    val accent = if (recording) RECORD_RED else FrameKitTheme.colors.foregroundMuted.copy(alpha = 0.4f)
    Canvas(modifier) {
        val bar = 3.dp.toPx()
        val gap = 2.5.dp.toPx()
        val step = bar + gap
        val count = (size.width / step).toInt()
        val middle = size.height / 2
        for (i in 0 until count) {
            // 오른쪽 끝이 가장 최근 소리다.
            val level = levels.getOrNull(levels.size - count + i) ?: 0f
            val half = (size.height / 2 * (0.06f + 0.94f * level)).coerceAtLeast(1.dp.toPx())
            val x = i * step
            drawRoundRect(accent.copy(alpha = if (level > 0f) 1f else 0.35f), Offset(x, middle - half), Size(bar, half * 2), CornerRadius(bar / 2))
        }
    }
}

/** 소리 크기만큼 둘레가 퍼지는 큰 원형 버튼. */
@Composable
private fun BigButton(icon: Painter, description: String, color: Color, level: Float, onClick: () -> Unit) {
    val ring by animateFloatAsState(1f + level * 0.35f, tween(90), label = "ring")
    Box(Modifier.size(112.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(84.dp).scale(ring).background(color.copy(alpha = 0.18f), CircleShape))
        Box(
            Modifier
                .size(76.dp)
                .background(color, CircleShape)
                .semantics {
                    contentDescription = description
                    role = Role.Button
                }
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(32.dp)) }
    }
}

@Composable
private fun SideButton(icon: Painter, label: String, tint: Color, onClick: () -> Unit) {
    Column(
        Modifier.semantics { role = Role.Button }.clickable(onClick = onClick).padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(52.dp).background(FrameKitTheme.colors.raised, CircleShape), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
        }
        Text(label, color = FrameKitTheme.colors.foregroundMuted, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp))
    }
}

/**
 * 누르고 말하기 버튼. 누르면 [onPress], 떼면 [onRelease]. 위로 밀면 [onLock], 왼쪽으로 밀면 [onCancel]하고 손을 떼도 녹음 결과를 내지 않는다.
 */
@Composable
private fun HoldButton(holding: Boolean, level: Float, onPress: () -> Boolean, onRelease: () -> Unit, onLock: () -> Unit, onCancel: () -> Unit) {
    val colors = FrameKitTheme.colors
    val density = LocalDensity.current
    val lockPx = with(density) { 72.dp.toPx() }
    val cancelPx = with(density) { 96.dp.toPx() }
    var dragX by remember { mutableFloatStateOf(0f) }
    var dragY by remember { mutableFloatStateOf(0f) }
    val press by rememberUpdatedState(onPress)
    val release by rememberUpdatedState(onRelease)
    val lock by rememberUpdatedState(onLock)
    val cancel by rememberUpdatedState(onCancel)
    val scale by animateFloatAsState(if (holding) 1.18f else 1f, tween(140), label = "hold-scale")
    val ring by animateFloatAsState(1f + level * 0.5f, tween(90), label = "hold-ring")
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        if (holding) {
            // 손을 떼지 않고 밀 방향을 알려 준다.
            Column(Modifier.align(Alignment.TopCenter).offset { IntOffset(0, (-8).dp.roundToPx()) }, horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Filled.Lock, contentDescription = null, tint = if (dragY < -lockPx / 2) colors.accent else colors.foregroundMuted, modifier = Modifier.size(18.dp))
                Icon(Icons.Filled.KeyboardArrowUp, contentDescription = null, tint = colors.foregroundMuted, modifier = Modifier.size(18.dp))
            }
            Row(Modifier.align(Alignment.CenterStart).padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = null, tint = if (dragX < -cancelPx / 2) colors.error else colors.foregroundMuted)
                Text(stringResource(R.string.framekit_audio_slide_cancel), color = if (dragX < -cancelPx / 2) colors.error else colors.foregroundMuted, style = MaterialTheme.typography.labelSmall)
            }
        }
        Box(
            Modifier
                .size(120.dp)
                .offset { IntOffset((dragX.coerceIn(-cancelPx, 0f)).roundToInt(), (dragY.coerceIn(-lockPx, 0f)).roundToInt()) }
                .semantics {
                    role = Role.Button
                }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown()
                        if (!press()) return@awaitEachGesture
                        dragX = 0f
                        dragY = 0f
                        var outcome = 0 // 0: 떼서 끝내기, 1: 고정, 2: 취소
                        do {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull() ?: break
                            dragX += change.position.x - change.previousPosition.x
                            dragY += change.position.y - change.previousPosition.y
                            change.consume()
                            if (dragY < -lockPx) outcome = 1
                            if (dragX < -cancelPx) outcome = 2
                        } while (outcome == 0 && event.changes.any { it.pressed })
                        dragX = 0f
                        dragY = 0f
                        when (outcome) {
                            1 -> lock()
                            2 -> cancel()
                            else -> release()
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(92.dp).scale(scale * if (holding) ring else 1f).background(RECORD_RED.copy(alpha = 0.18f), CircleShape))
            Box(Modifier.size(80.dp).scale(scale).background(RECORD_RED, CircleShape), contentAlignment = Alignment.Center) {
                Icon(painterResource(UiR.drawable.framekit_ic_mic), contentDescription = stringResource(R.string.framekit_audio_mode_hold), tint = Color.White, modifier = Modifier.size(34.dp))
            }
        }
    }
}

/** `m:ss.d`. */
internal fun formatClock(ms: Long): String {
    val tenths = ms.coerceAtLeast(0) / 100
    return String.format(Locale.ROOT, "%d:%02d.%d", tenths / 600, tenths / 10 % 60, tenths % 10)
}

private val RECORD_RED = Color(0xFFFF4D5E)
private const val TICK_MS = 50L
private const val MAX_BARS = 240
