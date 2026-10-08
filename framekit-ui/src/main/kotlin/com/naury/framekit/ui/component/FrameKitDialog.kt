package com.naury.framekit.ui.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.naury.framekit.ui.design.FrameKitTheme

/** [FrameKitDialog] 버튼의 강조 단계. */
public enum class DialogActionStyle {
    /** 권장 동작. 프라이머리 색으로 채운다. */
    PRIMARY,

    /** 되돌리기 어려운 동작(버리기·삭제). 오류 색으로 표시한다. */
    DESTRUCTIVE,

    /** 그 밖의 선택(계속 편집·취소). 글자 버튼이다. */
    SECONDARY,
}

/** [FrameKitDialog]의 버튼 하나. 목록 순서대로 위에서 아래로 쌓인다. */
public data class DialogAction(
    val label: String,
    val style: DialogActionStyle,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
)

/**
 * FrameKit 편집기의 대화상자. 테마 색·모서리를 따르고, 아이콘·제목·설명·버튼을 가운데 정렬해 보여 준다.
 * 버튼은 화면 폭이 좁아도 잘리지 않도록 세로로 쌓으며, 열릴 때 살짝 커지며 나타난다.
 *
 * @param icon 위쪽 원 안의 아이콘. `null`이면 아이콘 없이 제목부터 보인다.
 * @param iconTint 아이콘과 원 배경의 색. `null`이면 프라이머리 색.
 * @param message 제목 아래 설명. `null`이면 생략한다.
 * @param onDismiss 바깥을 누르거나 뒤로 가기를 눌렀을 때.
 * @param content 설명과 버튼 사이에 넣을 내용(입력 칸 등).
 */
@Composable
public fun FrameKitDialog(
    title: String,
    actions: List<DialogAction>,
    onDismiss: () -> Unit,
    icon: ImageVector? = null,
    iconTint: Color? = null,
    message: String? = null,
    content: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val colors = FrameKitTheme.colors
    val tint = iconTint ?: colors.accent
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, tween(APPEAR_MS, easing = FastOutSlowInEasing)) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .padding(horizontal = 24.dp)
                .widthIn(max = 400.dp)
                .fillMaxWidth()
                .graphicsLayer {
                    val scale = 0.92f + 0.08f * appear.value
                    scaleX = scale
                    scaleY = scale
                    alpha = appear.value
                }
                .background(colors.surface, MaterialTheme.shapes.large)
                .padding(horizontal = 24.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (icon != null) {
                Box(Modifier.size(56.dp).background(tint.copy(alpha = 0.16f), CircleShape), contentAlignment = Alignment.Center) {
                    Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(28.dp))
                }
                Spacer(Modifier.height(16.dp))
            }
            Text(
                title,
                color = colors.foreground,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { heading() },
            )
            if (message != null) {
                Spacer(Modifier.height(8.dp))
                Text(message, color = colors.foregroundMuted, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
            }
            if (content != null) {
                Spacer(Modifier.height(16.dp))
                content()
            }
            Spacer(Modifier.height(24.dp))
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                actions.forEach { action -> ActionButton(action) }
            }
        }
    }
}

@Composable
private fun ActionButton(action: DialogAction) {
    val colors = FrameKitTheme.colors
    val modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
    when (action.style) {
        DialogActionStyle.PRIMARY -> Button(
            onClick = action.onClick,
            enabled = action.enabled,
            modifier = modifier,
            shape = MaterialTheme.shapes.medium,
            colors = ButtonDefaults.buttonColors(
                containerColor = colors.accent,
                contentColor = colors.onAccent,
                disabledContainerColor = colors.raised,
                disabledContentColor = colors.foregroundMuted,
            ),
        ) { Text(action.label, fontWeight = FontWeight.SemiBold) }
        DialogActionStyle.DESTRUCTIVE -> Button(
            onClick = action.onClick,
            enabled = action.enabled,
            modifier = modifier,
            shape = MaterialTheme.shapes.medium,
            colors = ButtonDefaults.buttonColors(containerColor = colors.error.copy(alpha = 0.14f), contentColor = colors.error),
        ) { Text(action.label, fontWeight = FontWeight.SemiBold) }
        DialogActionStyle.SECONDARY -> TextButton(
            onClick = action.onClick,
            enabled = action.enabled,
            modifier = modifier,
            shape = MaterialTheme.shapes.medium,
        ) { Text(action.label, color = colors.foregroundMuted) }
    }
}

private const val APPEAR_MS = 180
