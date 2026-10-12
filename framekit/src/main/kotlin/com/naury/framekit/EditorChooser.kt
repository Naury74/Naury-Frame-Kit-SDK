package com.naury.framekit

/** 원본을 받은 뒤 편집 방식을 정하는 방법. */
public enum class EditorChooser {
    /** 원본 종류를 보고 알맞은 편집기를 바로 연다(사진→사진 편집, 영상→영상 편집, 움직이는 GIF·WebP→움직이는 이미지 편집). */
    AUTO,

    /** 원본에 맞는 선택지를 하단 시트로 보여 주고 사용자가 고르게 한다. 고를 수 있는 것이 하나뿐이면 묻지 않고 바로 연다. */
    ASK,
}

/**
 * 선택 시트에 보여 줄 수 있는 동작. 원본에 맞지 않는 동작은 자동으로 빠진다.
 */
public enum class EditorAction {
    /** 사진 편집(여러 장이면 한 장씩 편집하고 사진마다 저장). 움직이는 이미지는 첫 장면만 편집한다. */
    EDIT_PHOTO,

    /** 사진들을 PDF 하나로 묶는다(사진 편집기에서 편집 후 PDF로 저장). */
    MAKE_PDF,

    /** 사진으로 움직이는 이미지를 만들거나, 움직이는 GIF·WebP를 편집한다. */
    MOTION,

    /** 영상 편집. */
    EDIT_VIDEO,

    /** 음성 편집(소리 파일 자르기·나누기·녹음 끼워 넣기). */
    EDIT_AUDIO,

    /** 편집하지 않고 원본을 그대로 돌려준다. 결과의 [com.naury.framekit.android.result.EditedMedia.isOriginal]이 `true`다. */
    SEND_ORIGINAL,
}
