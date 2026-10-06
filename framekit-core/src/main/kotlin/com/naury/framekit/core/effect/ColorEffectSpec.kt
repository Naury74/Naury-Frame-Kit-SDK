package com.naury.framekit.core.effect

import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Resolved constants of the color pipeline. Renderers (CPU reference and GPU) read only this, so both
 * apply exactly the same numbers.
 *
 * Order, version [VERSION]:
 * 1. linear sRGB: exposure (`× 2^EV`), white balance (temperature/tint gains, luminance preserving)
 * 2. display sRGB: shadows/highlights, brightness, contrast, saturation, filter preset (mixed by
 *    intensity), then clamp and quantize to 8 bits
 * 3. spatial and canvas effects: sharpness (unsharp mask), fade, vignette, grain
 *
 * Vignette and grain are positioned in normalized output-canvas coordinates and the sharpening
 * radius is a fraction of the short edge, so preview and export at different sizes match.
 */
public data class ColorEffectSpec(
    val exposureGain: Float,
    val whiteBalance: Gains,
    val shadows: Float,
    val highlights: Float,
    val brightnessOffset: Float,
    val contrastFactor: Float,
    val saturationFactor: Float,
    val grade: ResolvedGrade?,
    val filterIntensity: Float,
    val sharpenAmount: Float,
    val fadeLift: Float,
    val vignetteStrength: Float,
    val grainAmount: Float,
    val grainSeed: Int,
) {
    /** Per-channel multipliers. */
    public data class Gains(val red: Float, val green: Float, val blue: Float)

    /** [FilterGrade] turned into the same kind of constants as the adjustments. */
    public data class ResolvedGrade(
        val gains: Gains,
        val brightnessOffset: Float,
        val contrastFactor: Float,
        val saturationFactor: Float,
        val shadowTint: Gains,
        val highlightTint: Gains,
        val fadeLift: Float,
    )

    /** `true` when the pipeline returns the input unchanged. */
    public val isIdentity: Boolean
        get() = exposureGain == 1f && whiteBalance == NEUTRAL && shadows == 0f && highlights == 0f &&
            brightnessOffset == 0f && contrastFactor == 1f && saturationFactor == 1f &&
            (grade == null || filterIntensity == 0f) && !hasSpatialEffects

    /** `true` when sharpness, fade, vignette or grain are active. */
    public val hasSpatialEffects: Boolean
        get() = sharpenAmount != 0f || fadeLift != 0f || vignetteStrength != 0f || grainAmount != 0f

    /** Gaussian sigma in pixels for an output whose short edge is [shortEdgePx]. */
    public fun sharpenSigma(shortEdgePx: Int): Float = max(MIN_SIGMA_PX, SHARPEN_SIGMA_SHORT_EDGE_RATIO * shortEdgePx)

    /** Normalized 1D Gaussian weights from the center tap outwards; both renderers use these. */
    public fun sharpenWeights(shortEdgePx: Int): FloatArray {
        val sigma = sharpenSigma(shortEdgePx)
        val radius = min(MAX_BLUR_RADIUS, ceil(3f * sigma).toInt())
        val weights = FloatArray(radius + 1) { i -> exp(-(i * i) / (2f * sigma * sigma)) }
        val total = weights[0] + 2f * weights.drop(1).sum()
        for (i in weights.indices) weights[i] /= total
        return weights
    }

    public companion object {
        public const val VERSION: Int = 1
        public const val MAX_BLUR_RADIUS: Int = 48
        internal const val SHARPEN_SIGMA_SHORT_EDGE_RATIO = 0.002f
        internal const val MIN_SIGMA_PX = 0.6f
        internal val NEUTRAL = Gains(1f, 1f, 1f)

        /** Grain cells across the short edge of the canvas. */
        public const val GRAIN_CELLS_SHORT_EDGE: Int = 720

        public fun of(adjustments: Adjustments, filter: FilterSelection, grainSeed: Long): ColorEffectSpec {
            val preset = FilterCatalog.find(filter.presetId)?.takeIf { !filter.isIdentity && it.id != FilterCatalog.ORIGINAL_ID }
            return ColorEffectSpec(
                exposureGain = 2.0.pow(adjustments.exposure).toFloat(),
                whiteBalance = whiteBalance(adjustments.temperature, adjustments.tint, TEMPERATURE_SCALE, TINT_SCALE),
                shadows = adjustments.shadows.toFloat(),
                highlights = adjustments.highlights.toFloat(),
                brightnessOffset = (BRIGHTNESS_SCALE * adjustments.brightness).toFloat(),
                contrastFactor = contrastFactor(adjustments.contrast),
                saturationFactor = (1.0 + adjustments.saturation).toFloat(),
                grade = preset?.grade?.let(::resolve),
                filterIntensity = if (preset == null) 0f else filter.intensity.toFloat(),
                sharpenAmount = (SHARPEN_SCALE * adjustments.sharpness).toFloat(),
                fadeLift = (FADE_SCALE * adjustments.fade).toFloat(),
                vignetteStrength = adjustments.vignette.toFloat(),
                grainAmount = adjustments.grain.toFloat(),
                grainSeed = (grainSeed xor (grainSeed ushr 32)).toInt(),
            )
        }

        private fun resolve(grade: FilterGrade) = ResolvedGrade(
            gains = whiteBalance(grade.temperature, grade.tint, GRADE_TEMPERATURE_SCALE, GRADE_TINT_SCALE),
            brightnessOffset = (BRIGHTNESS_SCALE * grade.brightness).toFloat(),
            contrastFactor = contrastFactor(grade.contrast),
            saturationFactor = (1.0 + grade.saturation).toFloat(),
            shadowTint = Gains(grade.shadowTint.red.toFloat(), grade.shadowTint.green.toFloat(), grade.shadowTint.blue.toFloat()),
            highlightTint = Gains(grade.highlightTint.red.toFloat(), grade.highlightTint.green.toFloat(), grade.highlightTint.blue.toFloat()),
            fadeLift = (FADE_SCALE * grade.fade).toFloat(),
        )

        // 따뜻함은 R↑B↓, tint 양수는 마젠타(G↓). 흰색의 밝기가 변하지 않도록 luminance로 정규화한다.
        private fun whiteBalance(temperature: Double, tint: Double, temperatureScale: Double, tintScale: Double): Gains {
            val r = 1.0 + temperatureScale * temperature
            val g = 1.0 - tintScale * tint
            val b = 1.0 - temperatureScale * temperature
            val k = 1.0 / (LUMA_R * r + LUMA_G * g + LUMA_B * b)
            return Gains((r * k).toFloat(), (g * k).toFloat(), (b * k).toFloat())
        }

        private fun contrastFactor(contrast: Double): Float {
            val a = contrast.coerceIn(-0.95, 0.95)
            return ((1 + a) / (1 - a)).toFloat()
        }

        internal const val LUMA_R = 0.2126
        internal const val LUMA_G = 0.7152
        internal const val LUMA_B = 0.0722
        private const val TEMPERATURE_SCALE = 0.25
        private const val TINT_SCALE = 0.2
        private const val GRADE_TEMPERATURE_SCALE = 0.1
        private const val GRADE_TINT_SCALE = 0.08
        private const val BRIGHTNESS_SCALE = 0.25
        private const val SHARPEN_SCALE = 1.5
        private const val FADE_SCALE = 0.15
    }
}
