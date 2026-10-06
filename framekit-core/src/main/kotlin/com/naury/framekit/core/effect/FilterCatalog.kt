package com.naury.framekit.core.effect

/** display RGB에서 더하는 작은 색 이동. split toning에 쓴다. */
public data class RgbShift(val red: Double = 0.0, val green: Double = 0.0, val blue: Double = 0.0) {
    public val isZero: Boolean get() = red == 0.0 && green == 0.0 && blue == 0.0
}

/**
 * 필터 프리셋의 룩. [Adjustments]와 같은 연산·범위로 정의하므로 프리셋과 보정이 하나의 렌더러를
 * 공유한다.
 *
 * 모든 연산은 display(sRGB 인코딩) 공간에서 다음 순서로 실행된다: temperature, tint, 밝기, 대비,
 * 채도, split toning(어두운 톤에 [shadowTint], 밝은 톤에 [highlightTint]), fade.
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
 * 필터 프리셋. [grade]가 바뀔 때마다 [version]도 바뀌므로, 캐시된 썸네일과 저장된 프로젝트가 이전
 * 룩과 새 룩을 구분할 수 있다.
 */
public data class FilterPreset(val id: String, val version: Int, val grade: FilterGrade)

/**
 * 선택된 프리셋과, 보정된 이미지 위에 섞는 강도.
 *
 * @property intensity `0..1`. 결과는 `mix(adjusted, filtered, intensity)`이다.
 */
public data class FilterSelection(
    val presetId: String = FilterCatalog.ORIGINAL_ID,
    val intensity: Double = 0.0,
) {
    public val isIdentity: Boolean get() = presetId == FilterCatalog.ORIGINAL_ID || intensity == 0.0
}

/**
 * 필터 프리셋 목록. 내장 프리셋([presets])과 호스트가 등록한 프리셋([custom])을 id로 찾는다.
 *
 * 호스트 프리셋은 프로세스 전체에서 하나의 목록이다. 편집기와 headless 처리기가 시작할 때 요청에 담긴
 * 목록으로 [install]하므로, 미리보기·저장·실행 취소 기록이 같은 id로 같은 값을 얻는다.
 */
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
        // 종이 문서를 찍은 사진용. 배경을 밝게 하고 글자 대비를 높인다. PDF로 만들 때 쓰기 좋다.
        FilterPreset("document", 1, FilterGrade(brightness = 0.1, contrast = 0.4, saturation = -0.3, highlightTint = RgbShift(0.03, 0.03, 0.03))),
        FilterPreset("document-bw", 1, FilterGrade(brightness = 0.12, contrast = 0.6, saturation = -1.0, highlightTint = RgbShift(0.04, 0.04, 0.04))),
    )

    @Volatile
    private var installed: List<FilterPreset> = emptyList()

    /** 호스트가 등록한 프리셋. */
    public val custom: List<FilterPreset> get() = installed

    /** 내장 프리셋 뒤에 호스트 프리셋을 붙인 전체 목록. */
    public val all: List<FilterPreset> get() = presets + installed

    public fun find(id: String): FilterPreset? = presets.firstOrNull { it.id == id } ?: installed.firstOrNull { it.id == id }

    /**
     * 호스트 프리셋을 바꾼다. 이전에 등록한 목록은 대체된다.
     *
     * @throws IllegalArgumentException 내장 프리셋과 id가 겹치거나 id가 중복될 때.
     */
    public fun install(custom: List<FilterPreset>) {
        require(custom.none { preset -> presets.any { it.id == preset.id } }) { "Custom filter ids must not reuse built-in ids" }
        require(custom.map { it.id }.toSet().size == custom.size) { "Custom filter ids must be unique" }
        installed = custom.toList()
    }
}
