package com.naury.framekit.core.model

/** 편집기가 다루거나 내보내기가 만드는 미디어 종류. */
public enum class MediaType {
    IMAGE,
    VIDEO,

    /** 소리(음성·음악). 영상 편집에서는 배경 음악 원본이고, 음성 편집기의 결과이기도 하다. */
    AUDIO,

    /** 사진을 쪽으로 묶은 PDF 문서. */
    DOCUMENT,

    /** 움직이는 이미지(GIF·움직이는 WebP). MP4로 만든 움직이는 이미지는 [VIDEO]다. */
    ANIMATED_IMAGE,
}
