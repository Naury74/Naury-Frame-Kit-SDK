package com.naury.framekit.ui.image.tool

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.naury.framekit.android.result.EditorErrorCode
import com.naury.framekit.ui.design.FrameKitTheme
import com.naury.framekit.ui.image.R
import com.naury.framekit.ui.image.editor.CutoutStatus

/** 한 번의 탭으로 배경 제거와 복원. */
@Composable
internal fun CutoutToolPanel(
    applied: Boolean,
    status: CutoutStatus?,
    onRemove: () -> Unit,
    onRestore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = FrameKitTheme.colors
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.framekit_cutout_description), color = colors.foregroundMuted, style = MaterialTheme.typography.bodySmall)
        when {
            status == CutoutStatus.Processing -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            ) {
                CircularProgressIndicator(color = colors.accent, modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                Text(stringResource(R.string.framekit_cutout_processing), color = colors.foreground)
            }
            applied -> OutlinedButton(onClick = onRestore, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.framekit_cutout_restore), color = colors.foreground)
            }
            else -> Button(
                onClick = onRemove,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = colors.accent, contentColor = colors.onAccent),
            ) { Text(stringResource(R.string.framekit_cutout_remove)) }
        }
        if (status is CutoutStatus.Failed) {
            Text(
                stringResource(if (status.code == EditorErrorCode.UNSUPPORTED_OPERATION) R.string.framekit_cutout_unavailable else R.string.framekit_cutout_failed),
                color = colors.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
