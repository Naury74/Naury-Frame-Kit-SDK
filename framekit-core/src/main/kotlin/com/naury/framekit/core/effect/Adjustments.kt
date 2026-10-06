package com.naury.framekit.core.effect

/**
 * One adjustment slider.
 *
 * @property minimum lower bound of the model value.
 * @property maximum upper bound of the model value.
 * @property signed `true` when the slider is centered on zero (`-100..100` in the UI), `false` for
 *   amounts that start at zero (`0..100`).
 */
public enum class AdjustmentKind(public val minimum: Double, public val maximum: Double, public val signed: Boolean) {
    BRIGHTNESS(-1.0, 1.0, true),

    /** Exposure in EV stops; the UI maps `-100..100` to `-2..2`. */
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

    /** Lowest value the slider shows: `-100` for signed kinds, `0` otherwise. */
    public val displayMinimum: Int get() = if (signed) -DISPLAY_RANGE else 0

    /** Converts a model value to the slider value. */
    public fun toDisplay(value: Double): Int = Math.round(value / maximum * DISPLAY_RANGE).toInt()

    /** Converts a slider value to the model value, clamped to the model range. */
    public fun fromDisplay(display: Float): Double =
        (display.toDouble() / DISPLAY_RANGE * maximum).coerceIn(minimum, maximum)

    public companion object {
        /** Slider span. Every display value lives in this single mapping. */
        public const val DISPLAY_RANGE: Int = 100
    }
}

/**
 * Color and tone adjustments. All values are 0 by default, which leaves the image unchanged.
 *
 * Ranges are listed in [AdjustmentKind]. Values are applied in a fixed order defined by
 * [ColorEffectSpec], regardless of the order in which the user moved the sliders.
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
