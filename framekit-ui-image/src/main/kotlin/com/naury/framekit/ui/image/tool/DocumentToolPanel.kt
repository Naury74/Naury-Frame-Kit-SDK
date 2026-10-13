package com.naury.framekit.ui.image.tool

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.BorderStroke
import com.naury.framekit.core.document.ScanMode
import com.naury.framekit.ui.component.ChoiceChips
import com.naury.framekit.ui.design.FrameKitTheme
import com.naury.framekit.ui.image.R
import com.naury.framekit.ui.image.editor.DocumentBatch

/**
 * 문서 보정 도구의 안내와 버튼. 모서리는 캔버스에서 끌어 맞춘다.
 *
 * @param batch 모든 쪽을 스캔하는 중이면 진행 상황.
 * @param rectified 지금 사진이 이미 문서 보정된 이미지면 `true`. "원본으로"를 보여 준다.
 * @param pageCount 편집 중인 쪽 수. 2쪽 이상이면 "모든 쪽 스캔"을 보여 준다.
 */
@Composable
internal fun DocumentToolPanel(
    busy: Boolean,
    batch: DocumentBatch?,
    rectified: Boolean,
    pageCount: Int,
    onScanAll: () -> Unit,
    scanMode: ScanMode,
    onScanMode: (ScanMode) -> Unit,
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
                val label = if (batch != null) {
                    stringResource(R.string.framekit_document_scanning_pages, minOf(batch.done + 1, batch.total), batch.total)
                } else {
                    stringResource(R.string.framekit_document_working)
                }
                Text(label, color = colors.foreground, style = MaterialTheme.typography.bodyMedium)
            }
            return@Column
        }
        Text(
            stringResource(R.string.framekit_document_hint),
            color = colors.foregroundMuted,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
        )
        // 펴고 나서 어떻게 정리할지. 스캐너 앱처럼 기본은 색을 살린 스캔이다.
        ChoiceChips(
            options = ScanMode.entries,
            selected = scanMode,
            label = { mode ->
                stringResource(
                    when (mode) {
                        ScanMode.COLOR -> R.string.framekit_scan_color
                        ScanMode.GRAYSCALE -> R.string.framekit_scan_grayscale
                        ScanMode.BLACK_WHITE -> R.string.framekit_scan_black_white
                        ScanMode.ORIGINAL -> R.string.framekit_scan_original
                    },
                )
            },
            onSelect = onScanMode,
            modifier = Modifier.padding(top = 10.dp),
        )
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PanelButton(stringResource(R.string.framekit_document_detect), onDetect, Modifier.weight(1f))
            PanelButton(stringResource(R.string.framekit_document_whole), onWholeImage, Modifier.weight(1f))
            if (rectified) PanelButton(stringResource(R.string.framekit_document_restore), onRestore, Modifier.weight(1f))
        }
        if (pageCount > 1) {
            // 지금 쪽은 맞춘 모서리로, 나머지 쪽은 자동으로 찾아 같은 스캔 방식으로 편다.
            Button(
                onClick = onScanAll,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp).heightIn(min = 44.dp),
                shape = MaterialTheme.shapes.medium,
                colors = ButtonDefaults.buttonColors(containerColor = colors.raised, contentColor = colors.foreground),
            ) {
                Text(pluralStringResource(R.plurals.framekit_document_scan_all, pageCount, pageCount), style = MaterialTheme.typography.labelLarge, maxLines = 1)
            }
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
