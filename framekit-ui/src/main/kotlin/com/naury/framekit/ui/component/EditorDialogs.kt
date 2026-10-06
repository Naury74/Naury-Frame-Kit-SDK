package com.naury.framekit.ui.component

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.naury.framekit.android.result.EditorErrorCode
import com.naury.framekit.ui.R
import com.naury.framekit.ui.design.FrameKitTheme
import com.naury.framekit.ui.text.messageRes

/** 저장하지 않은 변경이 있는 에디터를 닫기 전에 확인한다. */
@Composable
public fun DiscardChangesDialog(onDiscard: () -> Unit, onKeepEditing: () -> Unit) {
    val colors = FrameKitTheme.colors
    AlertDialog(
        onDismissRequest = onKeepEditing,
        title = { Text(stringResource(R.string.framekit_discard_title)) },
        text = { Text(stringResource(R.string.framekit_discard_message)) },
        confirmButton = {
            TextButton(onClick = onDiscard) { Text(stringResource(R.string.framekit_action_discard), color = colors.error) }
        },
        dismissButton = {
            TextButton(onClick = onKeepEditing) { Text(stringResource(R.string.framekit_action_keep_editing)) }
        },
        containerColor = colors.surface,
    )
}

/**
 * 초안 도구(자르기·회전·텍스트 등)에서 바뀐 내용이 있는데 뒤로 가기를 눌렀을 때 묻는다.
 * 실수로 눌러 작업을 잃지 않도록 적용·버리기·계속 편집 중 하나를 고르게 한다.
 */
@Composable
public fun ApplyDraftDialog(onApply: () -> Unit, onDiscard: () -> Unit, onKeepEditing: () -> Unit) {
    val colors = FrameKitTheme.colors
    AlertDialog(
        onDismissRequest = onKeepEditing,
        title = { Text(stringResource(R.string.framekit_draft_title)) },
        text = { Text(stringResource(R.string.framekit_draft_message)) },
        confirmButton = {
            TextButton(onClick = onApply) { Text(stringResource(R.string.framekit_action_apply)) }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDiscard) { Text(stringResource(R.string.framekit_action_discard), color = colors.error) }
                TextButton(onClick = onKeepEditing) { Text(stringResource(R.string.framekit_action_keep_editing)) }
            }
        },
        containerColor = colors.surface,
    )
}

/** 내보내기 실패를 설명한다. 프로젝트는 그대로이므로 사용자는 다시 시도하거나 편집을 이어갈 수 있다. */
@Composable
public fun ExportErrorDialog(code: EditorErrorCode, onRetry: () -> Unit, onDismiss: () -> Unit) {
    val colors = FrameKitTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.framekit_export_failed_title)) },
        text = { Text(stringResource(code.messageRes())) },
        confirmButton = {
            if (code.defaultRecoverable) TextButton(onClick = onRetry) { Text(stringResource(R.string.framekit_action_retry)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.framekit_action_keep_editing)) }
        },
        containerColor = colors.surface,
    )
}
