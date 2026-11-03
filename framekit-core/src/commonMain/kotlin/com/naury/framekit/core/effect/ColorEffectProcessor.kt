package com.naury.framekit.core.effect

import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * [ColorEffectSpec]의 CPU 기준 구현.
 *
 * GPU 렌더러를 비교할 기대 출력을 정의하고, OpenGL ES 3.0이 없는 기기에서는 대체 경로로 쓰인다.
 * 픽셀은 premultiply되지 않은 ARGB이며 제자리에서 처리하고, alpha는 유지한다.
 */
public object ColorEffectProcessor {

    /**
     * @param includeCanvasEffects `false`이면 vignette와 grain을 건너뛴다. 최종 캔버스를 보여주지 않는
     *   미리보기(예: 자르기 도구의 자르기 전 화면)용이다.
     */
    public fun process(pixels: IntArray, width: Int, height: Int, spec: ColorEffectSpec, includeCanvasEffects: Boolean = true) {
        require(pixels.size >= width * height) { "Pixel buffer is too small" }
        val rgb = FloatArray(3)
        for (i in 0 until width * height) {
            val argb = pixels[i]
            rgb[0] = SRGB_TO_LINEAR[(argb shr 16) and 0xFF]
            rgb[1] = SRGB_TO_LINEAR[(argb shr 8) and 0xFF]
            rgb[2] = SRGB_TO_LINEAR[argb and 0xFF]
            pointOps(spec, rgb)
            pixels[i] = (argb and ALPHA_MASK) or (quantize(rgb[0]) shl 16) or (quantize(rgb[1]) shl 8) or quantize(rgb[2])
        }
        if (!spec.hasSpatialEffects) return

        val blurred = if (spec.sharpenAmount != 0f) blur(pixels, width, height, spec.sharpenWeights(min(width, height))) else null
        val shortEdge = min(width, height).toFloat()
        for (y in 0 until height) {
            for (x in 0 until width) {
                val i = y * width + x
                val argb = pixels[i]
                for (c in 0..2) rgb[c] = ((argb shr (16 - 8 * c)) and 0xFF) / 255f
                if (blurred != null) {
                    val b = blurred[i]
                    for (c in 0..2) {
                        val base = rgb[c]
                        rgb[c] = base + spec.sharpenAmount * (base - ((b shr (16 - 8 * c)) and 0xFF) / 255f)
                    }
                }
                finishOps(spec, rgb, x, y, width, height, shortEdge, includeCanvasEffects)
                pixels[i] = (argb and ALPHA_MASK) or (quantize(rgb[0]) shl 16) or (quantize(rgb[1]) shl 8) or quantize(rgb[2])
            }
        }
    }

    /** 픽셀 하나에 대한 파이프라인 1·2단계. [rgb]는 linear로 들어와 clamp된 display RGB로 나간다. */
    public fun pointOps(spec: ColorEffectSpec, rgb: FloatArray) {
        val wb = spec.whiteBalance
        rgb[0] = linearToSrgb((rgb[0] * spec.exposureGain * wb.red).coerceIn(0f, 1f))
        rgb[1] = linearToSrgb((rgb[1] * spec.exposureGain * wb.green).coerceIn(0f, 1f))
        rgb[2] = linearToSrgb((rgb[2] * spec.exposureGain * wb.blue).coerceIn(0f, 1f))

        if (spec.shadows != 0f || spec.highlights != 0f) {
            val y = luma(rgb)
            val lift = TONE_SCALE * (spec.shadows * shadowMask(y) + spec.highlights * highlightMask(y))
            for (c in 0..2) rgb[c] += lift
        }
        for (c in 0..2) rgb[c] = (rgb[c] + spec.brightnessOffset - 0.5f) * spec.contrastFactor + 0.5f
        saturate(rgb, spec.saturationFactor)

        val grade = spec.grade
        if (grade != null && spec.filterIntensity > 0f) {
            val r = rgb[0]
            val g = rgb[1]
            val b = rgb[2]
            applyGrade(grade, rgb)
            val t = spec.filterIntensity
            rgb[0] = r + (rgb[0] - r) * t
            rgb[1] = g + (rgb[1] - g) * t
            rgb[2] = b + (rgb[2] - b) * t
        }
        for (c in 0..2) rgb[c] = rgb[c].coerceIn(0f, 1f)
    }

    /** 샤프닝 이후의 3단계: clamp된 display RGB에 fade, vignette, grain을 적용한다. */
    public fun finishOps(
        spec: ColorEffectSpec,
        rgb: FloatArray,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        shortEdge: Float,
        includeCanvasEffects: Boolean,
    ) {
        if (spec.fadeLift != 0f) for (c in 0..2) rgb[c] = rgb[c] * (1f - spec.fadeLift) + spec.fadeLift
        if (!includeCanvasEffects) return
        val u = (x + 0.5f) / width
        val v = (y + 0.5f) / height
        if (spec.vignetteStrength != 0f) {
            val dx = (u - 0.5f) * 2f
            val dy = (v - 0.5f) * 2f
            val d = sqrt(dx * dx + dy * dy) / SQRT2
            val factor = 1f - VIGNETTE_SCALE * spec.vignetteStrength * smoothstep(VIGNETTE_START, 1f, d)
            for (c in 0..2) rgb[c] *= factor
        }
        if (spec.grainAmount != 0f) {
            val cells = ColorEffectSpec.GRAIN_CELLS_SHORT_EDGE / shortEdge
            val cellX = floor(u * width * cells).toInt()
            val cellY = floor(v * height * cells).toInt()
            val noise = grainNoise(cellX, cellY, spec.grainSeed) - 0.5f
            for (c in 0..2) rgb[c] += GRAIN_SCALE * spec.grainAmount * noise
        }
    }

