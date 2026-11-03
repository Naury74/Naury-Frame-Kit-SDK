package com.naury.framekit.core.overlay

/** 워터마크를 놓는 방식. */
public enum class WatermarkPattern {
    /** 한 군데에 하나. [Watermark.position]의 아홉 자리 중 하나에 둔다. */
    SINGLE,

    /** 화면 전체에 벽돌처럼 엇갈려 반복한다. [Watermark.rotationDegrees]만큼 기울이고 [Watermark.density]로 간격을 정한다. */
    TILE,

    /** 왼쪽 아래에서 오른쪽 위로 가로지르는 큰 글자 하나. 크기는 대각선 길이에 맞춘다. */
    DIAGONAL,

    /** 위나 아래 가장자리에 화면 폭만큼 반투명 띠를 깔고 그 위에 글자를 둔다. */
    BAND,
}

/** [WatermarkPattern.SINGLE]·[WatermarkPattern.BAND]의 자리. 띠는 위·아래만 보고, 가로는 글자 정렬로 쓴다. */
public enum class WatermarkPosition(public val horizontal: Int, public val vertical: Int) {
    TOP_START(-1, -1),
    TOP_CENTER(0, -1),
    TOP_END(1, -1),
    CENTER_START(-1, 0),
    CENTER(0, 0),
    CENTER_END(1, 0),
    BOTTOM_START(-1, 1),
    BOTTOM_CENTER(0, 1),
    BOTTOM_END(1, 1),
}

/**
 * 사진·영상 전체에 얹는 글자 워터마크. 크기는 출력의 짧은 변에 대한 비율이라 어떤 해상도로 저장해도 같아 보인다.
 * 자르기·회전 뒤의 출력 화면 기준으로 그리며, 다른 오버레이보다 위에 놓인다.
 *
 * @property text 글자. 앞뒤 공백을 뺀 1..[MAX_TEXT_LENGTH]자, 한 줄.
 * @property sizeRatio 글자 높이를 짧은 변으로 나눈 값([MIN_SIZE]..[MAX_SIZE]). [WatermarkPattern.DIAGONAL]은 쓰지 않는다.
 * @property opacity 불투명도 `0.05..1`.
 * @property colorArgb 글자 색.
 * @property rotationDegrees [WatermarkPattern.TILE]의 기울기(도, `-90..90`, 음수는 반시계).
 * @property density [WatermarkPattern.TILE]의 촘촘함(`0.5..2`, 클수록 촘촘).
 * @property shadow 밝은 곳·어두운 곳 어디서나 읽히도록 옅은 그림자를 넣는다.
 * @property background 글자 뒤에 둥근 반투명 상자를 깐다([WatermarkPattern.SINGLE]·[WatermarkPattern.TILE]).
 * @property fontId 폰트 카탈로그의 폰트 id.
 */
public data class Watermark(
    val text: String,
    val pattern: WatermarkPattern = WatermarkPattern.SINGLE,
    val position: WatermarkPosition = WatermarkPosition.BOTTOM_END,
    val sizeRatio: Double = DEFAULT_SIZE,
    val opacity: Double = 0.7,
    val colorArgb: Int = 0xFFFFFFFF.toInt(),
    val rotationDegrees: Double = -30.0,
    val density: Double = 1.0,
    val shadow: Boolean = true,
    val background: Boolean = false,
    val fontId: String = "sans-bold",
) {
    /** 저장할 수 있는 값인지. 범위를 벗어난 값은 [normalized]로 맞출 수 있다. */
    public val isValid: Boolean
        get() = text.isNotBlank() && text.trim().length <= MAX_TEXT_LENGTH && '\n' !in text &&
            sizeRatio in MIN_SIZE..MAX_SIZE && opacity in MIN_OPACITY..1.0 &&
            rotationDegrees in -90.0..90.0 && density in MIN_DENSITY..MAX_DENSITY

    /** 글자를 한 줄로 다듬고 숫자 값을 허용 범위로 맞춘다. 글자가 비면 그대로 둔다(유효하지 않음). */
    public fun normalized(): Watermark = copy(
        text = text.replace('\n', ' ').trim().take(MAX_TEXT_LENGTH),
        sizeRatio = sizeRatio.coerceIn(MIN_SIZE, MAX_SIZE),
        opacity = opacity.coerceIn(MIN_OPACITY, 1.0),
        rotationDegrees = rotationDegrees.coerceIn(-90.0, 90.0),
        density = density.coerceIn(MIN_DENSITY, MAX_DENSITY),
    )

    public companion object {
        public const val MAX_TEXT_LENGTH: Int = 60
        public const val MIN_SIZE: Double = 0.015
        public const val MAX_SIZE: Double = 0.2
        public const val DEFAULT_SIZE: Double = 0.04
        public const val MIN_OPACITY: Double = 0.05
        public const val MIN_DENSITY: Double = 0.5
        public const val MAX_DENSITY: Double = 2.0
    }
}
