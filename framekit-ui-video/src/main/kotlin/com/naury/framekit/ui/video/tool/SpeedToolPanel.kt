package com.naury.framekit.ui.video.tool

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.naury.framekit.core.video.VideoClip
import com.naury.framekit.ui.component.ChoiceChips
import com.naury.framekit.ui.design.FrameKitTheme
import com.naury.framekit.ui.video.R
import com.naury.framekit.ui.video.timeline.formatTime
import java.text.DecimalFormat
import kotlin.math.roundToLong

/**
 * 고정 재생 속도 프리셋. 오디오는 음높이를 유지한다.
 *
 * @param sourceDurationUs 선택한 클립의 원본 구간 길이(µs). 속도를 바꿨을 때 결과 길이를 미리 보여 준다.
 */
@Composable
internal fun SpeedToolPanel(speed: Double, sourceDurationUs: Long, onSelect: (Double) -> Unit, modifier: Modifier = Modifier) {
    val format = DecimalFormat("0.##")
    Column(modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(
            stringResource(R.string.framekit_speed_result, formatTime(sourceDurationUs), formatTime((sourceDurationUs / speed).roundToLong())),
            color = FrameKitTheme.colors.foregroundMuted,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
        )
        ChoiceChips(
            options = VideoClip.speedPresets,
            selected = VideoClip.speedPresets.firstOrNull { it == speed } ?: 1.0,
            label = { stringResource(R.string.framekit_speed_value, format.format(it)) },
            onSelect = onSelect,
        )
    }
}
