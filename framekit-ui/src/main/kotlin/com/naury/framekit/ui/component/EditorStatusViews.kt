package com.naury.framekit.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.naury.framekit.android.result.EditorErrorCode
import com.naury.framekit.ui.R
import com.naury.framekit.ui.design.FrameKitTheme
import com.naury.framekit.ui.text.messageRes

/** 소스를 확인하고 미리보기를 디코딩하는 동안 표시한다. */
@Composable
public fun EditorLoadingView(modifier: Modifier = Modifier) {
    val colors = FrameKitTheme.colors
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator(color = colors.accent)
            Text(stringResource(R.string.framekit_loading), color = colors.foregroundMuted)
        }
    }
}

/** 열 수 없는 소스에 대한 전체 화면 오류. */
@Composable
public fun EditorErrorView(
    code: EditorErrorCode,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    onChooseAnother: (() -> Unit)? = null,
) {
    val colors = FrameKitTheme.colors
    Box(modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(
                stringResource(code.messageRes()),
                color = colors.foreground,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
            if (onChooseAnother != null) {
                Button(
                    onClick = onChooseAnother,
                    colors = ButtonDefaults.buttonColors(containerColor = colors.accent, contentColor = colors.onAccent),
                ) { Text(stringResource(R.string.framekit_action_choose_another)) }
            }
            OutlinedButton(onClick = onClose) { Text(stringResource(R.string.framekit_action_close), color = colors.foreground) }
        }
    }
}
