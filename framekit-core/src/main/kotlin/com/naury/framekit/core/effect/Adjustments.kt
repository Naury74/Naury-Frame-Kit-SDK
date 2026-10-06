package com.naury.framekit.core.effect

/**
 * 보정 슬라이더 하나.
 *
 * @property minimum 모델 값의 하한.
 * @property maximum 모델 값의 상한.
 * @property signed 슬라이더가 0을 중심으로 하면(UI에서 `-100..100`) `true`, 0에서 시작하는 양(`0..100`)이면
 *   `false`.
 */
public enum class AdjustmentKind(public val minimum: Double, public val maximum: Double, public val signed: Boolean) {
    BRIGHTNESS(-1.0, 1.0, true),

    /** 노출(EV 단위). UI의 `-100..100`을 `-2..2`로 매핑한다. */
    EXPOSURE(-2.0, 2.0, true),
    CONTRAST(-1.0, 1.0, true),
    HIGHLIGHTS(-1.0, 1.0, true),
    SHADOWS(-1.0, 1.0, true),
    SATURATION(-1.0, 1.0, true),
    TEMPERATURE(-1.0, 1.0, true),
    TINT(-1.0, 1.0, true),
    SHARPNESS(0.0, 1.0, false),
    FADE(0.0, 1.0, false),
    VIGNETTE(0.0, 1.0, false),
    GRAIN(0.0, 1.0, false),
    ;

    /** 슬라이더가 표시하는 최솟값. 부호 있는 종류는 `-100`, 그 외는 `0`이다. */
    public val displayMinimum: Int get() = if (signed) -DISPLAY_RANGE else 0

    /** 모델 값을 슬라이더 값으로 변환한다. */
    public fun toDisplay(value: Double): Int = Math.round(value / maximum * DISPLAY_RANGE).toInt()

    /** 슬라이더 값을 모델 값으로 변환하고 모델 범위로 제한한다. */
    public fun fromDisplay(display: Float): Double =
        (display.toDouble() / DISPLAY_RANGE * maximum).coerceIn(minimum, maximum)

    public companion object {
        /** 슬라이더 폭. 모든 표시 값은 이 하나의 매핑을 따른다. */
        public const val DISPLAY_RANGE: Int = 100
    }
}

/**
 * 색상·톤 보정. 모든 값의 기본값은 0이며, 이때 이미지는 바뀌지 않는다.
 *
 * 범위는 [AdjustmentKind]에 정리되어 있다. 사용자가 슬라이더를 움직인 순서와 관계없이 값은
 * [ColorEffectSpec]이 정한 고정 순서로 적용된다.
 */
public data class Adjustments(
    val brightness: Double = 0.0,
    val exposure: Double = 0.0,
    val contrast: Double = 0.0,
    val highlights: Double = 0.0,
    val shadows: Double = 0.0,
    val saturation: Double = 0.0,
    val temperature: Double = 0.0,
    val tint: Double = 0.0,
    val sharpness: Double = 0.0,
    val fade: Double = 0.0,
    val vignette: Double = 0.0,
    val grain: Double = 0.0,
) {
    public val isIdentity: Boolean get() = this == Adjustments()

    public operator fun get(kind: AdjustmentKind): Double = when (kind) {
        AdjustmentKind.BRIGHTNESS -> brightness
        AdjustmentKind.EXPOSURE -> exposure
        AdjustmentKind.CONTRAST -> contrast
        AdjustmentKind.HIGHLIGHTS -> highlights
        AdjustmentKind.SHADOWS -> shadows
        AdjustmentKind.SATURATION -> saturation
        AdjustmentKind.TEMPERATURE -> temperature
        AdjustmentKind.TINT -> tint
        AdjustmentKind.SHARPNESS -> sharpness
        AdjustmentKind.FADE -> fade
        AdjustmentKind.VIGNETTE -> vignette
        AdjustmentKind.GRAIN -> grain
    }

    public fun with(kind: AdjustmentKind, value: Double): Adjustments = when (kind) {
        AdjustmentKind.BRIGHTNESS -> copy(brightness = value)
        AdjustmentKind.EXPOSURE -> copy(exposure = value)
        AdjustmentKind.CONTRAST -> copy(contrast = value)
        AdjustmentKind.HIGHLIGHTS -> copy(highlights = value)
        AdjustmentKind.SHADOWS -> copy(shadows = value)
        AdjustmentKind.SATURATION -> copy(saturation = value)
        AdjustmentKind.TEMPERATURE -> copy(temperature = value)
        AdjustmentKind.TINT -> copy(tint = value)
        AdjustmentKind.SHARPNESS -> copy(sharpness = value)
        AdjustmentKind.FADE -> copy(fade = value)
        AdjustmentKind.VIGNETTE -> copy(vignette = value)
        AdjustmentKind.GRAIN -> copy(grain = value)
    }
}
