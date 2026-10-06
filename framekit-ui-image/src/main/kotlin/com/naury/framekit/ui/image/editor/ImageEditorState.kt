package com.naury.framekit.ui.image.editor

import com.naury.framekit.android.result.EditorErrorCode
import com.naury.framekit.core.geometry.CropAspectRatio
import com.naury.framekit.core.history.HistoryTransaction
import com.naury.framekit.core.model.ImageProject
import com.naury.framekit.image.decode.DecodedImage
import com.naury.framekit.image.decode.ImageSourceInfo
import com.naury.framekit.ui.component.ExportStageUi
import com.naury.framekit.ui.image.contract.ImageTool

internal sealed interface ImageEditorUiState {
    /** Waiting for the system picker. */
    data object AwaitingPick : ImageEditorUiState

    data object Loading : ImageEditorUiState

    data class LoadFailed(val code: EditorErrorCode, val canChooseAnother: Boolean) : ImageEditorUiState

    data class Ready(
        val preview: DecodedImage,
        val source: ImageSourceInfo,
        val transaction: HistoryTransaction<ImageProject>,
        val activeTool: ImageTool? = null,
        val cropAspect: CropAspectRatio = CropAspectRatio.Free,
        val export: ExportUiState? = null,
        val showDiscardDialog: Boolean = false,
        val showApplyHint: Boolean = false,
        val showingOriginal: Boolean = false,
        val draggingCrop: Boolean = false,
    ) : ImageEditorUiState {
        val displayed: ImageProject get() = transaction.displayed
        val isDirty: Boolean get() = transaction.history.isDirty
        val hasDraftChanges: Boolean
            get() = transaction.draft?.let { !it.sameContentAs(transaction.history.current) } == true
    }
}

internal sealed interface ExportUiState {
    data class Running(val stage: ExportStageUi) : ExportUiState
    data class Failed(val code: EditorErrorCode) : ExportUiState
}
