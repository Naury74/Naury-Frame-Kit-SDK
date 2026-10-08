package com.naury.framekit.ui.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 가로로 스크롤되는 줄의 양 끝을 흐리게 해, 화면 밖에 항목이 더 있다는 것을 알려 준다.
 *
 * 스크롤 컨테이너보다 **바깥**(수정자 순서상 `horizontalScroll`보다 앞)에 붙여야 보이는 영역 기준으로 그려진다.
 * 더 갈 곳이 없는 쪽은 흐리지 않으며, 흐림은 짧게 나타나고 사라진다.
 *
 * @param state 이 줄의 스크롤 상태.
 * @param width 흐림 폭.
 */
@Composable
public fun Modifier.horizontalFadingEdges(state: ScrollState, width: Dp = DefaultFadeWidth): Modifier {
    val start by remember(state) { derivedStateOf { state.value > 0 } }
    val end by remember(state) { derivedStateOf { state.value < state.maxValue } }
    return fadingEdges(start, end, width)
}

/** [LazyListState]를 쓰는 가로 목록(`LazyRow`)용 [horizontalFadingEdges]. */
@Composable
public fun Modifier.horizontalFadingEdges(state: LazyListState, width: Dp = DefaultFadeWidth): Modifier {
    val start by remember(state) { derivedStateOf { state.canScrollBackward } }
    val end by remember(state) { derivedStateOf { state.canScrollForward } }
    return fadingEdges(start, end, width)
}

@Composable
private fun Modifier.fadingEdges(start: Boolean, end: Boolean, width: Dp): Modifier {
    val startAmount by animateFloatAsState(if (start) 1f else 0f, tween(FADE_MS), label = "fade-start")
    val endAmount by animateFloatAsState(if (end) 1f else 0f, tween(FADE_MS), label = "fade-end")
    return this
        // 배경과 상관없이 내용만 투명해지도록 별도 레이어에서 DstIn으로 가린다.
        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            val fade = width.toPx().coerceAtMost(size.width / 3f)
            if (startAmount > 0f) {
                drawRect(
                    Brush.horizontalGradient(0f to Color.Black.copy(alpha = 1f - startAmount), 1f to Color.Black, startX = 0f, endX = fade),
                    size = Size(fade, size.height),
                    blendMode = BlendMode.DstIn,
                )
            }
            if (endAmount > 0f) {
                drawRect(
                    Brush.horizontalGradient(0f to Color.Black, 1f to Color.Black.copy(alpha = 1f - endAmount), startX = size.width - fade, endX = size.width),
                    topLeft = Offset(size.width - fade, 0f),
                    size = Size(fade, size.height),
                    blendMode = BlendMode.DstIn,
                )
            }
        }
}

private val DefaultFadeWidth = 32.dp
private const val FADE_MS = 150
