package com.naury.framekit.ui.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput

/**
 * 사진만 보도록 편집 화면의 상단 바·도구 같은 조작부를 잠시 숨긴다. 숨긴 동안에는 자리를 그대로 두어 사진이 움직이지 않고,
 * 그 자리를 누르면 [onReveal]로 다시 보이게 한다(숨긴 버튼이 눌리지 않는다).
 */
@Composable
public fun Modifier.editorChrome(hidden: Boolean, onReveal: () -> Unit): Modifier {
    val alpha by animateFloatAsState(if (hidden) 0f else 1f, tween(CHROME_FADE_MS), label = "editor-chrome")
    val reveal by rememberUpdatedState(onReveal)
    val blocking = if (hidden) {
        Modifier.pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    event.changes.forEach { it.consume() }
                    if (event.type == PointerEventType.Release) reveal()
                }
            }
        }
    } else {
        Modifier
    }
    return this.graphicsLayer { this.alpha = alpha }.then(blocking)
}

private const val CHROME_FADE_MS = 200
