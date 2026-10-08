package com.naury.framekit.ui.component

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.material3.ripple
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.animation.core.animateFloatAsState
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
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onCancel != null) {
            // 취소는 은은한 원형 버튼, 적용은 프라이머리 색 원형 버튼으로 무게를 나눈다.
            CircleButton(
                icon = Icons.Filled.Close,
                description = stringResource(R.string.framekit_action_cancel),
                background = colors.raised,
                tint = colors.foreground,
                onClick = onCancel,
            )
        }
        Text(
            title,
            color = colors.foreground,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f).padding(horizontal = if (onCancel != null) 12.dp else 4.dp),
        )
        if (onReset != null) {
            TextButton(onClick = onReset) { Text(stringResource(R.string.framekit_action_reset), color = colors.foregroundMuted) }
        }
        CircleButton(
            icon = Icons.Filled.Check,
            description = stringResource(if (onCancel != null) R.string.framekit_action_apply else R.string.framekit_action_done),
            background = colors.accent,
            tint = colors.onAccent,
            onClick = onApply,
        )
    }
}

@Composable
private fun CircleButton(icon: ImageVector, description: String, background: Color, tint: Color, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    // 누르는 동안 살짝 작아져 눌림이 손에 느껴지게 한다.
    val scale by animateFloatAsState(if (pressed) 0.9f else 1f, label = "circle-press")
    Box(
        Modifier
            .size(48.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(CircleShape)
            .background(background)
            .clickable(interactionSource = interaction, indication = ripple(), role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
    }
}
