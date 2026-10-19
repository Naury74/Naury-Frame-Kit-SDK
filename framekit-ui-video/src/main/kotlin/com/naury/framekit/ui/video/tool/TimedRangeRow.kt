package com.naury.framekit.ui.video.tool

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.naury.framekit.core.video.TimeRangeUs
import com.naury.framekit.ui.design.FrameKitTheme
import com.naury.framekit.ui.video.R
import com.naury.framekit.ui.video.timeline.formatTime
import com.naury.kinetica.ui.KButton
import com.naury.kinetica.ui.KButtonSize
import com.naury.kinetica.ui.KIconButton
import com.naury.kinetica.ui.KVariant

/**
 * 고른 구간 항목(마스크·텍스트·스티커)의 표시 구간과 시작·끝 조정, 삭제 버튼. 고른 것이 없으면 [hint]를 보여 준다.
 */
@Composable
internal fun TimedRangeRow(
    range: TimeRangeUs?,
    hint: String,
    onStartHere: () -> Unit,
    onEndHere: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = FrameKitTheme.colors
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        if (range == null) {
            Text(hint, color = colors.foregroundMuted, style = MaterialTheme.typography.bodySmall)
            return@Column
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.framekit_mask_range, formatTime(range.startUs), formatTime(range.endExclusiveUs)),
                color = colors.foreground,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f),
            )
            KIconButton(
                Icons.Filled.Delete,
                stringResource(R.string.framekit_item_delete),
                onDelete,
                variant = KVariant.Text,
                size = 48.dp,
                contentColor = colors.foregroundMuted,
            )
        }
        Row {
            KButton(stringResource(R.string.framekit_mask_start_here), onClick = onStartHere, variant = KVariant.Text, size = KButtonSize.Small)
            KButton(stringResource(R.string.framekit_mask_end_here), onClick = onEndHere, variant = KVariant.Text, size = KButtonSize.Small)
        }
    }
}
