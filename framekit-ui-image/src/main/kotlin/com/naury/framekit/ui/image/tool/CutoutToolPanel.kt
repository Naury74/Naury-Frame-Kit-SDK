package com.naury.framekit.ui.image.tool

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.naury.framekit.android.result.EditorErrorCode
import com.naury.framekit.ui.component.DialogAction
import com.naury.framekit.ui.component.DialogActionStyle
import com.naury.framekit.ui.component.FrameKitDialog
import com.naury.framekit.ui.design.FrameKitTheme
import com.naury.framekit.ui.image.R
import com.naury.framekit.ui.image.editor.CutoutStatus
import kotlin.math.roundToInt
import com.naury.framekit.ui.R as UiR

/**
 * 한 번의 탭으로 배경 제거와 복원. 모델이 아직 없으면 왜 받아야 하는지와 내려받기 버튼을, 받는 중이면 진행 상황을 보여 준다.
 */
@Composable
internal fun CutoutToolPanel(
    applied: Boolean,
    status: CutoutStatus?,
    onRemove: () -> Unit,
    onRestore: () -> Unit,
    onDownloadModel: () -> Unit,
    onRetryDownload: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = FrameKitTheme.colors
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.framekit_cutout_description), color = colors.foregroundMuted, style = MaterialTheme.typography.bodySmall)
        when {
            status == CutoutStatus.Processing -> ProgressRow(stringResource(R.string.framekit_cutout_processing))
            status == CutoutStatus.ModelChecking -> ProgressRow(stringResource(R.string.framekit_cutout_checking))
            status == CutoutStatus.ModelNeedsDownload -> ModelCard(
                icon = painterResource(UiR.drawable.framekit_ic_cutout),
                title = stringResource(R.string.framekit_cutout_model_title),
                body = stringResource(R.string.framekit_cutout_model_body),
            ) {
                Button(
                    onClick = onDownloadModel,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = colors.accent, contentColor = colors.onAccent),
                ) { Text(stringResource(R.string.framekit_cutout_model_download), fontWeight = FontWeight.SemiBold) }
            }
            status is CutoutStatus.ModelDownloading -> DownloadProgress(status)
            status is CutoutStatus.ModelDownloadFailed -> {
                Text(
                    stringResource(if (status.noNetwork) R.string.framekit_cutout_model_failed_network else R.string.framekit_cutout_model_failed),
                    color = colors.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedButton(onClick = onRetryDownload, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(UiR.string.framekit_action_retry), color = colors.foreground)
                }
            }
            // 다시 눌러도 같은 결과이므로 버튼 대신 안내만 둔다. 이미 적용한 배경 제거는 복원 버튼으로 끌 수 있다.
            (status == CutoutStatus.DeviceUnsupported || status == CutoutStatus.NoPlayServices) && !applied -> Text(
                stringResource(if (status == CutoutStatus.NoPlayServices) R.string.framekit_cutout_no_play_services else R.string.framekit_cutout_device_unsupported),
                color = colors.error,
                style = MaterialTheme.typography.bodyMedium,
            )
            // 이미 지운 사진이면 다시 지우거나 원래 배경으로 되돌릴 수 있게 둘 다 보여 준다.
            applied -> {
                Button(
                    onClick = onRemove,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = colors.accent, contentColor = colors.onAccent),
                ) { Text(stringResource(R.string.framekit_cutout_remove_again)) }
                OutlinedButton(onClick = onRestore, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.framekit_cutout_restore), color = colors.foreground)
                }
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

@Composable
private fun ProgressRow(text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        CircularProgressIndicator(color = FrameKitTheme.colors.accent, modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
        Text(text, color = FrameKitTheme.colors.foreground)
    }
}

