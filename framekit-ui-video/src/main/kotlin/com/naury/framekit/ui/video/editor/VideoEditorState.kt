package com.naury.framekit.ui.video.editor

import com.naury.framekit.android.result.EditorErrorCode
import com.naury.framekit.android.source.SourceLocation
import com.naury.framekit.core.effect.AdjustmentKind
import com.naury.framekit.core.geometry.CropAspectRatio
import com.naury.framekit.core.history.HistoryTransaction
import com.naury.framekit.core.video.TimelineTimeMapper
import com.naury.framekit.core.video.VideoClip
import com.naury.framekit.core.video.VideoProject
import com.naury.framekit.ui.component.ExportStageUi
import com.naury.framekit.ui.tool.PrivacySettings
import com.naury.framekit.ui.tool.PrivacyShape
import com.naury.framekit.ui.video.contract.VideoTool
import com.naury.framekit.video.source.VideoSourceInfo

internal sealed interface VideoEditorUiState {
    /** Waiting for the system picker. */
    data object AwaitingPick : VideoEditorUiState

    data object Loading : VideoEditorUiState

    data class LoadFailed(val code: EditorErrorCode, val canChooseAnother: Boolean) : VideoEditorUiState

    data class Ready(
        val source: VideoSourceInfo,
        val location: SourceLocation,
        val transaction: HistoryTransaction<VideoProject>,
        val activeTool: VideoTool? = null,
        val cropAspect: CropAspectRatio = CropAspectRatio.Free,
        val draggingCrop: Boolean = false,
        val adjustKind: AdjustmentKind = AdjustmentKind.BRIGHTNESS,
        val privacy: PrivacySettings = PrivacySettings(shape = PrivacyShape.RECTANGLE),
        val selectedMaskId: String? = null,
        val export: ExportUiState? = null,
        val showDiscardDialog: Boolean = false,
        val showApplyHint: Boolean = false,
        val notice: VideoNotice? = null,
    ) : VideoEditorUiState {
        val displayed: VideoProject get() = transaction.displayed
        val clip: VideoClip get() = displayed.timeline.videoClips.first()
        val durationUs: Long get() = TimelineTimeMapper.durationUs(displayed.timeline)
        val sourceDurationUs: Long get() = source.metadata.durationUs ?: clip.sourceRange.endExclusiveUs
        val isDirty: Boolean get() = transaction.history.isDirty
        val hasDraftChanges: Boolean
            get() = transaction.draft?.let { !it.sameContentAs(transaction.history.current) } == true
    }
}

/** One-time message shown as a snackbar. */
internal enum class VideoNotice {
    MASK_LIMIT,

    /** 프로세스 종료 뒤 확정된 편집이 돌아왔다. 실행 취소 기록은 비어 있다. */
    RESTORED,

    /** 저장 중에 프로세스가 종료됐다. 파일은 만들어지지 않았으니 다시 저장해야 한다. */
    EXPORT_INTERRUPTED,
    SPEED_TOO_LONG,
    SPEED_TOO_SHORT,
}

internal sealed interface ExportUiState {
    /** @property progress `0..1` while encoding when Media3 can estimate it. */
    data class Running(val stage: ExportStageUi, val progress: Float? = null) : ExportUiState
    data class Failed(val code: EditorErrorCode) : ExportUiState
}