    /** 가장자리를 clamp하는 분리형 Gaussian blur. 8비트 RGB에 적용하고 alpha는 복사한다. */
    public fun blur(pixels: IntArray, width: Int, height: Int, weights: FloatArray): IntArray {
        val radius = weights.size - 1
        val horizontal = IntArray(width * height)
        val acc = FloatArray(3)
        for (y in 0 until height) for (x in 0 until width) {
            acc.fill(0f)
            for (k in -radius..radius) {
                val p = pixels[y * width + (x + k).coerceIn(0, width - 1)]
                val w = weights[if (k < 0) -k else k]
                acc[0] += w * ((p shr 16) and 0xFF)
                acc[1] += w * ((p shr 8) and 0xFF)
                acc[2] += w * (p and 0xFF)
            }
            horizontal[y * width + x] = pack(pixels[y * width + x], acc)
        }
        val result = IntArray(width * height)
        for (y in 0 until height) for (x in 0 until width) {
            acc.fill(0f)
            for (k in -radius..radius) {
                val p = horizontal[(y + k).coerceIn(0, height - 1) * width + x]
                val w = weights[if (k < 0) -k else k]
                acc[0] += w * ((p shr 16) and 0xFF)
                acc[1] += w * ((p shr 8) and 0xFF)
                acc[2] += w * (p and 0xFF)
            }
            result[y * width + x] = pack(pixels[y * width + x], acc)
        }
        return result
    }

    /** [sigma] 픽셀에 대한 정규화된 1D Gaussian 가중치. 중앙 탭부터 바깥쪽 순서다. */
    public fun gaussianWeights(sigma: Float, maxRadius: Int = ColorEffectSpec.MAX_BLUR_RADIUS): FloatArray {
        val safeSigma = maxOf(0.5f, sigma)
        val radius = min(maxRadius, ceil(3f * safeSigma).toInt())
        val weights = FloatArray(radius + 1) { i -> exp(-(i * i) / (2f * safeSigma * safeSigma)) }
        val total = weights[0] + 2f * weights.drop(1).sum()
        for (i in weights.indices) weights[i] /= total
        return weights
    }

    /** shader와 비트 단위까지 동일한 정수 해시. `0..1`을 반환한다. */
    public fun grainNoise(x: Int, y: Int, seed: Int): Float {
        var h = x * HASH_X + y * HASH_Y + seed * HASH_SEED
        h = (h xor (h ushr 13)) * HASH_MIX
        h = h xor (h ushr 16)
        return (h and 0xFFFFFF) / 16777215f
    }

    private fun applyGrade(grade: ColorEffectSpec.ResolvedGrade, rgb: FloatArray) {
        rgb[0] *= grade.gains.red
        rgb[1] *= grade.gains.green
        rgb[2] *= grade.gains.blue
        for (c in 0..2) rgb[c] = (rgb[c] + grade.brightnessOffset - 0.5f) * grade.contrastFactor + 0.5f
        saturate(rgb, grade.saturationFactor)
        val y = luma(rgb)
        val sm = shadowMask(y)
        val hm = highlightMask(y)
        rgb[0] += sm * grade.shadowTint.red + hm * grade.highlightTint.red
        rgb[1] += sm * grade.shadowTint.green + hm * grade.highlightTint.green
        rgb[2] += sm * grade.shadowTint.blue + hm * grade.highlightTint.blue
        if (grade.fadeLift != 0f) for (c in 0..2) rgb[c] = rgb[c] * (1f - grade.fadeLift) + grade.fadeLift
    }

    private fun saturate(rgb: FloatArray, factor: Float) {
        if (factor == 1f) return
        val y = luma(rgb)
        for (c in 0..2) rgb[c] = y + (rgb[c] - y) * factor
    }

    private fun luma(rgb: FloatArray): Float =
        (ColorEffectSpec.LUMA_R * rgb[0] + ColorEffectSpec.LUMA_G * rgb[1] + ColorEffectSpec.LUMA_B * rgb[2]).toFloat()

    private fun shadowMask(y: Float) = 1f - smoothstep(0f, SHADOW_EDGE, y)

    private fun highlightMask(y: Float) = smoothstep(HIGHLIGHT_EDGE, 1f, y)

    private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun pack(original: Int, acc: FloatArray): Int =
        (original and ALPHA_MASK) or (quantize(acc[0] / 255f) shl 16) or (quantize(acc[1] / 255f) shl 8) or quantize(acc[2] / 255f)

    private fun quantize(value: Float): Int = (value.coerceIn(0f, 1f) * 255f).roundToInt()

    public fun linearToSrgb(value: Float): Float =
        if (value <= 0.0031308f) value * 12.92f else (1.055 * value.toDouble().pow(1.0 / 2.4) - 0.055).toFloat()

    public fun srgbToLinear(value: Float): Float =
        if (value <= 0.04045f) value / 12.92f else ((value + 0.055) / 1.055).pow(2.4).toFloat()

    private val SRGB_TO_LINEAR = FloatArray(256) { srgbToLinear(it / 255f) }

    private const val ALPHA_MASK = 0xFF000000.toInt()
    internal const val TONE_SCALE = 0.35f
    internal const val SHADOW_EDGE = 0.55f
    internal const val HIGHLIGHT_EDGE = 0.45f
    internal const val VIGNETTE_SCALE = 0.75f
    internal const val VIGNETTE_START = 0.3f
    internal const val GRAIN_SCALE = 0.12f
    private const val SQRT2 = 1.4142135f
    private const val HASH_X = 374761393
    private const val HASH_Y = 668265263
    private const val HASH_SEED = 1442695041
    private const val HASH_MIX = 1274126177
}
