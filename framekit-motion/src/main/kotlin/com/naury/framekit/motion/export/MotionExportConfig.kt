package com.naury.framekit.motion.export

import android.os.Parcelable
import com.naury.framekit.core.validation.ValidationCode
import com.naury.framekit.core.validation.ValidationIssue
import com.naury.framekit.core.validation.ValidationResult
import kotlinx.parcelize.Parcelize

/** 움직이는 이미지 저장 형식. */
public enum class MotionFormat(public val mimeType: String, public val extension: String) {
    /** H.264 영상. 품질과 크기가 가장 좋고 소리를 넣을 수 있다. */
    MP4("video/mp4", "mp4"),

    /** 256색 움직이는 이미지. 거의 모든 앱·웹에서 열린다. */
    GIF("image/gif", "gif"),

    /** 움직이는 WebP. GIF보다 색이 풍부하고 작다. */
    WEBP("image/webp", "webp"),
}

/** 결과 크기 단계. 형식마다 긴 변 길이가 다르다([longEdge]). */
public enum class MotionSize {
    SMALL,
    MEDIUM,
    LARGE,
    ;

    /** [format]으로 저장할 때 긴 변(px). */
    public fun longEdge(format: MotionFormat): Int = when (format) {
        MotionFormat.MP4 -> when (this) { SMALL -> 540; MEDIUM -> 720; LARGE -> 1080 }
        MotionFormat.WEBP -> when (this) { SMALL -> 360; MEDIUM -> 540; LARGE -> 720 }
        MotionFormat.GIF -> when (this) { SMALL -> 240; MEDIUM -> 360; LARGE -> 480 }
    }
}

/**
 * 움직이는 이미지 저장 설정. 사용자는 저장할 때 형식과 크기를 다시 고를 수 있고, 이 값은 처음 선택값이 된다.
 *
 * @property formats 사용자에게 보여 줄 형식. 비어 있으면 안 된다.
 * @property format 처음 선택된 형식. [formats]에 있어야 한다.
 * @property frameRate 초당 프레임 수(5..60). `null`이면 형식마다 기본값(MP4 30, WebP 20, GIF 15). 움직이는 원본을 편집할 때는
 *   원본 프레임 간격에 맞춰 이 값 이하로 정한다.
 * @property webpQuality 움직이는 WebP 품질(1..100).
 * @property loopCount GIF·WebP 반복 횟수. `0`은 무한 반복.
 */
@Parcelize
public data class MotionExportConfig(
    val formats: List<MotionFormat> = MotionFormat.entries,
    val format: MotionFormat = MotionFormat.MP4,
    val size: MotionSize = MotionSize.MEDIUM,
    val frameRate: Int? = null,
    val webpQuality: Int = 80,
    val loopCount: Int = 0,
) : Parcelable {

    public fun validate(): ValidationResult {
        val issues = mutableListOf<ValidationIssue>()
        if (formats.isEmpty()) issues += ValidationIssue(ValidationCode.OUT_OF_RANGE, "export.formats", "At least one format is required")
        if (format !in formats) issues += ValidationIssue(ValidationCode.OUT_OF_RANGE, "export.format", "Format must be in formats")
        if (frameRate != null && frameRate !in 5..60) issues += ValidationIssue(ValidationCode.OUT_OF_RANGE, "export.frameRate", "Expected 5..60")
        if (webpQuality !in 1..100) issues += ValidationIssue(ValidationCode.OUT_OF_RANGE, "export.webpQuality", "Expected 1..100")
        if (loopCount !in 0..0xFFFF) issues += ValidationIssue(ValidationCode.OUT_OF_RANGE, "export.loopCount", "Expected 0..65535")
        return ValidationResult.of(issues)
    }

    /** [format]으로 저장할 때 초당 프레임 수. */
    public fun frameRateFor(format: MotionFormat): Int = frameRate ?: when (format) {
        MotionFormat.MP4 -> 30
        MotionFormat.WEBP -> 20
        MotionFormat.GIF -> 15
    }
}
