package com.naury.framekit.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import com.naury.framekit.ui.design.frameKitGlass
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.naury.framekit.ui.R
import com.naury.framekit.ui.design.FrameKitTheme
import com.naury.kinetica.ui.KIconButton
import com.naury.kinetica.ui.KTone
import com.naury.kinetica.ui.KVariant

/**
 * 캔버스 위에 떠 있는 실행 취소/다시 실행 버튼. 위치가 튀지 않도록 비활성 버튼도 계속 보이며,
 * 그 상태는 접근성 서비스가 알린다.
 */
@Composable
public fun HistoryControls(
    canUndo: Boolean,
    canRedo: Boolean,
    showUndo: Boolean,
    showRedo: Boolean,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    modifier: Modifier = Modifier,
    onCompare: ((Boolean) -> Unit)? = null,
) {
    if (!showUndo && !showRedo && onCompare == null) return
    Row(modifier.frameKitGlass(CircleShape).padding(horizontal = 4.dp)) {
        if (showUndo) {
            KIconButton(
                painterResource(R.drawable.framekit_ic_undo),
                stringResource(R.string.framekit_action_undo),
                onUndo,
                variant = KVariant.Text,
                tone = KTone.Neutral,
                size = 48.dp,
                enabled = canUndo,
            )
        }
        if (showRedo) {
            KIconButton(
                painterResource(R.drawable.framekit_ic_redo),
                stringResource(R.string.framekit_action_redo),
                onRedo,
                variant = KVariant.Text,
                tone = KTone.Neutral,
                size = 48.dp,
                enabled = canRedo,
            )
        }
        if (onCompare != null) CompareButton(onCompare)
    }
}

/**
 * 누르고 있는 동안 원본을 보여 준다. 화면 읽기 프로그램 사용자는 길게 누르기가 어려우므로
 * 한 번 탭하면 원본 보기를 켜고 다시 탭하면 끈다.
 */
@Composable
private fun CompareButton(onCompare: (Boolean) -> Unit) {
    val colors = FrameKitTheme.colors
    var toggled by remember { mutableStateOf(false) }
    val label = stringResource(R.string.framekit_action_compare)
    Box(
        Modifier
            .size(48.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = label
                role = Role.Switch
                toggleableState = ToggleableState(toggled)
                onClick {
                    toggled = !toggled
                    onCompare(toggled)
                    true
                }
            }
            .pointerInput(onCompare) {
                detectTapGestures(onPress = {
                    onCompare(true)
                    tryAwaitRelease()
                    toggled = false
                    onCompare(false)
                })
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(R.drawable.framekit_ic_compare), contentDescription = null, tint = colors.foreground)
    }
}
