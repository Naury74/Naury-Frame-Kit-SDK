package com.naury.framekit.ui.config

import android.os.Parcelable
import com.naury.framekit.core.overlay.Watermark
import com.naury.framekit.core.overlay.WatermarkPattern
import com.naury.framekit.core.overlay.WatermarkPosition
import com.naury.framekit.core.validation.ValidationCode
import com.naury.framekit.core.validation.ValidationIssue
import com.naury.framekit.core.validation.ValidationResult
import kotlinx.parcelize.Parcelize

/**
 * 호스트가 정하는 워터마크. 사진·영상 편집기 설정의 `watermark`로 넘긴다.
 *
 * - 문구만 넘기면 워터마크 도구를 열 때 그 문구가 채워진다([applyOnOpen]이 `false`일 때).
 * - [applyOnOpen]이 `true`면 편집기를 열자마자 워터마크가 얹혀 있고, 사용자가 도구에서 바꾸거나 지울 수 있다.
 * - [locked]가 `true`면 사용자가 바꾸거나 지울 수 없고 워터마크 도구가 숨겨진다. 저장할 때 항상 이 워터마크가 들어간다.
 *
 * @property text 문구. 앞뒤 공백을 뺀 0..[Watermark.MAX_TEXT_LENGTH]자, 한 줄. [applyOnOpen]·[locked]이면 비어 있으면 안 된다.
 * @property pattern 놓는 방식.
 * @property position [WatermarkPattern.SINGLE]·[WatermarkPattern.BAND]의 자리.
 * @property sizeRatio 글자 높이를 짧은 변으로 나눈 값([Watermark.MIN_SIZE]..[Watermark.MAX_SIZE]).
 * @property opacity 불투명도 `0.05..1`.
 * @property colorArgb 글자 색.
 * @property rotationDegrees 반복 무늬의 기울기(도, `-90..90`).
 * @property density 반복 무늬의 촘촘함(`0.5..2`).
 * @property background 글자 뒤에 반투명 둥근 상자를 깐다.
 * @property applyOnOpen 편집기를 열 때 바로 얹는다.
 * @property locked 사용자가 바꾸거나 지울 수 없게 한다. `true`면 [applyOnOpen]도 `true`여야 한다.
 * @property suggestions 워터마크 도구에 빠르게 고를 문구로 보여 줄 후보(최대 [MAX_SUGGESTIONS]개). 예: 가게 이름, 계정 이름.
 */
@Parcelize
public data class WatermarkConfig(
    val text: String = "",
    val pattern: WatermarkPattern = WatermarkPattern.SINGLE,
    val position: WatermarkPosition = WatermarkPosition.BOTTOM_END,
    val sizeRatio: Double = Watermark.DEFAULT_SIZE,
    val opacity: Double = 0.7,
    val colorArgb: Int = 0xFFFFFFFF.toInt(),
    val rotationDegrees: Double = -30.0,
    val density: Double = 1.0,
    val background: Boolean = false,
    val applyOnOpen: Boolean = false,
    val locked: Boolean = false,
    val suggestions: List<String> = emptyList(),
) : Parcelable {

    /** 이 설정의 워터마크. 문구가 비어 있으면 `null`. */
    public fun toWatermark(): Watermark? = text.trim().takeIf { it.isNotEmpty() }?.let {
        Watermark(
            text = it,
            pattern = pattern,
            position = position,
            sizeRatio = sizeRatio,
            opacity = opacity,
            colorArgb = colorArgb,
            rotationDegrees = rotationDegrees,
            density = density,
            background = background,
        ).normalized()
    }

    /** 워터마크 도구를 처음 열 때 채울 값. 문구가 비어 있을 수 있다. */
    public fun template(): Watermark = Watermark(
        text = text,
        pattern = pattern,
        position = position,
        sizeRatio = sizeRatio,
        opacity = opacity,
        colorArgb = colorArgb,
        rotationDegrees = rotationDegrees,
        density = density,
        background = background,
    ).normalized()

    /** 편집기를 열 때 프로젝트에 얹을 워터마크. [applyOnOpen]·[locked]가 아니거나 문구가 비면 `null`. */
    public val initialWatermark: Watermark? get() = if (applyOnOpen || locked) toWatermark() else null

    /** @param path 오류 경로 앞부분. 예: `image.watermark`. */
    public fun validate(path: String): ValidationResult {
        val issues = mutableListOf<ValidationIssue>()
        val trimmed = text.trim()
        if (trimmed.length > Watermark.MAX_TEXT_LENGTH || '\n' in text) {
            issues += ValidationIssue(ValidationCode.OUT_OF_RANGE, "$path.text", "Expected up to ${Watermark.MAX_TEXT_LENGTH} characters on one line")
        }
        if ((applyOnOpen || locked) && trimmed.isEmpty()) {
            issues += ValidationIssue(ValidationCode.OUT_OF_RANGE, "$path.text", "Text is required when applyOnOpen or locked")
        }
        if (locked && !applyOnOpen) {
            issues += ValidationIssue(ValidationCode.OUT_OF_RANGE, "$path.locked", "locked requires applyOnOpen")
        }
        if (sizeRatio !in Watermark.MIN_SIZE..Watermark.MAX_SIZE) issues += ValidationIssue(ValidationCode.OUT_OF_RANGE, "$path.sizeRatio", "Expected ${Watermark.MIN_SIZE}..${Watermark.MAX_SIZE}")
        if (opacity !in Watermark.MIN_OPACITY..1.0) issues += ValidationIssue(ValidationCode.OUT_OF_RANGE, "$path.opacity", "Expected ${Watermark.MIN_OPACITY}..1")
        if (rotationDegrees !in -90.0..90.0) issues += ValidationIssue(ValidationCode.OUT_OF_RANGE, "$path.rotationDegrees", "Expected -90..90")
        if (density !in Watermark.MIN_DENSITY..Watermark.MAX_DENSITY) issues += ValidationIssue(ValidationCode.OUT_OF_RANGE, "$path.density", "Expected ${Watermark.MIN_DENSITY}..${Watermark.MAX_DENSITY}")
        if (suggestions.size > MAX_SUGGESTIONS || suggestions.any { it.isBlank() || it.trim().length > Watermark.MAX_TEXT_LENGTH }) {
            issues += ValidationIssue(ValidationCode.OUT_OF_RANGE, "$path.suggestions", "Up to $MAX_SUGGESTIONS non-blank one-line texts")
        }
        return ValidationResult.of(issues)
    }

    public companion object {
        public const val MAX_SUGGESTIONS: Int = 8
    }
}
