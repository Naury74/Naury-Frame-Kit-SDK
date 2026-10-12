package com.naury.framekit.ui.audioeditor.editor

import com.naury.framekit.android.result.EditorErrorCode
import com.naury.framekit.core.audio.AudioProject
import com.naury.framekit.core.history.HistoryTransaction
import com.naury.framekit.core.video.AudioClip
import com.naury.framekit.core.video.TimeRangeUs

/** 소리 원본 정보. [durationUs]는 원본 길이(µs). */
internal data class AudioSource(val name: String?, val durationUs: Long)

/** 녹음한 소리를 넣을 자리. */
internal enum class RecordTarget {
    /** 재생 위치에 끼워 넣고 뒤를 민다. */
    INSERT,

    /** 재생 위치에 겹쳐 넣는다(겹친 줄). */
    OVERLAY,
}

internal sealed interface AudioEditorUiState {

    /** 시스템 파일 선택기를 열어야 한다. */
    data class AwaitingPick(val maxItems: Int) : AudioEditorUiState

    /** 처음 녹음 화면(편집할 소리가 아직 없음). */
    data object Recording : AudioEditorUiState

    data object Loading : AudioEditorUiState

    data class LoadFailed(val code: EditorErrorCode, val canRetry: Boolean) : AudioEditorUiState

    /**
     * @property positionUs 재생 위치(µs).
     * @property selectedClipId 고른 조각. 아래에 나누기·삭제·볼륨·페이드가 나온다.
     * @property range 구간 편집 중이면 고른 구간.
     * @property recordSheet 녹음 시트를 열었으면 넣을 자리.
     */
    data class Ready(
        val transaction: HistoryTransaction<AudioProject>,
        val sources: Map<String, AudioSource>,
        val playing: Boolean = false,
        val positionUs: Long = 0,
        val selectedClipId: String? = null,
        val range: TimeRangeUs? = null,
        val recordSheet: RecordTarget? = null,
        val export: AudioExportUiState? = null,
        val busy: Boolean = false,
        val showDiscardDialog: Boolean = false,
        val notice: AudioNotice? = null,
    ) : AudioEditorUiState {
        val project: AudioProject get() = transaction.displayed
        val selectedClip: AudioClip? get() = project.clips.firstOrNull { it.id == selectedClipId }
        val isDirty: Boolean get() = transaction.history.isDirty
    }
}

internal sealed interface AudioExportUiState {
    data class Running(val progress: Float, val cancelling: Boolean = false) : AudioExportUiState
    data class Failed(val code: EditorErrorCode) : AudioExportUiState
}

internal enum class AudioNotice {
    /** 소리 파일을 열 수 없었다. */
    ADD_FAILED,

    /** 녹음이 너무 짧아 버렸다. */
    TOO_SHORT,

    /** 너무 끝이라 나눌 수 없다. */
    SPLIT_UNAVAILABLE,

    /** 프로세스가 끝났다가 돌아와 이전 편집을 이어 왔다. */
    RESTORED,
}
