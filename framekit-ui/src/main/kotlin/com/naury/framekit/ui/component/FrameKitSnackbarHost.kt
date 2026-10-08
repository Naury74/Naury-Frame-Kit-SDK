package com.naury.framekit.ui.component

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.naury.framekit.ui.design.FrameKitTheme

/** 편집기 테마 색·모서리를 따르는 안내 메시지. 실행 취소 같은 동작 버튼은 프라이머리 색으로 보인다. */
@Composable
public fun FrameKitSnackbarHost(state: SnackbarHostState, modifier: Modifier = Modifier) {
    val colors = FrameKitTheme.colors
    SnackbarHost(state, modifier) { data ->
        Snackbar(
            snackbarData = data,
            modifier = Modifier.padding(horizontal = 12.dp),
            shape = MaterialTheme.shapes.medium,
            containerColor = colors.raised,
            contentColor = colors.foreground,
            actionColor = colors.accent,
            actionContentColor = colors.accent,
        )
    }
}
