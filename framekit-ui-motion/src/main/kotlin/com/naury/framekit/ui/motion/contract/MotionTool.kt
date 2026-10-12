package com.naury.framekit.ui.motion.contract

/** 움직이는 이미지 편집기의 도구. */
public enum class MotionTool {
    /** 사진의 카메라 움직임(확대·이동). 슬라이드쇼에만 보인다. */
    MOTION,

    /** 사진 사이 전환. 사진이 두 장 이상일 때만 보인다. */
    TRANSITION,

    /** 사진당 시간, 또는 움직이는 원본의 구간·속도. */
    TIMING,

    /** 반복 방식(앞으로·부메랑). */
    LOOP,

    /** 화면 비율과 채우기 방식. */
    CANVAS,
    ADJUST,
    FILTER,
    TEXT,
    STICKER,

    /** 배경 음악·음성. MP4로 저장할 때만 담긴다. */
    AUDIO,
}
