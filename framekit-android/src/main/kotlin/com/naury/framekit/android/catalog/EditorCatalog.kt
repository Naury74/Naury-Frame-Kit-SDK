package com.naury.framekit.android.catalog

import android.net.Uri
import android.os.Parcelable
import com.naury.framekit.core.effect.FilterCatalog
import com.naury.framekit.core.effect.FilterGrade
import com.naury.framekit.core.effect.FilterPreset
import com.naury.framekit.core.effect.RgbShift
import com.naury.framekit.core.overlay.FontCatalog
import com.naury.framekit.core.validation.ValidationCode
import com.naury.framekit.core.validation.ValidationIssue
import com.naury.framekit.core.validation.ValidationResult
import kotlinx.parcelize.Parcelize

/**
 * 호스트가 편집기에 더하는 필터·스티커·폰트.
 *
 * 실행 Intent로 전달되므로 값과 파일 위치만 담는다. 편집기는 시작할 때와 Activity가 다시 만들어질 때마다
 * 이 목록을 다시 불러오므로, 파일은 편집이 끝날 때까지 같은 위치에 있어야 한다. 프로젝트에는 id만
 * 저장되니 id는 앱 버전이 바뀌어도 같은 것을 가리켜야 한다.
 *
 * @property showDefaultFilters `false`이면 내장 필터를 숨기고 [filters]만 보여 준다. 원본(필터 없음)은 항상 있다.
 * @property showDefaultStickers `false`이면 이모지 스티커를 숨긴다.
 * @property showDefaultFonts `false`이면 내장 폰트 중 기본 산세리프만 남긴다.
 */
@Parcelize
public data class EditorCatalog(
    val filters: List<CustomFilter> = emptyList(),
    val stickers: List<CustomSticker> = emptyList(),
    val fonts: List<CustomFont> = emptyList(),
    val showDefaultFilters: Boolean = true,
    val showDefaultStickers: Boolean = true,
    val showDefaultFonts: Boolean = true,
) : Parcelable {

    public val isEmpty: Boolean get() = filters.isEmpty() && stickers.isEmpty() && fonts.isEmpty()

    /** id 형식·중복·내장 id와의 충돌, 필터 값 범위를 확인한다. */
    public fun validate(): ValidationResult {
        val issues = mutableListOf<ValidationIssue>()
        fun checkIds(ids: List<String>, path: String, reserved: Set<String>) {
            ids.forEachIndexed { index, id ->
                if (!ID_PATTERN.matches(id)) issues += ValidationIssue(ValidationCode.OUT_OF_RANGE, "$path[$index].id", "Use 1..64 of a-z, 0-9, '-', '_', '.'")
                if (id in reserved) issues += ValidationIssue(ValidationCode.DUPLICATE_ID, "$path[$index].id", "Reuses a built-in id")
            }
            ids.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.forEach {
                issues += ValidationIssue(ValidationCode.DUPLICATE_ID, path, "Duplicate id $it")
            }
        }
        checkIds(filters.map { it.id }, "catalog.filters", FilterCatalog.presets.map { it.id }.toSet())
        checkIds(stickers.map { it.id }, "catalog.stickers", emptySet())
        checkIds(fonts.map { it.id }, "catalog.fonts", FontCatalog.fontIds.toSet())
        filters.forEachIndexed { index, filter -> filter.validate("catalog.filters[$index]", issues) }
        (filters.map { it.label } + stickers.map { it.label } + fonts.map { it.label }).forEach { label ->
            if (label.isBlank()) issues += ValidationIssue(ValidationCode.OUT_OF_RANGE, "catalog", "Labels must not be blank")
        }
        if (stickers.size > MAX_ITEMS || filters.size > MAX_ITEMS || fonts.size > MAX_FONTS) {
            issues += ValidationIssue(ValidationCode.OUT_OF_RANGE, "catalog", "At most $MAX_ITEMS filters and stickers, $MAX_FONTS fonts")
        }
        return ValidationResult.of(issues)
    }

    public companion object {
        public const val MAX_ITEMS: Int = 200
        public const val MAX_FONTS: Int = 20
        private val ID_PATTERN = Regex("[a-z0-9_.-]{1,64}")
    }
}

