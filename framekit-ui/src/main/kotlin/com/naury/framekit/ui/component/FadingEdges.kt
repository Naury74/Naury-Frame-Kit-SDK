package com.naury.framekit.ui.component

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.naury.kinetica.ui.horizontalFadingEdges as kineticaHorizontalFadingEdges
import com.naury.kinetica.ui.verticalFadingEdges as kineticaVerticalFadingEdges

// 그리는 방식은 Kinetica와 같게 맞춘다. RTL에서 시작·끝을 바꾸고 동작 줄이기 설정을 따르는 처리를 한곳에서만 고치기 위해서다.

/**
 * 가로로 스크롤되는 줄의 양 끝을 흐리게 해, 화면 밖에 항목이 더 있다는 것을 알려 준다.
 *
 * 스크롤 컨테이너보다 **바깥**(수정자 순서상 `horizontalScroll`보다 앞)에 붙여야 보이는 영역 기준으로 그려진다.
 * 더 갈 곳이 없는 쪽은 흐리지 않으며, 흐림은 짧게 나타나고 사라진다. RTL에서는 시작과 끝이 바뀐다.
 *
 * @param state 이 줄의 스크롤 상태.
 * @param width 흐림 폭. 줄 폭의 1/3을 넘지 않는다.
 */
@Composable
public fun Modifier.horizontalFadingEdges(state: ScrollState, width: Dp = DefaultFadeWidth): Modifier =
    kineticaHorizontalFadingEdges(state, width)

/** [LazyListState]를 쓰는 가로 목록(`LazyRow`)용 [horizontalFadingEdges]. */
@Composable
public fun Modifier.horizontalFadingEdges(state: LazyListState, width: Dp = DefaultFadeWidth): Modifier =
    kineticaHorizontalFadingEdges(state, width)

/**
 * 세로로 스크롤되는 영역의 위·아래 끝을 흐리게 해, 가려진 내용이 더 있다는 것을 알려 준다.
 * [horizontalFadingEdges]처럼 `verticalScroll`보다 앞(바깥)에 붙인다.
 *
 * @param height 흐림 높이. 영역 높이의 1/3을 넘지 않는다.
 */
@Composable
public fun Modifier.verticalFadingEdges(state: ScrollState, height: Dp = DefaultFadeWidth): Modifier =
    kineticaVerticalFadingEdges(state, height)

/** 세로 스크롤과 위·아래 흐림을 함께 붙인다. 스크롤 상태가 따로 필요 없을 때 쓴다. */
@Composable
public fun Modifier.verticalFadingScroll(): Modifier {
    val state = rememberScrollState()
    return verticalFadingEdges(state).verticalScroll(state)
}

private val DefaultFadeWidth = 32.dp
