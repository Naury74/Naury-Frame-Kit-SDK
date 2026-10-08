package com.naury.framekit.ui.image.contract

/** 이미지 에디터의 도구. 편집이 실제로 렌더링되고 내보내지는 도구만 나열한다. */
public enum class ImageTool(internal val isDraft: Boolean) {
    /** 자유 또는 고정 비율 자르기. 적용 전까지 변경은 초안이다. */
    CROP(isDraft = true),

    /** 90° 회전, 좌우·상하 뒤집기, 최대 ±45° 수평 보정. 적용 전까지 변경은 초안이다. */
    ROTATE(isDraft = true),

    /**
     * 문서 보정. 사진 속 종이의 네 모서리를 찾아(직접 맞출 수도 있음) 비스듬한 문서를 반듯하게 편다.
     * 적용하면 펴낸 이미지가 그 쪽의 새 원본이 되며, 보정·필터는 이어지고 "원본으로"로 되돌릴 수 있다.
     */
    DOCUMENT(isDraft = false),

    /** 색상·톤 보정 12종. 슬라이더 드래그 한 번이 실행 취소 한 단계로 커밋된다. */
    ADJUST(isDraft = false),

    /** 강도를 조절할 수 있는 필터 프리셋. 선택 한 번 또는 강도 드래그 한 번이 실행 취소 한 단계다. */
    FILTER(isDraft = false),

    /** 글꼴, 색상, 정렬, 외곽선, 배경을 지정하는 텍스트. 편집 세션 하나가 실행 취소 한 단계다. */
    TEXT(isDraft = true),

    /** 표준 이모지 스티커. 추가와 각 이동·크기 조절·회전이 각각 실행 취소 한 단계다. */
    STICKER(isDraft = false),

    /** 펜, 마커, 형광펜, 지우개. 획 하나가 실행 취소 한 단계다. */
    DRAW(isDraft = false),

    /** 브러시·사각형·타원으로 적용하는 모자이크와 블러. 마스크 하나가 실행 취소 한 단계다. */
    PRIVACY(isDraft = false),

    /**
     * 배경 제거. 선택 아티팩트 `framekit-segmentation`이 설치된 경우에만 표시되며,
     * 배경 제거와 복원은 각각 실행 취소 한 단계다.
     */
    CUTOUT(isDraft = false),
    ;

    /** 잘린 결과 대신 회전된 이미지 전체를 보여주는 도구. */
    internal val isGeometry: Boolean get() = this == CROP || this == ROTATE

    public companion object {
        /** 이 버전에서 사용할 수 있는 도구, 레일 순서대로. */
        public val defaults: Set<ImageTool> = linkedSetOf(CROP, ROTATE, DOCUMENT, CUTOUT, ADJUST, FILTER, TEXT, STICKER, DRAW, PRIVACY)
    }
}
