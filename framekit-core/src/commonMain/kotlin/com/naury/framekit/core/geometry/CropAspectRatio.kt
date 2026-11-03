package com.naury.framekit.core.geometry

/** 자르기 도구의 비율 제약. 프로젝트에는 결과 사각형만 저장된다. */
public sealed interface CropAspectRatio {
    /** 제약 없음. 초기·초기화 자르기는 이미지 자체의 비율을 쓴다. */
    public data object Free : CropAspectRatio

    /** 90° 회전 이후, 수평 보정 이전의 원본 비율. */
    public data object Original : CropAspectRatio

    /**
     * 출력 픽셀 기준의 고정 가로:세로 비율.
     *
     * @throws IllegalArgumentException 어느 한 변이라도 양수가 아닐 때.
     */
    public data class Fixed(val width: Int, val height: Int) : CropAspectRatio {
        init {
            require(width > 0 && height > 0) { "Aspect sides must be positive: $width:$height" }
        }

        val ratio: Double get() = width.toDouble() / height.toDouble()
    }

    public companion object {
        /** 기본 자르기 패널이 제공하는 비율. 표시 순서대로다. */
        public val presets: List<CropAspectRatio> = listOf(
            Free,
            Original,
            Fixed(1, 1),
            Fixed(4, 3),
            Fixed(3, 4),
            Fixed(16, 9),
            Fixed(9, 16),
            Fixed(3, 2),
            Fixed(2, 3),
        )
    }
}
