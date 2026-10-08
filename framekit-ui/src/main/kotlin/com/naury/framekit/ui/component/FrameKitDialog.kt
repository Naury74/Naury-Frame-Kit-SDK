package com.naury.framekit.ui.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.naury.framekit.ui.design.FrameKitTheme
import kotlinx.coroutines.launch

/** [FrameKitDialog] 버튼의 강조 단계. */
public enum class DialogActionStyle {
    /** 권장 동작. 프라이머리 색으로 채운다. */
    PRIMARY,

    /** 되돌리기 어려운 동작(버리기·삭제). 오류 색으로 표시한다. */
    DESTRUCTIVE,

    /** 그 밖의 선택(계속 편집·취소). 배경 없는 버튼이다. */
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
 * FrameKit 편집기의 대화상자.
 *
 * 폰 화면에서는 아래에서 올라오는 시트로 뜬다. 위쪽 손잡이를 아래로 끌거나 바깥을 누르면 내려가며 닫힌다.
 * 넓은 화면(가로 600dp 이상)에서는 가운데 카드로 뜬다. 아이콘·제목·설명·버튼은 가운데 정렬이며, 버튼은
 * 화면이 좁아도 잘리지 않도록 알약 모양으로 세로로 쌓는다.
 *
 * @param icon 위쪽 원 안의 아이콘. `null`이면 아이콘 없이 제목부터 보인다.
 * @param iconTint 아이콘과 원 배경의 색. `null`이면 프라이머리 색.
 * @param message 제목 아래 설명. `null`이면 생략한다.
 * @param onDismiss 바깥을 누르거나, 끌어 내리거나, 뒤로 가기를 눌렀을 때. 닫히는 애니메이션이 끝난 뒤 호출된다.
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
    val scope = rememberCoroutineScope()
    // 0: 닫힘, 1: 열림. 시트 위치·배경 어둡기·카드 크기가 모두 이 값을 따른다.
    val progress = remember { Animatable(0f) }
    // 손으로 끌어 내린 거리(px). 놓으면 일정 거리 이상이면 닫고, 아니면 제자리로 돌아간다.
    val drag = remember { Animatable(0f) }
    LaunchedEffect(Unit) { progress.animateTo(1f, spring(dampingRatio = 0.86f, stiffness = Spring.StiffnessMediumLow)) }
    val close: () -> Unit = {
        scope.launch {
            progress.animateTo(0f, tween(CLOSE_MS, easing = FastOutSlowInEasing))
            onDismiss()
        }
    }

    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        // 시스템 기본 어둡게 하기는 애니메이션이 없어 끄고, 직접 그린 배경을 함께 움직인다.
        val view = LocalView.current
        SideEffect { (view.parent as? DialogWindowProvider)?.window?.setDimAmount(0f) }
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val wide = maxWidth >= WIDE_BREAKPOINT
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = progress.value }
                    .background(Color.Black.copy(alpha = SCRIM_ALPHA))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = close),
            )
            val density = LocalDensity.current
            val body: @Composable ColumnScope.() -> Unit = {
                DialogBody(title, message, icon, iconTint ?: colors.accent, actions, content)
            }
            if (wide) {
                Column(
                    Modifier
                        .align(Alignment.Center)
                        .widthIn(max = 420.dp)
                        .padding(24.dp)
                        .graphicsLayer {
                            val scale = 0.94f + 0.06f * progress.value
                            scaleX = scale
                            scaleY = scale
                            alpha = progress.value
                        }
                        .shadow(24.dp, RoundedCornerShape(CARD_RADIUS))
                        .clip(RoundedCornerShape(CARD_RADIUS))
                        .background(colors.surface)
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                        .padding(horizontal = 24.dp, vertical = 28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    content = body,
                )
            } else {
                Column(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .graphicsLayer {
                            // 열릴 때는 아래에서 올라오고, 끄는 동안은 손가락을 따라 내려간다.
                            translationY = (1f - progress.value) * size.height + drag.value
                        }
                        .shadow(24.dp, RoundedCornerShape(topStart = SHEET_RADIUS, topEnd = SHEET_RADIUS))
                        .clip(RoundedCornerShape(topStart = SHEET_RADIUS, topEnd = SHEET_RADIUS))
                        .background(colors.surface)
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                        .draggable(
                            orientation = Orientation.Vertical,
                            state = rememberDraggableState { delta -> scope.launch { drag.snapTo((drag.value + delta).coerceAtLeast(0f)) } },
                            onDragStopped = { velocity ->
                                val threshold = with(density) { DISMISS_DRAG.toPx() }
                                if (drag.value > threshold || velocity > DISMISS_VELOCITY) {
                                    close()
                                } else {
                                    drag.animateTo(0f, spring(dampingRatio = 0.8f))
                                }
                            },
                        )
                        .windowInsetsPadding(WindowInsets.navigationBars)
                        .padding(start = 24.dp, end = 24.dp, top = 10.dp, bottom = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    // 끌어서 닫을 수 있다는 손잡이.
                    Box(Modifier.width(36.dp).height(4.dp).background(colors.foregroundMuted.copy(alpha = 0.4f), CircleShape))
                    Spacer(Modifier.height(20.dp))
                    body()
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.DialogBody(
    title: String,
    message: String?,
    icon: ImageVector?,
    tint: Color,
    actions: List<DialogAction>,
    content: (@Composable ColumnScope.() -> Unit)?,
) {
    val colors = FrameKitTheme.colors
    if (icon != null) {
        Box(Modifier.size(52.dp).background(tint.copy(alpha = 0.14f), CircleShape), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(26.dp))
        }
        Spacer(Modifier.height(14.dp))
    }
    Text(
        title,
        color = colors.foreground,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
        modifier = Modifier.semantics { heading() },
    )
    if (message != null) {
        Spacer(Modifier.height(6.dp))
        Text(message, color = colors.foregroundMuted, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
    }
    if (content != null) {
        Spacer(Modifier.height(18.dp))
        content()
    }
    Spacer(Modifier.height(24.dp))
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        actions.forEach { action -> ActionButton(action) }
    }
}

@Composable
private fun ActionButton(action: DialogAction) {
    val colors = FrameKitTheme.colors
    val (background, foreground) = when (action.style) {
        DialogActionStyle.PRIMARY -> colors.accent to colors.onAccent
        DialogActionStyle.DESTRUCTIVE -> colors.error.copy(alpha = 0.14f) to colors.error
        DialogActionStyle.SECONDARY -> Color.Transparent to colors.foregroundMuted
    }
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clip(CircleShape)
            .background(if (action.enabled) background else colors.raised)
            .clickable(enabled = action.enabled, role = Role.Button, onClick = action.onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            action.label,
            color = if (action.enabled) foreground else colors.foregroundMuted,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = if (action.style == DialogActionStyle.SECONDARY) FontWeight.Medium else FontWeight.SemiBold,
        )
    }
}

private val WIDE_BREAKPOINT = 600.dp
private val SHEET_RADIUS = 28.dp
private val CARD_RADIUS = 28.dp
private val DISMISS_DRAG = 96.dp
private const val DISMISS_VELOCITY = 1800f
private const val SCRIM_ALPHA = 0.5f
private const val CLOSE_MS = 180
