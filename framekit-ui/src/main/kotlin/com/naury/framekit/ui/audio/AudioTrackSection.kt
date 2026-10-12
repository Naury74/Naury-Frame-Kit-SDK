package com.naury.framekit.ui.audio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.naury.framekit.ui.R
import com.naury.framekit.ui.component.ValueSlider
import com.naury.framekit.ui.design.FrameKitTheme
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 오디오 트랙(배경 음악·음성) 하나의 화면 상태.
 *
 * @property volume `0..2`(1이 원래 크기).
 * @property startUs 결과 시간에서 트랙이 시작하는 위치(µs).
 * @property offsetUs 원본 소리의 어디부터 쓰는지(µs).
 * @property sourceDurationUs 원본 소리 길이(µs).
 */
public data class AudioTrackUi(
    val name: String?,
    val volume: Double,
    val loop: Boolean,
    val startUs: Long,
    val offsetUs: Long,
    val sourceDurationUs: Long,
)

/**
 * 오디오 트랙 한 칸. 비어 있으면 "파일 추가"(와 녹음할 수 있으면 "녹음") 버튼을, 있으면 이름·바꾸기·삭제와 볼륨·시작 위치·반복을 보여 준다.
 *
 * @param title 칸 제목(예: "배경 음악", "음성").
 * @param addLabel 파일 추가 버튼 글자.
 * @param onRecord 녹음 버튼. `null`이면 숨긴다(호스트가 녹음 권한을 선언하지 않은 경우 등).
 */
@Composable
public fun AudioTrackSection(
    title: String,
    addLabel: String,
    track: AudioTrackUi?,
    busy: Boolean,
    onAddFile: () -> Unit,
    onRemove: () -> Unit,
    onVolume: (Float) -> Unit,
    onLoop: (Boolean) -> Unit,
    onStartHere: () -> Unit,
    onOffset: (Long) -> Unit,
    onGestureFinished: () -> Unit,
    modifier: Modifier = Modifier,
    onRecord: (() -> Unit)? = null,
) {
    val colors = FrameKitTheme.colors
    Column(modifier.fillMaxWidth()) {
        Text(
            title,
            color = colors.foregroundMuted,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp),
        )
        if (track == null) {
            Row(Modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onAddFile, enabled = !busy) {
                    Icon(Icons.Filled.Add, contentDescription = null, tint = colors.accent)
                    Text(addLabel, color = colors.accent, modifier = Modifier.padding(start = 6.dp))
                }
                if (onRecord != null) {
                    TextButton(onClick = onRecord, enabled = !busy) {
                        Icon(painterResource(R.drawable.framekit_ic_mic), contentDescription = null, tint = colors.accent, modifier = Modifier.size(20.dp))
                        Text(stringResource(R.string.framekit_voice_record), color = colors.accent, modifier = Modifier.padding(start = 6.dp))
                    }
                }
            }
            return@Column
        }
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                track.name ?: stringResource(R.string.framekit_track_untitled),
                color = colors.foreground,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onAddFile, enabled = !busy) { Text(stringResource(R.string.framekit_track_replace), color = colors.accent) }
            if (onRecord != null) {
                IconButton(onClick = onRecord, enabled = !busy) {
                    Icon(painterResource(R.drawable.framekit_ic_mic), contentDescription = stringResource(R.string.framekit_voice_record), tint = colors.accent)
                }
            }
            IconButton(onClick = onRemove) {
                Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.framekit_track_remove), tint = colors.foregroundMuted)
            }
        }
        ValueSlider(
            label = stringResource(R.string.framekit_track_volume),
            value = (track.volume * 100).toFloat(),
            valueRange = 0f..200f,
            onValueChange = onVolume,
            onValueChangeFinished = onGestureFinished,
            formatValue = { "${it.roundToInt()}%" },
            resetValue = 100f,
            snapThreshold = 4f,
        )
        if (track.sourceDurationUs > MIN_SOURCE_US) {
            ValueSlider(
                label = stringResource(R.string.framekit_track_offset),
                value = (track.offsetUs / 1_000_000.0).toFloat(),
                valueRange = 0f..((track.sourceDurationUs - MIN_SOURCE_US) / 1_000_000f),
                onValueChange = { onOffset((it * 1_000_000).toLong()) },
                onValueChangeFinished = onGestureFinished,
                formatValue = { formatAudioTime((it * 1_000_000).toLong()) },
            )
        }
        Row(
            Modifier.fillMaxWidth().toggleable(value = track.loop, role = Role.Switch, onValueChange = onLoop).heightIn(min = 48.dp).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.framekit_track_loop), color = colors.foreground, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            Switch(checked = track.loop, onCheckedChange = null, colors = SwitchDefaults.colors(checkedTrackColor = colors.accent, checkedThumbColor = colors.onAccent))
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(formatAudioTime(track.startUs), color = colors.foregroundMuted, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 8.dp).weight(1f))
            TextButton(onClick = onStartHere) { Text(stringResource(R.string.framekit_track_start_here), color = colors.accent) }
        }
    }
}

/** `m:ss.d`. */
public fun formatAudioTime(us: Long): String {
    val tenths = (us.coerceAtLeast(0) + 50_000) / 100_000
    return String.format(Locale.ROOT, "%d:%02d.%d", tenths / 600, tenths / 10 % 60, tenths % 10)
}

private const val MIN_SOURCE_US = 1_000_000L
