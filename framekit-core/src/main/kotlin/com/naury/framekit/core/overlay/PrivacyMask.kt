package com.naury.framekit.core.overlay

import com.naury.framekit.core.geometry.PointN
import com.naury.framekit.core.geometry.RectN

/** 출력 캔버스(C) 좌표 기준으로 가리기 마스크가 덮는 영역. */
public sealed interface MaskShape {
    /** 자유 브러시. [widthShortEdgeRatio]는 캔버스 짧은 변에 대한 브러시 지름 비율이다. */
    public data class Brush(val points: List<PointN>, val widthShortEdgeRatio: Double) : MaskShape

    public data class Rectangle(val rect: RectN) : MaskShape

    /** [rect]에 내접하는 타원. */
    public data class Ellipse(val rect: RectN) : MaskShape
}

/** 가리기 마스크가 아래 내용을 감추는 방식. */
public sealed interface PrivacyEffect {
    /**
     * Gaussian blur. 블러는 복원될 수 있으므로 민감한 정보에는 [Mosaic]이나 불투명 도형을
     * 사용한다.
     *
     * @property radiusShortEdgeRatio 캔버스 짧은 변에 대한 Gaussian sigma 비율.
     */
    public data class Blur(val radiusShortEdgeRatio: Double = 0.015) : PrivacyEffect

    /**
     * 캔버스 원점에 고정된 격자로 픽셀화한다.
     *
     * @property blockShortEdgeRatio 캔버스 짧은 변에 대한 블록 한 변의 비율.
     */
    public data class Mosaic(val blockShortEdgeRatio: Double = 0.03) : PrivacyEffect

    public companion object {
        public const val MAX_RATIO: Double = 0.2
    }
}

/**
 * 이미지 일부에 적용하는 블러 또는 모자이크. 색상 보정된 사진에만 적용되며 텍스트, 스티커, 그리기는
 * 그 위에 선명하게 남는다. 겹친 마스크는 모두 마스크 적용 전 사진을 읽으므로 가장자리가 두 번
 * 블러되지 않는다.
 */
public data class PrivacyMask(val id: String, val shape: MaskShape, val effect: PrivacyEffect)
