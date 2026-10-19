package com.naury.framekit.ui.component

import androidx.compose.ui.semantics.clearAndSetSemantics
import com.naury.kinetica.ui.KButton
import com.naury.kinetica.ui.KLoadingIndicator
import com.naury.kinetica.ui.KTone
import com.naury.kinetica.ui.KVariant
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
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
            val label = stringResource(R.string.framekit_loading)
            KLoadingIndicator(label = label)
            Text(label, color = colors.foregroundMuted, modifier = Modifier.clearAndSetSemantics {})
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
                KButton(stringResource(R.string.framekit_action_choose_another), onClick = onChooseAnother)
            }
            KButton(stringResource(R.string.framekit_action_close), onClick = onClose, variant = KVariant.Tonal, tone = KTone.Neutral)
        }
    }
}
