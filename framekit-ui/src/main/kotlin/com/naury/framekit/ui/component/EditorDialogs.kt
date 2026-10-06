package com.naury.framekit.ui.component

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.naury.framekit.android.result.EditorErrorCode
import com.naury.framekit.ui.R
import com.naury.framekit.ui.design.FrameKitTheme
import com.naury.framekit.ui.text.messageRes

/** Asks before closing an editor with unsaved changes. */
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

/** Explains a failed export. The project stays as it was, so the user can retry or keep editing. */
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
