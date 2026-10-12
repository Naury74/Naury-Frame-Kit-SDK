package com.naury.framekit.ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.naury.framekit.ui.R
import com.naury.framekit.ui.design.FrameKitTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 가로로 스크롤되는 줄 위에 겹쳐, 화면 밖에 항목이 더 있으면 그쪽 끝에 둥근 화살표를 띄운다.
 *
 * - 화살표는 바깥쪽으로 살짝 움직여 눈에 띄고, 누르면 보이는 폭의 70%만큼 넘긴다.
 * - [peekOnce]가 `true`면 처음 나타날 때 줄을 조금 밀었다 되돌려 옆으로 넘길 수 있다는 것을 보여 준다(프로세스당 한 번).
 * - 화면 읽기 사용자는 줄 자체를 스크롤할 수 있으므로 화살표는 보조 버튼으로만 둔다.
 *
 * [horizontalFadingEdges]와 함께 쓰면 흐림 위에 화살표가 놓인다. 스크롤 줄과 같은 [Box] 안에서 호출한다.
 */
@Composable
public fun BoxScope.HorizontalScrollHints(state: ScrollState, peekOnce: Boolean = true) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val canBack by remember(state) { derivedStateOf { state.value > 0 } }
    val canForward by remember(state) { derivedStateOf { state.value < state.maxValue } }
    if (peekOnce) {
        LaunchedEffect(state.maxValue > 0) {
            if (state.maxValue <= 0 || peeked) return@LaunchedEffect
            peeked = true
            delay(PEEK_DELAY_MS)
            val distance = with(density) { PEEK_DISTANCE.toPx() }.coerceAtMost(state.maxValue.toFloat())
            state.animateScrollBy(distance, tween(PEEK_MS))
            state.animateScrollBy(-distance, tween(PEEK_MS))
        }
    }
    fun page(forward: Boolean) {
        scope.launch {
            val step = state.viewportSize * PAGE_FRACTION
            state.animateScrollBy(if (forward) step else -step, tween(PAGE_MS))
        }
    }
    // ScrollState와 Start/End 정렬, AutoMirrored 아이콘이 모두 레이아웃 방향을 따르므로 RTL에서도 그대로 맞는다.
    HintArrow(
        visible = canBack,
        atStart = true,
        label = stringResource(R.string.framekit_scroll_previous),
        modifier = Modifier.align(Alignment.CenterStart),
        onClick = { page(false) },
    )
    HintArrow(
        visible = canForward,
        atStart = false,
        label = stringResource(R.string.framekit_scroll_more),
        modifier = Modifier.align(Alignment.CenterEnd),
        onClick = { page(true) },
    )
}

@Composable
private fun HintArrow(visible: Boolean, atStart: Boolean, label: String, modifier: Modifier, onClick: () -> Unit) {
    val colors = FrameKitTheme.colors
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    AnimatedVisibility(
        visible = visible,
        modifier = modifier.padding(horizontal = 4.dp),
        enter = fadeIn(tween(180)) + scaleIn(tween(180), initialScale = 0.6f),
        exit = fadeOut(tween(140)) + scaleOut(tween(140), targetScale = 0.6f),
    ) {
        // 바깥쪽으로 2.5dp씩 천천히 오가며 "이쪽에 더 있다"를 알린다.
        val nudge by rememberInfiniteTransition(label = "scroll-hint").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(NUDGE_MS), RepeatMode.Reverse),
            label = "scroll-hint-nudge",
        )
        val shift = with(LocalDensity.current) { NUDGE_DISTANCE.toPx() } * nudge * if (atStart != rtl) -1 else 1
        Box(
            Modifier
                .graphicsLayer { translationX = shift }
                .size(ARROW_SIZE)
                .shadow(6.dp, CircleShape)
                .clip(CircleShape)
                .background(colors.raised)
                .border(1.dp, colors.foreground.copy(alpha = 0.12f), CircleShape)
                .clickable(role = Role.Button, onClickLabel = label, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (atStart) Icons.AutoMirrored.Filled.KeyboardArrowLeft else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = label,
                tint = colors.accent,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

// 처음 보여 준 적이 있는지. 화면마다 매번 움직이면 거슬리므로 프로세스당 한 번만 보여 준다.
private var peeked = false

private val ARROW_SIZE = 30.dp
private val NUDGE_DISTANCE = 2.5.dp
private val PEEK_DISTANCE = 56.dp
private const val NUDGE_MS = 700
private const val PEEK_MS = 380
private const val PEEK_DELAY_MS = 450L
private const val PAGE_MS = 320
private const val PAGE_FRACTION = 0.7f