/**
 * 호스트 필터. 값의 의미와 범위는 내장 필터([FilterGrade])와 같다.
 *
 * @property temperature 색온도, `-1..1`(양수는 따뜻하게).
 * @property tint 초록↔자홍, `-1..1`.
 * @property brightness 밝기 오프셋, `-1..1`.
 * @property contrast 대비, `-1..1`.
 * @property saturation 채도, `-1..1`(`-1`은 흑백).
 * @property shadowTint 어두운 영역에 더할 RGB, 채널마다 `-0.2..0.2`.
 * @property highlightTint 밝은 영역에 더할 RGB, 채널마다 `-0.2..0.2`.
 * @property fade 검은색을 들어 올리는 정도, `0..1`.
 * @property version 값을 바꾸면 올린다. 같은 id·버전이면 같은 결과가 나와야 한다.
 */
@Parcelize
public data class CustomFilter(
    val id: String,
    val label: String,
    val temperature: Double = 0.0,
    val tint: Double = 0.0,
    val brightness: Double = 0.0,
    val contrast: Double = 0.0,
    val saturation: Double = 0.0,
    val shadowTint: List<Double> = listOf(0.0, 0.0, 0.0),
    val highlightTint: List<Double> = listOf(0.0, 0.0, 0.0),
    val fade: Double = 0.0,
    val version: Int = 1,
) : Parcelable {

    public fun toPreset(): FilterPreset = FilterPreset(
        id,
        version,
        FilterGrade(temperature, tint, brightness, contrast, saturation, shift(shadowTint), shift(highlightTint), fade),
    )

    internal fun validate(path: String, issues: MutableList<ValidationIssue>) {
        val unit = listOf(temperature, tint, brightness, contrast, saturation)
        if (unit.any { !it.isFinite() || it !in -1.0..1.0 }) issues += ValidationIssue(ValidationCode.OUT_OF_RANGE, path, "Values must be in -1..1")
        if (!fade.isFinite() || fade !in 0.0..1.0) issues += ValidationIssue(ValidationCode.OUT_OF_RANGE, "$path.fade", "Expected 0..1")
        listOf(shadowTint, highlightTint).forEach { tint ->
            if (tint.size != 3 || tint.any { !it.isFinite() || it !in -MAX_TINT..MAX_TINT }) {
                issues += ValidationIssue(ValidationCode.OUT_OF_RANGE, path, "Tints need 3 values in -$MAX_TINT..$MAX_TINT")
            }
        }
        if (version < 1) issues += ValidationIssue(ValidationCode.OUT_OF_RANGE, "$path.version", "Must be positive")
    }

    private fun shift(values: List<Double>) = if (values.size == 3) RgbShift(values[0], values[1], values[2]) else RgbShift()

    private companion object {
        const val MAX_TINT = 0.2
    }
}

/** 호스트 스티커 이미지(PNG·WEBP 등, 투명 가능). 긴 변 512px로 줄여 불러온다. */
@Parcelize
public data class CustomSticker(val id: String, val label: String, val file: CatalogFile) : Parcelable

/** 호스트 폰트 파일(TTF·OTF). */
@Parcelize
public data class CustomFont(val id: String, val label: String, val file: CatalogFile) : Parcelable

/** 카탈로그 파일 위치. 편집기는 호스트 프로세스에서 실행되므로 호스트가 읽을 수 있는 위치면 된다. */
public sealed interface CatalogFile : Parcelable {
    /** 호스트 앱 `assets/` 안의 경로. 예: `stickers/logo.png`. */
    @Parcelize
    public data class Asset(val path: String) : CatalogFile

    /** 호스트 앱 내부 저장소 파일의 절대 경로. */
    @Parcelize
    public data class LocalFile(val absolutePath: String) : CatalogFile

    /** 호스트가 읽기 권한을 가진 `content://` 또는 `android.resource://` Uri. */
    @Parcelize
    public data class UriFile(val uri: Uri) : CatalogFile
}