/** 아이콘·제목·설명과 아래 버튼을 담는 둥근 안내 카드. */
@Composable
private fun ModelCard(icon: Painter, title: String, body: String, action: @Composable () -> Unit) {
    val colors = FrameKitTheme.colors
    Column(
        Modifier.fillMaxWidth().background(colors.raised, RoundedCornerShape(16.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(icon, contentDescription = null, tint = colors.accent, modifier = Modifier.background(colors.accent.copy(alpha = 0.15f), CircleShape).padding(8.dp).size(20.dp))
            Text(title, color = colors.foreground, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        }
        Text(body, color = colors.foregroundMuted, style = MaterialTheme.typography.bodySmall)
        action()
    }
}

@Composable
private fun DownloadProgress(status: CutoutStatus.ModelDownloading) {
    val colors = FrameKitTheme.colors
    val context = LocalContext.current
    val fraction = status.fraction
    Column(
        Modifier.fillMaxWidth().background(colors.raised, RoundedCornerShape(16.dp)).padding(16.dp).semantics { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(if (status.installing) R.string.framekit_cutout_model_installing else R.string.framekit_cutout_model_downloading),
                color = colors.foreground,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            if (fraction != null && !status.installing) Text("${(fraction * 100).roundToInt()}%", color = colors.accent, style = MaterialTheme.typography.labelLarge)
        }
        // 크기를 아직 모르거나 설치 중이면 끝을 알 수 없는 막대로 보여 준다.
        if (fraction != null && !status.installing) {
            LinearProgressIndicator(progress = { fraction }, color = colors.accent, trackColor = colors.background, modifier = Modifier.fillMaxWidth())
        } else {
            LinearProgressIndicator(color = colors.accent, trackColor = colors.background, modifier = Modifier.fillMaxWidth())
        }
        if (status.total > 0) {
            Text(
                stringResource(R.string.framekit_cutout_model_progress, Formatter.formatShortFileSize(context, status.bytes), Formatter.formatShortFileSize(context, status.total)),
                color = colors.foregroundMuted,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Text(stringResource(R.string.framekit_cutout_model_note), color = colors.foregroundMuted, style = MaterialTheme.typography.bodySmall)
    }
}

/**
 * 모델을 내려받기 전에 이유와 데이터 사용을 알린다. 모바일 데이터에 연결되어 있으면 경고를 더 눈에 띄게 보여 준다.
 */
@Composable
internal fun CutoutModelDownloadDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val colors = FrameKitTheme.colors
    val context = LocalContext.current
    val metered = remember { isMeteredNetwork(context) }
    FrameKitDialog(
        title = stringResource(R.string.framekit_cutout_model_dialog_title),
        icon = ImageVector.vectorResource(UiR.drawable.framekit_ic_cutout),
        message = stringResource(R.string.framekit_cutout_model_dialog_body),
        onDismiss = onDismiss,
        actions = listOf(
            DialogAction(stringResource(R.string.framekit_action_download), DialogActionStyle.PRIMARY, onClick = onConfirm),
            DialogAction(stringResource(R.string.framekit_action_later), DialogActionStyle.SECONDARY, onClick = onDismiss),
        ),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            NoteRow(Icons.Filled.Lock, stringResource(R.string.framekit_cutout_model_privacy), colors.foregroundMuted)
            NoteRow(
                Icons.Filled.Warning,
                stringResource(if (metered) R.string.framekit_cutout_model_metered_warning else R.string.framekit_cutout_model_data_warning),
                if (metered) colors.error else colors.foregroundMuted,
            )
        }
    }
}

@Composable
private fun NoteRow(icon: ImageVector, text: String, tint: Color) {
    val colors = FrameKitTheme.colors
    Row(
        Modifier.fillMaxWidth().background(colors.background, RoundedCornerShape(12.dp)).padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
        Text(text, color = if (tint == colors.error) colors.foreground else colors.foregroundMuted, style = MaterialTheme.typography.bodySmall)
    }
}

// 연결 상태를 알 수 없으면(권한 없음 등) 요금이 나올 수 있다고 보고 경고를 강하게 보여 준다.
@SuppressLint("MissingPermission")
private fun isMeteredNetwork(context: Context): Boolean = try {
    context.getSystemService(ConnectivityManager::class.java)?.isActiveNetworkMetered ?: true
} catch (_: SecurityException) {
    true
}
