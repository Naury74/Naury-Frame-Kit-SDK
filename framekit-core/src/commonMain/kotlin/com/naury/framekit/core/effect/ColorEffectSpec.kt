package com.naury.framekit.core.effect

import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * 색상 파이프라인의 확정 상수. 렌더러(CPU 기준 구현과 GPU)는 이것만 읽으므로 둘 다 정확히 같은
 * 숫자를 적용한다.
 *
 * 순서, 버전 [VERSION]:
 * 1. linear sRGB: 노출(`× 2^EV`), 화이트 밸런스(temperature/tint 게인, 휘도 보존)
 * 2. display sRGB: 그림자/하이라이트, 밝기, 대비, 채도, 필터 프리셋(intensity로 혼합) 후
 *    clamp하고 8비트로 양자화
 * 3. 공간·캔버스 효과: 선명도(unsharp mask), fade, vignette, grain
 *
 * vignette와 grain은 정규화된 출력 캔버스 좌표로 배치하고 샤프닝 반경은 짧은 변에 대한 비율이므로,
 * 크기가 다른 미리보기와 내보내기 결과가 일치한다.
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
    /** 채널별 배율. */
    public data class Gains(val red: Float, val green: Float, val blue: Float)

    /** [FilterGrade]를 보정과 같은 종류의 상수로 바꾼 값. */
    public data class ResolvedGrade(
        val gains: Gains,
        val brightnessOffset: Float,
        val contrastFactor: Float,
        val saturationFactor: Float,
        val shadowTint: Gains,
        val highlightTint: Gains,
        val fadeLift: Float,
    )

    /** 파이프라인이 입력을 그대로 반환하면 `true`. */
    public val isIdentity: Boolean
        get() = exposureGain == 1f && whiteBalance == NEUTRAL && shadows == 0f && highlights == 0f &&
            brightnessOffset == 0f && contrastFactor == 1f && saturationFactor == 1f &&
            (grade == null || filterIntensity == 0f) && !hasSpatialEffects

    /** 선명도, fade, vignette, grain 중 하나라도 활성이면 `true`. */
    public val hasSpatialEffects: Boolean
        get() = sharpenAmount != 0f || fadeLift != 0f || vignetteStrength != 0f || grainAmount != 0f

    /** 짧은 변이 [shortEdgePx]인 출력에 대한 Gaussian sigma(픽셀). */
    public fun sharpenSigma(shortEdgePx: Int): Float = max(MIN_SIGMA_PX, SHARPEN_SIGMA_SHORT_EDGE_RATIO * shortEdgePx)

    /** 중앙 탭부터 바깥쪽 순서의 정규화된 1D Gaussian 가중치. 두 렌더러가 모두 이것을 쓴다. */
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

        /** 캔버스 짧은 변에 걸친 grain 칸 수. */
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
