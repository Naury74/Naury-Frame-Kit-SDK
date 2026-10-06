package com.naury.framekit.ui.video.tool

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.naury.framekit.core.video.VideoClip
import com.naury.framekit.ui.component.ChoiceChips
import com.naury.framekit.ui.video.R
import java.text.DecimalFormat

/** Constant playback speed presets; audio keeps its pitch. */
@Composable
internal fun SpeedToolPanel(speed: Double, onSelect: (Double) -> Unit, modifier: Modifier = Modifier) {
    val format = DecimalFormat("0.##")
    Column(modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        ChoiceChips(
            options = VideoClip.speedPresets,
            selected = VideoClip.speedPresets.firstOrNull { it == speed } ?: 1.0,
            label = { stringResource(R.string.framekit_speed_value, format.format(it)) },
            onSelect = onSelect,
        )
    }
}
