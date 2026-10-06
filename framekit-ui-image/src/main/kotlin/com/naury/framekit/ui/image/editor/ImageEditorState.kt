package com.naury.framekit.ui.image.editor

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

/** Current drawing tool settings; they are UI state, not part of the project until a stroke is drawn. */
internal data class BrushSettings(
    val kind: BrushKind = BrushKind.PEN,
    val colorArgb: Int = 0xFFFFFFFF.toInt(),
    val widthShortEdgeRatio: Double = 0.012,
    val opacity: Double = 1.0,
)

/** Progress of a background removal request. */
internal sealed interface CutoutStatus {
    data object Processing : CutoutStatus
    data class Failed(val code: EditorErrorCode) : CutoutStatus
}

/** Mask kind for the privacy tool. */
internal enum class PrivacyShape { BRUSH, RECTANGLE, ELLIPSE }

/**
 * Privacy tool settings.
 *
 * @property strength `0..1`, mapped to the mosaic block or blur radius.
 * @property brushWidthShortEdgeRatio brush diameter relative to the canvas short edge.
 */
internal data class PrivacySettings(
    val mosaic: Boolean = true,
    val shape: PrivacyShape = PrivacyShape.BRUSH,
    val strength: Double = 0.4,
    val brushWidthShortEdgeRatio: Double = 0.06,
) {
    fun effect(): PrivacyEffect = if (mosaic) {
        PrivacyEffect.Mosaic(MIN_BLOCK + strength * (MAX_BLOCK - MIN_BLOCK))
    } else {
        PrivacyEffect.Blur(MIN_BLUR + strength * (MAX_BLUR - MIN_BLUR))
    }

    private companion object {
        const val MIN_BLOCK = 0.01
        const val MAX_BLOCK = 0.08
        const val MIN_BLUR = 0.005
        const val MAX_BLUR = 0.05
    }
}

/** One-time message after a session was restored from disk. */
internal enum class SessionNotice {
    /** Committed edits came back after process death; undo history starts empty. */
    RESTORED,

    /** The process died during a save. The file was not created and must be saved again. */
    EXPORT_INTERRUPTED,
}

internal sealed interface ExportUiState {
    data class Running(val stage: ExportStageUi) : ExportUiState
    data class Failed(val code: EditorErrorCode) : ExportUiState
}
