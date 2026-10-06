package com.naury.framekit.core.model

/**
 * 물리 픽셀 단위 크기.
 *
 * 잘못된 호스트 입력을 생성 시 크래시 대신 검증으로 보고할 수 있도록 0이나 음수 값도 담을 수 있다.
 * 산술 연산 전에 [isValid]를 확인한다.
 */
public data class PixelSize(val width: Int, val height: Int) {
    /** 두 치수가 모두 양수이면 `true`. */
    public val isValid: Boolean get() = width > 0 && height > 0

    /** 큰 이미지에서도 오버플로하지 않도록 [Long]으로 표현한 총 픽셀 수. */
    public val pixelCount: Long get() = width.toLong() * height.toLong()

    /** 너비를 높이로 나눈 값. [isValid]일 때만 의미가 있다. */
    public val aspectRatio: Double get() = width.toDouble() / height.toDouble()

    /** 90°나 270° 회전 결과처럼 너비와 높이를 맞바꾼 크기. */
    public fun transposed(): PixelSize = PixelSize(height, width)
}
