package com.naury.framekit.ui.video.tool

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.naury.framekit.ui.component.ValueSlider
import com.naury.framekit.ui.design.FrameKitTheme
import com.naury.framekit.ui.video.R
import kotlin.math.roundToInt

/** Mute switch and volume of the original sound. */
@Composable
internal fun AudioToolPanel(
    hasAudio: Boolean,
    muted: Boolean,
    volume: Double,
    onMuted: (Boolean) -> Unit,
    onVolume: (Float) -> Unit,
    onVolumeFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = FrameKitTheme.colors
    Column(modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        if (!hasAudio) {
            Text(
                stringResource(R.string.framekit_audio_none),
                color = colors.foregroundMuted,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
            return@Column
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.framekit_audio_mute), color = colors.foreground, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            Switch(
                checked = muted,
                onCheckedChange = onMuted,
                colors = SwitchDefaults.colors(checkedTrackColor = colors.accent, checkedThumbColor = colors.onAccent),
            )
        }
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
}
