package com.naury.framekit.ui.audio

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.naury.framekit.ui.R
import com.naury.framekit.ui.component.DialogAction
import com.naury.framekit.ui.component.DialogActionStyle
import com.naury.framekit.ui.component.FrameKitDialog
import com.naury.framekit.ui.design.FrameKitTheme
import kotlinx.coroutines.delay
import java.io.File
import java.util.Locale

/**
 * 음성 녹음 시트. 열리면 마이크 권한을 확인(없으면 요청)하고 바로 녹음을 시작한다. "완료"로 파일을 넘기고, "취소"나 시트 닫기는 녹음을 버린다.
 *
 * @param onRecorded 녹음을 마친 파일. 너무 짧으면 부르지 않는다.
 */
@Composable
public fun VoiceRecordDialog(onRecorded: (File) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val recorder = remember { VoiceRecorder(context.applicationContext) }
    var error by remember { mutableStateOf<Int?>(null) }
    var recording by remember { mutableStateOf(false) }
    var elapsed by remember { mutableLongStateOf(0L) }
    val levels = remember { mutableStateListOf<Float>() }

    fun begin() {
        try {
            recorder.start()
            recording = true
        } catch (_: Exception) {
            error = R.string.framekit_voice_failed
        }
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) begin() else error = R.string.framekit_voice_permission_denied
    }
    LaunchedEffect(Unit) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) begin()
        else permission.launch(Manifest.permission.RECORD_AUDIO)
    }
    LaunchedEffect(recording) {
        while (recording) {
            elapsed = recorder.elapsedMs
            levels.add(recorder.level())
            if (levels.size > BARS) levels.removeAt(0)
            if (elapsed >= MAX_MS) {
                recording = false
                recorder.stop()?.let(onRecorded)
                return@LaunchedEffect
            }
            delay(TICK_MS)
        }
    }
    // 시트가 사라지면(화면 회전, 뒤로 가기) 녹음을 버려 마이크를 놓는다.
    DisposableEffect(Unit) { onDispose { recorder.cancel() } }

    val colors = FrameKitTheme.colors
    val currentError = error
    FrameKitDialog(
        title = stringResource(R.string.framekit_voice_record),
        icon = ImageVector.vectorResource(R.drawable.framekit_ic_mic),
        iconTint = if (recording) colors.error else null,
        message = currentError?.let { stringResource(it) },
        onDismiss = {
            recorder.cancel()
            onDismiss()
        },
        actions = if (currentError != null) {
            listOf(DialogAction(stringResource(R.string.framekit_action_close), DialogActionStyle.SECONDARY, onClick = onDismiss))
        } else {
            listOf(
                DialogAction(stringResource(R.string.framekit_voice_stop), DialogActionStyle.PRIMARY) {
                    recording = false
                    val file = recorder.stop()
                    if (file != null) onRecorded(file) else error = R.string.framekit_voice_too_short
                },
                DialogAction(stringResource(R.string.framekit_action_cancel), DialogActionStyle.SECONDARY) {
                    recorder.cancel()
                    onDismiss()
                },
            )
        },
    ) {
        if (currentError == null) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }) {
                Text(
                    String.format(Locale.ROOT, "%d:%02d", elapsed / 60_000, elapsed / 1000 % 60),
                    color = colors.foreground,
                    style = MaterialTheme.typography.headlineMedium,
                )
                Text(
                    stringResource(if (recording) R.string.framekit_voice_recording else R.string.framekit_voice_waiting),
                    color = colors.foregroundMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(
                    Modifier.fillMaxWidth().height(48.dp).padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    levels.forEach { level ->
                        Box(Modifier.width(4.dp).height((4 + level * 32).dp).background(colors.accent, RoundedCornerShape(2.dp)))
                    }
                }
            }
        }
    }
}

private const val TICK_MS = 100L
private const val BARS = 40
private const val MAX_MS = 10 * 60_000L
