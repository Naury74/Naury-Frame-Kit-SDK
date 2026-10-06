package com.naury.framekit.ui.video.tool

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

/**
 * 배경 음악 한 곡의 화면 표시 값.
 *
 * @property startUs 출력 타임라인에서 음악이 시작하는 시간(µs).
 * @property offsetUs 곡 안에서 재생을 시작하는 위치(µs).
 */
internal data class MusicUi(
    val name: String?,
    val volume: Double,
    val loop: Boolean,
    val startUs: Long,
    val offsetUs: Long,
    val songDurationUs: Long,
)

/** 선택한 클립의 원본 소리(음소거·볼륨)와 배경 음악(추가·볼륨·반복·시작 위치·삭제). */
@Composable
internal fun AudioToolPanel(
    hasAudio: Boolean,
    muted: Boolean,
    volume: Double,
    onMuted: (Boolean) -> Unit,
    onVolume: (Float) -> Unit,
    onVolumeFinished: () -> Unit,
    modifier: Modifier = Modifier,
    music: MusicUi? = null,
    busy: Boolean = false,
    onAddMusic: (() -> Unit)? = null,
    onMusicVolume: (Float) -> Unit = {},
    onMusicLoop: (Boolean) -> Unit = {},
    onMusicStartHere: () -> Unit = {},
    onMusicOffset: (Long) -> Unit = {},
    onRemoveMusic: () -> Unit = {},
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
        if (onAddMusic == null) return@Column
        SectionTitle(stringResource(R.string.framekit_music))
        if (music == null) {
            TextButton(onClick = onAddMusic, enabled = !busy, modifier = Modifier.padding(horizontal = 8.dp)) {
                Icon(Icons.Filled.Add, contentDescription = null, tint = colors.accent)
                Text(stringResource(R.string.framekit_music_add), color = colors.accent, modifier = Modifier.padding(start = 6.dp))
            }
            return@Column
        }
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                music.name ?: stringResource(R.string.framekit_music_untitled),
                color = colors.foreground,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onAddMusic, enabled = !busy) { Text(stringResource(R.string.framekit_music_add), color = colors.accent) }
            IconButton(onClick = onRemoveMusic) {
                Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.framekit_music_remove), tint = colors.foregroundMuted)
            }
        }
        ValueSlider(
            label = stringResource(R.string.framekit_audio_volume),
            value = (music.volume * 100).toFloat(),
            valueRange = 0f..200f,
            onValueChange = onMusicVolume,
            onValueChangeFinished = onGestureFinished,
            formatValue = { "${it.roundToInt()}%" },
            resetValue = 100f,
            snapThreshold = 4f,
        )
        if (music.songDurationUs > MIN_SONG_US) {
            ValueSlider(
                label = stringResource(R.string.framekit_music_offset),
                value = (music.offsetUs / 1_000_000.0).toFloat(),
                valueRange = 0f..((music.songDurationUs - MIN_SONG_US) / 1_000_000f),
                onValueChange = { onMusicOffset((it * 1_000_000).toLong()) },
                onValueChangeFinished = onGestureFinished,
                formatValue = { formatTime((it * 1_000_000).toLong()) },
            )
        }
        SwitchRow(stringResource(R.string.framekit_music_loop), music.loop, onMusicLoop)
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(formatTime(music.startUs), color = colors.foregroundMuted, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 8.dp).weight(1f))
            TextButton(onClick = onMusicStartHere) { Text(stringResource(R.string.framekit_music_start_here), color = colors.accent) }
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

private const val MIN_SONG_US = 1_000_000L
