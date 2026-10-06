package com.naury.framekit.ui.component

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.naury.framekit.ui.R
import com.naury.framekit.ui.design.FrameKitTheme

/**
 * 열린 도구의 하단 바. 취소(초안 도구만), 제목, 선택적 초기화, 적용으로 구성된다.
 *
 * @param onCancel 변경이 이미 커밋되는 도구는 `null`. 이때 체크 버튼은 도구를 닫는다.
 */
@Composable
public fun ApplyCancelBar(
    title: String,
    onCancel: (() -> Unit)?,
    onApply: () -> Unit,
    modifier: Modifier = Modifier,
    onReset: (() -> Unit)? = null,
) {
    val colors = FrameKitTheme.colors
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onCancel != null) {
            IconButton(onClick = onCancel) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.framekit_action_cancel), tint = colors.foreground)
            }
        }
        Text(
            title,
            color = colors.foreground,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.weight(1f).padding(horizontal = if (onCancel != null) 8.dp else 16.dp),
        )
        if (onReset != null) {
            TextButton(onClick = onReset) { Text(stringResource(R.string.framekit_action_reset), color = colors.foregroundMuted) }
        }
        IconButton(onClick = onApply) {
            Icon(
                Icons.Filled.Check,
                contentDescription = stringResource(if (onCancel != null) R.string.framekit_action_apply else R.string.framekit_action_done),
                tint = colors.accent,
            )
        }
    }
}
