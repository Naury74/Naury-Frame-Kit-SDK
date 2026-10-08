package com.naury.framekit.ui.image.tool

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.foundation.BorderStroke
import com.naury.framekit.ui.design.FrameKitTheme
import com.naury.framekit.ui.image.R

/**
 * 문서 보정 도구의 안내와 버튼. 모서리는 캔버스에서 끌어 맞춘다.
 *
 * @param rectified 지금 사진이 이미 문서 보정된 이미지면 `true`. "원본으로"를 보여 준다.
 */
@Composable
internal fun DocumentToolPanel(
    busy: Boolean,
    rectified: Boolean,
    onDetect: () -> Unit,
    onWholeImage: () -> Unit,
    onRestore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = FrameKitTheme.colors
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (busy) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.heightIn(min = 48.dp)) {
                CircularProgressIndicator(color = colors.accent, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                Text(stringResource(R.string.framekit_document_working), color = colors.foreground, style = MaterialTheme.typography.bodyMedium)
            }
            return@Column
        }
        Text(
            stringResource(R.string.framekit_document_hint),
            color = colors.foregroundMuted,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
        )
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PanelButton(stringResource(R.string.framekit_document_detect), onDetect, Modifier.weight(1f))
            PanelButton(stringResource(R.string.framekit_document_whole), onWholeImage, Modifier.weight(1f))
            if (rectified) PanelButton(stringResource(R.string.framekit_document_restore), onRestore, Modifier.weight(1f))
        }
    }
}

@Composable
private fun PanelButton(label: String, onClick: () -> Unit, modifier: Modifier) {
    val colors = FrameKitTheme.colors
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 44.dp),
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, colors.raised),
    ) {
        Text(label, color = colors.foreground, style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}
