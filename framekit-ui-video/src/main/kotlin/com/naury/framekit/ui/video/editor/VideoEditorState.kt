package com.naury.framekit.ui.video.editor

import com.naury.framekit.android.result.EditorErrorCode
import com.naury.framekit.android.session.SourceReference
import com.naury.framekit.android.source.SourceLocation
import com.naury.framekit.core.effect.AdjustmentKind
import com.naury.framekit.core.geometry.CropAspectRatio
import com.naury.framekit.core.history.HistoryTransaction
import com.naury.framekit.core.model.SourceId
import com.naury.framekit.core.overlay.EmojiCatalog
import com.naury.framekit.core.video.TimelineTimeMapper
import com.naury.framekit.core.video.VideoClip
import com.naury.framekit.core.video.VideoProject
import com.naury.framekit.ui.component.ExportStageUi
import com.naury.framekit.ui.tool.PrivacySettings
import com.naury.framekit.ui.tool.PrivacyShape
import com.naury.framekit.ui.video.contract.VideoTool
import com.naury.framekit.video.source.AudioSourceInfo
import com.naury.framekit.video.source.VideoSourceInfo

/** 열어 둔 영상 원본. [reference]는 세션에 저장해 프로세스 종료 뒤 다시 열 때 쓴다. */
internal data class LoadedVideo(val info: VideoSourceInfo, val location: SourceLocation, val reference: SourceReference?)

/** 열어 둔 배경 음악 원본. [name]은 화면에 보일 파일 이름이다. */
internal data class LoadedAudio(val info: AudioSourceInfo, val location: SourceLocation, val reference: SourceReference?, val name: String?)

internal sealed interface VideoEditorUiState {
    /** 시스템 picker를 기다리는 중. */
    data object AwaitingPick : VideoEditorUiState

    data object Loading : VideoEditorUiState

    data class LoadFailed(val code: EditorErrorCode, val canChooseAnother: Boolean) : VideoEditorUiState

    /**
     * @property selectedClipId 클립 단위 도구(구간·자르기·회전·보정·필터·속도·소리)가 고치는 클립.
     * @property busy 클립이나 음악을 불러오는 중이면 `true`.
     */
    data class Ready(
        val sources: Map<SourceId, LoadedVideo>,
        val transaction: HistoryTransaction<VideoProject>,
        val selectedClipId: String,
        val music: Map<SourceId, LoadedAudio> = emptyMap(),
        val activeTool: VideoTool? = null,
        val cropAspect: CropAspectRatio = CropAspectRatio.Free,
        val draggingCrop: Boolean = false,
        val adjustKind: AdjustmentKind = AdjustmentKind.BRIGHTNESS,
        val privacy: PrivacySettings = PrivacySettings(shape = PrivacyShape.RECTANGLE),
        val selectedMaskId: String? = null,
        val selectedOverlayId: String? = null,
        val editingTextId: String? = null,
        val stickerCategory: EmojiCatalog.Category = EmojiCatalog.Category.SMILEYS,
        val export: ExportUiState? = null,
        val showDiscardDialog: Boolean = false,
        val showApplyHint: Boolean = false,
        val notice: VideoNotice? = null,
        val busy: Boolean = false,
    ) : VideoEditorUiState {
        val displayed: VideoProject get() = transaction.displayed
        val clips: List<VideoClip> get() = displayed.timeline.videoClips

        /** 선택한 클립. 선택이 사라졌으면 첫 클립. */
        val clip: VideoClip get() = clips.firstOrNull { it.id == selectedClipId } ?: clips.first()
        val source: VideoSourceInfo get() = sources.getValue(clip.source).info
        val location: SourceLocation get() = sources.getValue(clip.source).location
        val clipStartUs: Long get() = VideoTimelineEditing.clipStart(displayed, clip.id)
        val durationUs: Long get() = TimelineTimeMapper.durationUs(displayed.timeline)
        val sourceDurationUs: Long get() = source.metadata.durationUs ?: clip.sourceRange.endExclusiveUs
        val isDirty: Boolean get() = transaction.history.isDirty
        val hasDraftChanges: Boolean
            get() = transaction.draft?.let { !it.sameContentAs(transaction.history.current) } == true
    }
}

/** 스낵바로 한 번 보여 줄 안내. */
internal enum class VideoNotice {
    MASK_LIMIT,

    /** 프로세스 종료 뒤 확정된 편집이 돌아왔다. 실행 취소 기록은 비어 있다. */
    RESTORED,

    /** 저장 중에 프로세스가 종료됐다. 파일은 만들어지지 않았으니 다시 저장해야 한다. */
    EXPORT_INTERRUPTED,
    SPEED_TOO_LONG,
    SPEED_TOO_SHORT,

    /** 설정한 최대 클립 수에 도달했다. */
    CLIP_LIMIT,

    /** 더 붙이면 최대 길이를 넘는다. */
    TIMELINE_FULL,

    /** 재생 위치가 클립 경계이거나 나누면 최소 길이보다 짧아진다. */
    SPLIT_UNAVAILABLE,

    /** 고른 영상이나 음악을 열 수 없다. */
    ADD_FAILED,

    /** 이전 세션의 일부 원본을 다시 열 수 없어 그 클립이나 음악을 빼고 복원했다. */
    PARTIALLY_RESTORED,
}

internal sealed interface ExportUiState {
    /** @property progress Media3가 진행률을 추정할 수 있으면 인코딩 중 `0..1`. */
    data class Running(val stage: ExportStageUi, val progress: Float? = null) : ExportUiState
    data class Failed(val code: EditorErrorCode) : ExportUiState
}
