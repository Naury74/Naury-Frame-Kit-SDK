package com.naury.framekit.core.document

/** 문서를 편 뒤 적용하는 스캔 보정. */
public enum class ScanMode {
    /** 조명 얼룩·그림자를 없애고 배경을 하얗게, 색은 살린다. 도장·형광펜이 있는 문서에 알맞다. */
    COLOR,

    /** [COLOR]와 같게 정리한 뒤 흑백으로. 일반 인쇄 문서에 알맞다. */
    GRAYSCALE,

    /** 글자만 남기는 강한 흑백. 팩스·복사본처럼 보이며 파일이 가장 작다. */
    BLACK_WHITE,

    /** 펴기만 하고 색은 손대지 않는다. */
    ORIGINAL,
}
