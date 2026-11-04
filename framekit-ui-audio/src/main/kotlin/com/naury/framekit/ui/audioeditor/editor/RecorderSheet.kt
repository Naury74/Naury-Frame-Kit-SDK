package com.naury.framekit.ui.audioeditor.editor

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.naury.framekit.ui.audioeditor.R
import com.naury.framekit.ui.component.DialogAction
import com.naury.framekit.ui.component.DialogActionStyle
import com.naury.framekit.ui.component.ExportErrorDialog
import com.naury.framekit.ui.component.FrameKitDialog
import com.naury.framekit.ui.design.FrameKitTheme
import com.naury.kinetica.ui.KSheet
import com.naury.framekit.ui.R as UiR

/**
 * 녹음만 할 때 호스트 화면 위에 띄우는 녹음 시트.
 *
 * 뒤로 가기·바깥 누름·끌어 내리기는 모두 [requestClose]로 간다. 녹음 중이거나 돌려주지 못한 녹음이 있으면 버릴지 묻고,
 * 버리면 녹음을 멈추고 지운 뒤 `Cancelled`로 끝낸다.
 *
 * @param closing 결과가 정해져 시트를 내리는 중.
 * @param onClosed 시트가 내려간 뒤.
 */
@Composable
internal fun RecorderSheet(viewModel: AudioEditorViewModel, closing: Boolean, onClosed: () -> Unit) {
    val colors = FrameKitTheme.colors
    var active by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var tooShort by remember { mutableStateOf(false) }
    // 바꾸면 녹음 패널을 새로 만들어, 진행 중인 녹음을 바로 멈추고 지운다.
    var panelKey by remember { mutableIntStateOf(0) }
    val failure by viewModel.recordingFailure.collectAsStateWithLifecycle()

    fun requestClose() {
        if (closing) return
        if (active || viewModel.hasPendingRecording()) confirmDiscard = true else viewModel.cancelRecording()
    }

    // 시트 창이 아직 없거나 내려가는 중일 때의 뒤로 가기.
    BackHandler { requestClose() }
    val title = stringResource(R.string.framekit_audio_new_recording)
    KSheet(visible = !closing, onDismissRequest = ::requestClose, title = title, onDismissed = onClosed) {
        // 글자를 크게 하거나 가로 화면이라 높이가 모자라면 스크롤한다.
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                title,
                color = colors.foreground,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.height(4.dp))
            key(panelKey) {
                RecorderPanel(
                    mode = viewModel.config.recordMode,
                    maxRecordingMs = viewModel.config.maxRecordingMs,
                    compact = true,
                    onRecorded = viewModel::onRecorded,
                    onTooShort = { tooShort = true },
                    onActiveChange = {
                        active = it
                        if (it) tooShort = false
                    },
                )
            }
            if (tooShort) {
                Text(
                    stringResource(R.string.framekit_audio_too_short),
                    color = colors.error,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
    if (confirmDiscard) {
        FrameKitDialog(
            title = stringResource(R.string.framekit_audio_discard_recording),
            onDismiss = { confirmDiscard = false },
            actions = listOf(
                DialogAction(stringResource(R.string.framekit_audio_keep_recording), DialogActionStyle.PRIMARY) { confirmDiscard = false },
                DialogAction(stringResource(UiR.string.framekit_action_discard), DialogActionStyle.DESTRUCTIVE) {
                    confirmDiscard = false
                    active = false
                    panelKey++
                    viewModel.cancelRecording()
                },
            ),
        )
    }
    failure?.let { code ->
        ExportErrorDialog(code = code, onRetry = viewModel::retryRecordingDelivery, onDismiss = viewModel::dismissRecordingFailure)
    }
}
