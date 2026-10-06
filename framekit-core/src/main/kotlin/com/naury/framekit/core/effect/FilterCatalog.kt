package com.naury.framekit.core.effect

/** Small additive color shift in display RGB, used for split toning. */
public data class RgbShift(val red: Double = 0.0, val green: Double = 0.0, val blue: Double = 0.0) {
    public val isZero: Boolean get() = red == 0.0 && green == 0.0 && blue == 0.0
}

/**
 * Look of a filter preset, defined with the same operations and ranges as [Adjustments] so presets
 * and adjustments share one renderer.
 *
 * All operations run in display (sRGB-encoded) space in this order: temperature, tint, brightness,
 * contrast, saturation, split toning ([shadowTint] on dark tones, [highlightTint] on bright tones),
 * fade.
 */
public data class FilterGrade(
    val temperature: Double = 0.0,
    val tint: Double = 0.0,
    val brightness: Double = 0.0,
    val contrast: Double = 0.0,
    val saturation: Double = 0.0,
    val shadowTint: RgbShift = RgbShift(),
    val highlightTint: RgbShift = RgbShift(),
    val fade: Double = 0.0,
)

/**
 * A filter preset. [version] changes whenever [grade] changes so cached thumbnails and saved projects
 * can tell old looks from new ones.
 */
public data class FilterPreset(val id: String, val version: Int, val grade: FilterGrade)

/**
 * Selected preset and how strongly it is blended over the adjusted image.
 *
 * @property intensity `0..1`; the result is `mix(adjusted, filtered, intensity)`.
 */
public data class FilterSelection(
    val presetId: String = FilterCatalog.ORIGINAL_ID,
    val intensity: Double = 0.0,
) {
    public val isIdentity: Boolean get() = presetId == FilterCatalog.ORIGINAL_ID || intensity == 0.0
}

/** Built-in presets. Parameters live here, not in the UI. */
public object FilterCatalog {
    public const val ORIGINAL_ID: String = "original"

    public val original: FilterPreset = FilterPreset(ORIGINAL_ID, 1, FilterGrade())

    public val presets: List<FilterPreset> = listOf(
        original,
        // 한 번에 고르는 분위기 템플릿. 사진과 영상이 같은 카탈로그를 쓴다.
        FilterPreset(
            "bright",
            1,
            FilterGrade(brightness = 0.08, contrast = -0.08, saturation = 0.1, highlightTint = RgbShift(0.02, 0.02, 0.01), fade = 0.1),
        ),
        FilterPreset("soft", 1, FilterGrade(contrast = -0.15, saturation = -0.05, fade = 0.25)),
        FilterPreset("lovely", 1, FilterGrade(tint = 0.3, brightness = 0.04, saturation = 0.05, highlightTint = RgbShift(0.03, 0.0, 0.02))),
        FilterPreset("dramatic", 1, FilterGrade(contrast = 0.3, saturation = -0.15, shadowTint = RgbShift(0.0, 0.01, 0.03))),
        FilterPreset("clean", 1, FilterGrade(brightness = 0.02, contrast = 0.08, saturation = 0.05)),
        FilterPreset("vivid", 1, FilterGrade(contrast = 0.15, saturation = 0.35)),
        FilterPreset("warm", 1, FilterGrade(temperature = 0.5, saturation = 0.05, highlightTint = RgbShift(0.03, 0.01, -0.02))),
        FilterPreset("cool", 1, FilterGrade(temperature = -0.5, tint = -0.05)),
        FilterPreset(
            "film01",
            1,
            FilterGrade(contrast = -0.05, saturation = -0.15, shadowTint = RgbShift(0.0, 0.02, 0.04), highlightTint = RgbShift(0.04, 0.02, 0.0), fade = 0.3),
        ),
        FilterPreset("film02", 1, FilterGrade(temperature = 0.15, contrast = 0.1, saturation = -0.25, shadowTint = RgbShift(0.02, 0.0, 0.03), fade = 0.15)),
        FilterPreset("film03", 1, FilterGrade(temperature = -0.15, contrast = 0.2, saturation = 0.1, highlightTint = RgbShift(0.0, 0.02, 0.03), fade = 0.1)),
        FilterPreset("mono", 1, FilterGrade(contrast = 0.15, saturation = -1.0)),
        FilterPreset("fade", 1, FilterGrade(contrast = -0.1, saturation = -0.1, fade = 0.6)),
        FilterPreset(
            "vintage",
            1,
            FilterGrade(temperature = 0.35, contrast = -0.05, saturation = -0.35, shadowTint = RgbShift(0.03, 0.01, 0.0), fade = 0.35),
        ),
        FilterPreset("cinema", 1, FilterGrade(contrast = 0.2, saturation = -0.1, shadowTint = RgbShift(0.0, 0.03, 0.05), highlightTint = RgbShift(0.05, 0.02, -0.02))),
    )

    public fun find(id: String): FilterPreset? = presets.firstOrNull { it.id == id }
}
