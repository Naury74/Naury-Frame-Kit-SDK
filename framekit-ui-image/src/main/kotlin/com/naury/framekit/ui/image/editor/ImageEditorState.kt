package com.naury.framekit.ui.image.editor

import com.naury.framekit.ui.tool.PrivacySettings
import com.naury.framekit.android.result.EditorErrorCode
import com.naury.framekit.core.effect.AdjustmentKind
import com.naury.framekit.core.overlay.BrushKind
import com.naury.framekit.core.overlay.EmojiCatalog
import com.naury.framekit.core.overlay.PrivacyEffect
import com.naury.framekit.core.geometry.CropAspectRatio
import com.naury.framekit.core.history.HistoryTransaction
import com.naury.framekit.core.model.ImageProject
import com.naury.framekit.image.decode.DecodedImage
import com.naury.framekit.image.decode.ImageSourceInfo
import com.naury.framekit.ui.component.ExportStageUi
import com.naury.framekit.ui.image.contract.ImageTool

internal sealed interface ImageEditorUiState {
    /** 시스템 피커를 기다리는 중. */
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
        val notice: SessionNotice? = null,
        val adjustKind: AdjustmentKind = AdjustmentKind.BRIGHTNESS,
        val selectedOverlayId: String? = null,
        val editingTextId: String? = null,
        val brush: BrushSettings = BrushSettings(),
        val stickerCategory: EmojiCatalog.Category = EmojiCatalog.Category.SMILEYS,
        val privacy: PrivacySettings = PrivacySettings(),
        val cutoutStatus: CutoutStatus? = null,
    ) : ImageEditorUiState {
        val displayed: ImageProject get() = transaction.displayed
        val isDirty: Boolean get() = transaction.history.isDirty
        val hasDraftChanges: Boolean
            get() = transaction.draft?.let { !it.sameContentAs(transaction.history.current) } == true
    }
}

/** 현재 그리기 도구 설정. UI 상태이며 획을 그리기 전까지는 프로젝트에 포함되지 않는다. */
internal data class BrushSettings(
    val kind: BrushKind = BrushKind.PEN,
    val colorArgb: Int = 0xFFFFFFFF.toInt(),
    val widthShortEdgeRatio: Double = 0.012,
    val opacity: Double = 1.0,
)

/** 배경 제거 요청의 진행 상태. */
internal sealed interface CutoutStatus {
    data object Processing : CutoutStatus
    data class Failed(val code: EditorErrorCode) : CutoutStatus
}

/** 디스크에서 세션을 복원한 뒤 한 번 보여주는 메시지. */
internal enum class SessionNotice {
    /** 프로세스 종료 후 커밋된 편집이 복원되었다. 실행 취소 히스토리는 비어 있는 상태로 시작한다. */
    RESTORED,

    /** 저장 중 프로세스가 종료되었다. 파일이 만들어지지 않았으므로 다시 저장해야 한다. */
    EXPORT_INTERRUPTED,
}

internal sealed interface ExportUiState {
    data class Running(val stage: ExportStageUi) : ExportUiState
    data class Failed(val code: EditorErrorCode) : ExportUiState
}
