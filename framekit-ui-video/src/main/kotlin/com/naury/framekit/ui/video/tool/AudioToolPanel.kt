package com.naury.framekit.ui.video.tool

import com.naury.framekit.core.video.AudioRole
import com.naury.framekit.ui.audio.AudioTrackSection
import com.naury.framekit.ui.audio.AudioTrackUi
import com.naury.framekit.ui.R as UiR

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.naury.framekit.ui.component.ValueSlider
import com.naury.framekit.ui.design.FrameKitTheme
import com.naury.framekit.ui.video.R
import com.naury.framekit.ui.video.timeline.formatTime
import kotlin.math.roundToInt

/** 선택한 클립의 원본 소리(음소거·볼륨)와 배경 음악·음성 트랙(추가·녹음·볼륨·반복·시작 위치·삭제). */
@Composable
internal fun AudioToolPanel(
    hasAudio: Boolean,
    muted: Boolean,
    volume: Double,
    onMuted: (Boolean) -> Unit,
    onVolume: (Float) -> Unit,
    onVolumeFinished: () -> Unit,
    modifier: Modifier = Modifier,
    tracks: Map<AudioRole, AudioTrackUi> = emptyMap(),
    busy: Boolean = false,
    onAddFile: ((AudioRole) -> Unit)? = null,
    onRecordVoice: (() -> Unit)? = null,
    onTrackVolume: (AudioRole, Float) -> Unit = { _, _ -> },
    onTrackLoop: (AudioRole, Boolean) -> Unit = { _, _ -> },
    onTrackStartHere: (AudioRole) -> Unit = {},
    onTrackOffset: (AudioRole, Long) -> Unit = { _, _ -> },
    onRemoveTrack: (AudioRole) -> Unit = {},
    onGestureFinished: () -> Unit = {},
) {
    val colors = FrameKitTheme.colors
    Column(modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        SectionTitle(stringResource(R.string.framekit_original_sound))
        if (!hasAudio) {
            Text(
                stringResource(R.string.framekit_audio_none),
                color = colors.foregroundMuted,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        } else {
            SwitchRow(stringResource(R.string.framekit_audio_mute), muted, onMuted)
            ValueSlider(
                label = stringResource(R.string.framekit_audio_volume),
                value = if (muted) 0f else (volume * 100).toFloat(),
                valueRange = 0f..200f,
                onValueChange = onVolume,
                onValueChangeFinished = onVolumeFinished,
                formatValue = { "${it.roundToInt()}%" },
                resetValue = 100f,
                snapThreshold = 4f,
            )
        }
        if (onAddFile == null) return@Column
        Text(
            stringResource(R.string.framekit_sound_timeline_help),
            color = colors.foregroundMuted,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp),
        )
        AudioRole.entries.forEach { role ->
            AudioTrackSection(
                title = stringResource(if (role == AudioRole.MUSIC) UiR.string.framekit_track_music else UiR.string.framekit_track_voice),
                addLabel = stringResource(if (role == AudioRole.MUSIC) UiR.string.framekit_track_music_add else UiR.string.framekit_track_voice_add),
                track = tracks[role],
                busy = busy,
                onAddFile = { onAddFile(role) },
                onRemove = { onRemoveTrack(role) },
                onVolume = { onTrackVolume(role, it) },
                onLoop = { onTrackLoop(role, it) },
                onStartHere = { onTrackStartHere(role) },
                onOffset = { onTrackOffset(role, it) },
                onGestureFinished = onGestureFinished,
                onRecord = if (role == AudioRole.VOICE) onRecordVoice else null,
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        color = FrameKitTheme.colors.foregroundMuted,
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp),
    )
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val colors = FrameKitTheme.colors
    // 줄 전체를 하나의 스위치로 묶어 라벨을 눌러도 바뀌고 화면 읽기 프로그램도 라벨과 상태를 함께 읽는다.
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .heightIn(min = 48.dp)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = colors.foreground, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(checkedTrackColor = colors.accent, checkedThumbColor = colors.onAccent),
        )
    }
}

