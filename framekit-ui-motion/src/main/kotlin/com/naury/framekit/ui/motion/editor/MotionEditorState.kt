package com.naury.framekit.ui.motion.editor

import com.naury.framekit.android.result.EditorErrorCode
import com.naury.framekit.core.effect.AdjustmentKind
import com.naury.framekit.core.history.HistoryTransaction
import com.naury.framekit.core.overlay.EmojiCatalog
import com.naury.framekit.motion.model.MotionProject
import com.naury.framekit.ui.motion.contract.MotionTool

internal sealed interface MotionEditorUiState {

    /** 시스템 사진 선택기를 열어야 한다. */
    data class AwaitingSource(val maxItems: Int) : MotionEditorUiState

    data object Loading : MotionEditorUiState

    data class LoadFailed(val code: EditorErrorCode, val canChooseAnother: Boolean) : MotionEditorUiState

    /**
     * @property positionMs 미리보기 재생 위치(밀리초).
     * @property selectedSlide 슬라이드쇼에서 고른 사진 번호. 순서 바꾸기·빼기의 대상이다.
     */
    data class Ready(
        val transaction: HistoryTransaction<MotionProject>,
        val sourceDurationMs: Long,
        val activeTool: MotionTool? = null,
        val playing: Boolean = true,
        val positionMs: Long = 0,
        val selectedOverlayId: String? = null,
        val editingTextId: String? = null,
        val adjustKind: AdjustmentKind = AdjustmentKind.BRIGHTNESS,
        val stickerCategory: EmojiCatalog.Category? = EmojiCatalog.Category.SMILEYS,
        val selectedSlide: Int = 0,
        val export: MotionExportUiState? = null,
        val showSaveSheet: Boolean = false,
        val showDraftDialog: Boolean = false,
        val showDiscardDialog: Boolean = false,
        val notice: MotionNotice? = null,
        val audioSources: Map<String, MotionAudioSource> = emptyMap(),
        val busy: Boolean = false,
        val selectedAudioId: String? = null,
    ) : MotionEditorUiState {
        val displayed: MotionProject get() = transaction.displayed
        val isDirty: Boolean get() = transaction.history.isDirty
    }
}

internal sealed interface MotionExportUiState {
    data class Running(val progress: Float, val cancelling: Boolean = false) : MotionExportUiState
    data class Failed(val code: EditorErrorCode) : MotionExportUiState
}

/** 배경 음악·음성 원본 정보. [durationMs]는 원본 길이(밀리초). */
internal data class MotionAudioSource(val name: String?, val durationMs: Long)

/** 한 번 띄우는 안내. */
internal enum class MotionNotice {
    /** 넣을 수 있는 최대 사진 수를 넘어 일부만 넣었다. */
    SLIDE_LIMIT,

    /** 움직이는 원본의 프레임이 많아 앞부분만 읽었다. */
    FRAMES_TRIMMED,

    /** 소리 파일을 열 수 없었다. */
    AUDIO_FAILED,

    /** 프로세스가 끝났다가 돌아와 이전 편집을 이어 왔다. */
    RESTORED,
}
