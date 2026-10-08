package com.naury.framekit.ui.canvas

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.toSize

/**
 * 안의 내용을 [state]만큼 확대해 그리고, 확대·이동 제스처를 처리하는 캔버스 틀.
 *
 * 제스처는 확대되지 않은 바깥 좌표에서 받는다. 확대된 레이어 안에서 받으면 손가락 위치가 레이어와 함께 움직여
 * 이동이 떨리기 때문이다. 안쪽 [content]의 터치 좌표는 확대 전 좌표로 들어오므로 기존 편집 제스처는 그대로 동작한다.
 *
 * - 두 손가락: 손가락 중심을 기준으로 확대·이동한다.
 * - 한 손가락: [singleFingerPan]이고 확대되어 있을 때만, 손가락이 조금 움직이면 화면을 옮긴다.
 * - [isChildGestureActive]가 `true`인 동안(스티커를 잡고 있는 등)에는 끼어들지 않는다.
 *
 * @param enabled `false`면 제스처를 받지 않는다. 배율은 호출하는 쪽이 [CanvasZoomState.reset]으로 되돌린다.
 */
@Composable
public fun ZoomableCanvas(
    state: CanvasZoomState,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    singleFingerPan: Boolean = false,
    isChildGestureActive: () -> Boolean = { false },
    content: @Composable BoxScope.() -> Unit,
) {
    val currentEnabled by rememberUpdatedState(enabled)
    val currentSingleFinger by rememberUpdatedState(singleFingerPan)
    val currentChildActive by rememberUpdatedState(isChildGestureActive)
    Box(
        modifier
            .clipToBounds()
            .pointerInput(state) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    var active = false
                    var travelled = Offset.Zero
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (!currentEnabled) continue
                        val pressed = event.changes.count { it.pressed }
                        if (!active && !currentChildActive()) {
                            if (pressed >= 2) {
                                active = true
                            } else if (pressed == 1 && currentSingleFinger && state.isZoomed) {
                                travelled += event.calculatePan()
                                if (travelled.getDistance() > viewConfiguration.touchSlop) active = true
                            }
                        }
                        if (active && pressed >= 1) {
                            val centroid = event.calculateCentroid(useCurrent = true).takeIf { it.isSpecified } ?: Offset(size.width / 2f, size.height / 2f)
                            state.transform(event.calculateZoom(), event.calculatePan(), centroid, size.toSize())
                            // 안쪽 편집 제스처(그리기·마스크·자르기)가 같은 손가락을 처리하지 않도록 소비한다.
                            event.changes.forEach { it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
    ) {
        Box(
            Modifier.fillMaxSize().graphicsLayer {
                scaleX = state.zoom
                scaleY = state.zoom
                translationX = state.pan.x
                translationY = state.pan.y
            },
            content = content,
        )
    }
}
