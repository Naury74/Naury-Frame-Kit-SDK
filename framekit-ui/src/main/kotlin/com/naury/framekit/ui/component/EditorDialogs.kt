package com.naury.framekit.ui.component

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.naury.framekit.android.result.EditorErrorCode
import com.naury.framekit.ui.R
import com.naury.framekit.ui.design.FrameKitTheme
import com.naury.framekit.ui.text.messageRes

/** 저장하지 않은 변경이 있는 에디터를 닫기 전에 확인한다. 버리기가 되돌릴 수 없는 동작임을 색으로 구분한다. */
@Composable
public fun DiscardChangesDialog(onDiscard: () -> Unit, onKeepEditing: () -> Unit) {
    FrameKitDialog(
        title = stringResource(R.string.framekit_discard_title),
        message = stringResource(R.string.framekit_discard_message),
        icon = Icons.Filled.Delete,
        iconTint = FrameKitTheme.colors.error,
        onDismiss = onKeepEditing,
        actions = listOf(
            DialogAction(stringResource(R.string.framekit_action_keep_editing), DialogActionStyle.PRIMARY, onClick = onKeepEditing),
            DialogAction(stringResource(R.string.framekit_action_discard), DialogActionStyle.DESTRUCTIVE, onClick = onDiscard),
        ),
    )
}

/**
 * 초안 도구(자르기·회전·텍스트 등)에서 바뀐 내용이 있는데 뒤로 가기를 눌렀을 때 묻는다.
 * 어떤 편집을 다루는지 바로 알 수 있도록 제목과 버튼에 도구 이름을 넣는다(예: "자르기 적용", "자르기 취소").
 *
 * @param toolName 열려 있는 도구의 화면 이름(예: "자르기").
 */
@Composable
public fun ApplyDraftDialog(toolName: String, onApply: () -> Unit, onDiscard: () -> Unit, onKeepEditing: () -> Unit) {
    FrameKitDialog(
        title = stringResource(R.string.framekit_draft_title, toolName),
        message = stringResource(R.string.framekit_draft_message, toolName),
        icon = Icons.Filled.Edit,
        onDismiss = onKeepEditing,
        actions = listOf(
            DialogAction(stringResource(R.string.framekit_draft_apply, toolName), DialogActionStyle.PRIMARY, onClick = onApply),
            DialogAction(stringResource(R.string.framekit_draft_discard, toolName), DialogActionStyle.DESTRUCTIVE, onClick = onDiscard),
            DialogAction(stringResource(R.string.framekit_action_keep_editing), DialogActionStyle.SECONDARY, onClick = onKeepEditing),
        ),
    )
}

/** 내보내기 실패를 설명한다. 프로젝트는 그대로이므로 사용자는 다시 시도하거나 편집을 이어갈 수 있다. */
@Composable
public fun ExportErrorDialog(code: EditorErrorCode, onRetry: () -> Unit, onDismiss: () -> Unit) {
    val keepEditing = stringResource(R.string.framekit_action_keep_editing)
    FrameKitDialog(
        title = stringResource(R.string.framekit_export_failed_title),
        message = stringResource(code.messageRes()),
        icon = Icons.Filled.Warning,
        iconTint = FrameKitTheme.colors.error,
        onDismiss = onDismiss,
        actions = if (code.defaultRecoverable) {
            listOf(
                DialogAction(stringResource(R.string.framekit_action_retry), DialogActionStyle.PRIMARY, onClick = onRetry),
                DialogAction(keepEditing, DialogActionStyle.SECONDARY, onClick = onDismiss),
            )
        } else {
            listOf(DialogAction(keepEditing, DialogActionStyle.PRIMARY, onClick = onDismiss))
        },
    )
}
