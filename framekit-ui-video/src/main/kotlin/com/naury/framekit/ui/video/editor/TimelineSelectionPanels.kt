package com.naury.framekit.ui.video.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.naury.framekit.core.video.AudioRole
import com.naury.framekit.ui.component.ValueSlider
import com.naury.framekit.ui.design.FrameKitTheme
import com.naury.framekit.ui.video.R
import java.util.Locale
import kotlin.math.roundToInt
import com.naury.framekit.ui.R as UiR

/** 타임라인에서 소리 조각·원본 소리·끌 구간을 골랐으면 `true`. 이때는 도구 목록 대신 [TimelineSelectionPanel]을 보여 준다. */
internal val VideoEditorUiState.Ready.hasTimelineSelection: Boolean
    get() = selectedAudio != null || originalSelected || muteSelection != null

/** 타임라인에서 무언가를 골랐을 때 도구 목록 자리에 보여 주는 동작. */
@Composable
internal fun TimelineSelectionPanel(state: VideoEditorUiState.Ready, viewModel: VideoEditorViewModel) {
    when {
        state.muteSelection != null -> MuteSelectionBar(viewModel)
        state.selectedAudio != null -> AudioSelectionPanel(state, viewModel)
        state.originalSelected -> OriginalSelectionPanel(state, viewModel)
    }
}

/** 원본 소리에서 끌 구간을 고르는 중. */
@Composable
private fun MuteSelectionBar(viewModel: VideoEditorViewModel) {
    val colors = FrameKitTheme.colors
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(stringResource(R.string.framekit_mute_range_help), color = colors.foregroundMuted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            ActionButton(rememberVectorPainter(Icons.Filled.Close), stringResource(UiR.string.framekit_action_cancel), colors.foreground, viewModel::cancelMuteSelection)
            ActionButton(rememberVectorPainter(Icons.Filled.Check), stringResource(R.string.framekit_original_mute_apply), colors.error, viewModel::applyMuteSelection)
        }
    }
}

/** 고른 배경 음악·음성 조각: 나누기·반복·삭제와 볼륨·페이드. */
@Composable
private fun AudioSelectionPanel(state: VideoEditorUiState.Ready, viewModel: VideoEditorViewModel) {
    val colors = FrameKitTheme.colors
    val clip = state.selectedAudio ?: return
    val length = clip.outputRange(state.durationUs).durationUs
    Column(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(if (clip.role == AudioRole.MUSIC) UiR.string.framekit_track_music else UiR.string.framekit_track_voice),
                color = colors.foreground,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(start = 12.dp).weight(1f),
            )
            ActionButton(painterResource(UiR.drawable.framekit_ic_trim), stringResource(R.string.framekit_clip_split), colors.foreground, viewModel::splitSelectedAudio)
            ActionButton(rememberVectorPainter(Icons.Filled.Refresh), stringResource(UiR.string.framekit_track_loop), if (clip.loop) colors.accent else colors.foregroundMuted) {
                viewModel.setSelectedAudioLoop(!clip.loop)
            }
            ActionButton(rememberVectorPainter(Icons.Filled.Delete), stringResource(UiR.string.framekit_track_remove), colors.foreground, viewModel::deleteSelectedAudio)
            ActionButton(rememberVectorPainter(Icons.Filled.Close), stringResource(R.string.framekit_selection_done), colors.foregroundMuted, viewModel::clearTimelineSelection)
        }
        ValueSlider(
            label = stringResource(UiR.string.framekit_track_volume),
            value = (clip.volume * 100).toFloat(),
            valueRange = 0f..200f,
            onValueChange = viewModel::changeSelectedAudioVolume,
            onValueChangeFinished = viewModel::finishGesture,
            formatValue = { "${it.roundToInt()}%" },
            resetValue = 100f,
            snapThreshold = 4f,
        )
        val maxFade = (minOf(length / 2, MAX_FADE_US) / 1_000_000f).coerceAtLeast(0.1f)
        ValueSlider(
            label = stringResource(R.string.framekit_audio_fade_in),
            value = (clip.fadeInUs / 1_000_000f).coerceAtMost(maxFade),
            valueRange = 0f..maxFade,
            onValueChange = { viewModel.changeSelectedAudioFade((it * 1_000_000).toLong(), null) },
            onValueChangeFinished = viewModel::finishGesture,
            formatValue = { String.format(Locale.ROOT, "%.1fs", it) },
            resetValue = 0f,
        )
        ValueSlider(
            label = stringResource(R.string.framekit_audio_fade_out),
            value = (clip.fadeOutUs / 1_000_000f).coerceAtMost(maxFade),
            valueRange = 0f..maxFade,
            onValueChange = { viewModel.changeSelectedAudioFade(null, (it * 1_000_000).toLong()) },
            onValueChangeFinished = viewModel::finishGesture,
            formatValue = { String.format(Locale.ROOT, "%.1fs", it) },
            resetValue = 0f,
        )
    }
}

/** 고른 클립의 원본 소리: 구간 음소거·되살리기·전체 음소거와 볼륨. */
@Composable
private fun OriginalSelectionPanel(state: VideoEditorUiState.Ready, viewModel: VideoEditorViewModel) {
    val colors = FrameKitTheme.colors
    val playback by viewModel.playback.collectAsStateWithLifecycle()
    val clip = state.clip
    Column(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.framekit_original_sound), color = colors.foreground, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 12.dp).weight(1f))
            if (!clip.muted) {
                if (viewModel.isMutedAtPlayhead(state, playback.positionUs)) {
                    ActionButton(painterResource(UiR.drawable.framekit_ic_volume), stringResource(R.string.framekit_original_restore), colors.accent, viewModel::restoreMutedAtPlayhead)
                } else {
                    ActionButton(painterResource(UiR.drawable.framekit_ic_volume_off), stringResource(R.string.framekit_original_mute_range), colors.foreground, viewModel::beginMuteSelection)
                }
            }
            ActionButton(
                painterResource(if (clip.muted) UiR.drawable.framekit_ic_volume_off else UiR.drawable.framekit_ic_volume),
                stringResource(if (clip.muted) R.string.framekit_audio_unmute else R.string.framekit_audio_mute),
                if (clip.muted) colors.accent else colors.foreground,
            ) { viewModel.setMuted(!clip.muted) }
            ActionButton(rememberVectorPainter(Icons.Filled.Close), stringResource(R.string.framekit_selection_done), colors.foregroundMuted, viewModel::clearTimelineSelection)
        }
        if (!clip.muted) {
            ValueSlider(
                label = stringResource(R.string.framekit_audio_volume),
                value = (clip.volume * 100).toFloat(),
                valueRange = 0f..200f,
                onValueChange = viewModel::changeVolume,
                onValueChangeFinished = viewModel::finishGesture,
                formatValue = { "${it.roundToInt()}%" },
                resetValue = 100f,
                snapThreshold = 4f,
            )
        }
    }
}

@Composable
private fun ActionButton(icon: Painter, label: String, tint: Color, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
            Text(label, color = tint, style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }
    }
}

private const val MAX_FADE_US = 5_000_000L
