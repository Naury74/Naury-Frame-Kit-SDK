package com.naury.framekit.ui.video.contract

/** 영상 에디터의 도구. 편집이 실제로 재생되고 내보내지는 도구만 나열한다. */
public enum class VideoTool {
    TRIM,
    CROP,
    ROTATE,
    CANVAS,
    ADJUST,
    FILTER,
    SPEED,
    AUDIO,
    TEXT,
    STICKER,
    PRIVACY,
    ;

    /** 초안 도구는 통째로 적용하거나 취소하고, 나머지는 변경마다 커밋한다. */
    internal val isDraft: Boolean get() = this == TRIM || this == CROP || this == ROTATE || this == SPEED || this == TEXT

    /** 기하 도구는 회전된 프레임 전체 위에 자르기 프레임을 표시한다. */
    internal val isGeometry: Boolean get() = this == CROP || this == ROTATE

    public companion object {
        public val defaults: Set<VideoTool> = entries.toSet()
    }
}
