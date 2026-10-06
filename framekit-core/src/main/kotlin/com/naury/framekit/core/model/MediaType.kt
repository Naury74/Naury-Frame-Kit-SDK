package com.naury.framekit.core.model

/** 편집기가 다루거나 내보내기가 만드는 미디어 종류. */
public enum class MediaType {
    IMAGE,
    VIDEO,

    /** 영상의 배경 음악 원본. 편집 결과로는 나오지 않는다. */
    AUDIO,

    /** 사진을 쪽으로 묶은 PDF 문서. */
    DOCUMENT,
}
