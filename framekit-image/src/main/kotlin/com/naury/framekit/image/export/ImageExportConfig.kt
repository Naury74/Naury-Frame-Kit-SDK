package com.naury.framekit.image.export

import android.os.Parcelable
import com.naury.framekit.core.validation.ValidationCode
import com.naury.framekit.core.validation.ValidationIssue
import com.naury.framekit.core.validation.ValidationResult
import com.naury.framekit.core.pdf.PdfOrientation
import com.naury.framekit.core.pdf.PdfPageSize
import kotlinx.parcelize.Parcelize

/** 인코딩할 이미지 포맷이다. */
public enum class ImageFormat(public val mimeType: String, public val extension: String) {
    JPEG("image/jpeg", "jpg"),

    /** 무손실. [ImageExportConfig.quality]는 적용되지 않는다. */
    PNG("image/png", "png"),

    /** alpha를 지원하는 손실 WEBP. [ImageExportConfig.quality]를 사용한다. */
    WEBP_LOSSY("image/webp", "webp"),

    /**
     * alpha를 지원하는 무손실 WEBP. Android 11(API 30) 이상이 필요하며, 이전 기기에서는 조용히
     * 손실 파일을 쓰는 대신 `UNSUPPORTED_FORMAT`을 반환한다.
     */
    WEBP_LOSSLESS("image/webp", "webp"),

    /**
     * 사진을 쪽으로 담은 PDF 문서. 쪽마다 JPEG([ImageExportConfig.quality])로 넣고, 쪽 크기·여백·해상도는
     * [ImageExportConfig.pdf]를 따른다. 여러 장을 편집하면 기본으로 하나의 문서로 묶는다.
     */
    PDF("application/pdf", "pdf"),
    ;

    /** 투명도를 유지하는 포맷이면 `true`. */
    public val supportsAlpha: Boolean get() = this == PNG || this == WEBP_LOSSY || this == WEBP_LOSSLESS
}

/** 출력에 기록할 원본 메타데이터의 범위다. */
public enum class MetadataPolicy {
    /**
     * 촬영 시각과 고정된 목록의 촬영 설정만 복사한다. 위치·소유자·일련번호·주석·내장 썸네일은
     * 버린다. 방향은 normal로 기록하고 크기는 새 크기로 기록한다.
     */
    SAFE,

    /** 원본 메타데이터를 기록하지 않는다. */
    NONE,

    /**
     * 위치와 카메라 일련번호를 포함해 알려진 모든 태그를 복사한다. 사용자가 명시적으로 보존을 요청했을
     * 때만 쓴다. 이때도 방향·크기·썸네일은 다시 기록하므로 출력에 편집 전 그림이 보이지 않는다.
     */
    ALL,
}

/**
 * 이미지 내보내기 출력 설정이다.
 *
 * @property quality JPEG와 손실 WEBP의 품질 `0..100`. PNG와 무손실 WEBP에서는 무시한다.
 * @property maxWidth 출력 너비 상한(px). 선택 사항이다.
 * @property maxHeight 출력 높이 상한(px). 선택 사항이다.
 * @property maxOutputPixels `width × height` 상한. 기본값 16 MP는 중급 기기 메모리 안에서 전체 내보내기가
 *   가능하도록 정한 값이다. 출력은 crop보다 커지지 않는다.
 * @property jpegBackgroundArgb JPEG 출력에서 투명 영역을 대신할 색.
 * @property metadataPolicy JPEG와 WEBP 출력에 기록할 원본 메타데이터. PNG·PDF 출력에는 기록하지 않는다.
 * @property pdf [ImageFormat.PDF]일 때 쪽 설정.
 */
@Parcelize
public data class ImageExportConfig(
    val format: ImageFormat = ImageFormat.JPEG,
    val quality: Int = 92,
    val maxWidth: Int? = null,
    val maxHeight: Int? = null,
    val maxOutputPixels: Long = DEFAULT_MAX_OUTPUT_PIXELS,
    val metadataPolicy: MetadataPolicy = MetadataPolicy.SAFE,
    val jpegBackgroundArgb: Int = 0xFF000000.toInt(),
    val pdf: PdfOptions = PdfOptions(),
) : Parcelable {

    public fun validate(): ValidationResult {
        val issues = mutableListOf<ValidationIssue>()
        if (quality !in 0..100) issues += ValidationIssue(ValidationCode.OUT_OF_RANGE, "export.quality", "Expected 0..100")
        if (maxWidth != null && maxWidth <= 0) issues += ValidationIssue(ValidationCode.INVALID_SIZE, "export.maxWidth", "Must be positive")
        if (maxHeight != null && maxHeight <= 0) issues += ValidationIssue(ValidationCode.INVALID_SIZE, "export.maxHeight", "Must be positive")
        if (maxOutputPixels <= 0) issues += ValidationIssue(ValidationCode.INVALID_SIZE, "export.maxOutputPixels", "Must be positive")
        if (format == ImageFormat.PDF) issues += pdf.validate()
        return ValidationResult.of(issues)
    }

    public companion object {
        public const val DEFAULT_MAX_OUTPUT_PIXELS: Long = 16_000_000L
    }
}

/**
 * PDF 쪽 설정.
 *
 * @property pageSize 쪽 크기. [PdfPageSize.FIT_IMAGE]면 사진 크기에 맞춘다.
 * @property orientation [PdfOrientation.AUTO]는 사진이 가로로 길면 가로 쪽.
 * @property marginMm 네 변 여백(mm), `0..50`.
 * @property dpi 쪽에 넣는 해상도, `72..600`. 높을수록 선명하고 파일이 크다. 원본보다 키우지는 않는다.
 * @property backgroundArgb 여백과 투명 영역의 색.
 * @property combinePages 여러 장을 편집할 때 하나의 문서로 묶을지. `false`면 사진마다 PDF를 만든다.
 * @property recognizeText 글자 인식 모듈(`framekit-ocr`)이 있으면 쪽마다 글자를 인식해 보이지 않는 글자 레이어를
 *   넣는다. PDF 뷰어에서 검색·복사가 된다. 모듈이 없으면 이미지만 넣는다. 모듈이 있는데 인식하지 못한 쪽이
 *   있으면(모델 다운로드 중 등) 경고 `TEXT_RECOGNITION_SKIPPED`를 붙인다.
 */
@Parcelize
public data class PdfOptions(
    val pageSize: PdfPageSize = PdfPageSize.A4,
    val orientation: PdfOrientation = PdfOrientation.AUTO,
    val marginMm: Double = 10.0,
    val dpi: Int = 200,
    val backgroundArgb: Int = 0xFFFFFFFF.toInt(),
    val combinePages: Boolean = true,
    val recognizeText: Boolean = true,
) : Parcelable {

    internal fun validate(): List<ValidationIssue> = buildList {
        if (!marginMm.isFinite() || marginMm !in 0.0..MAX_MARGIN_MM) add(ValidationIssue(ValidationCode.OUT_OF_RANGE, "export.pdf.marginMm", "Expected 0..$MAX_MARGIN_MM"))
        if (dpi !in MIN_DPI..MAX_DPI) add(ValidationIssue(ValidationCode.OUT_OF_RANGE, "export.pdf.dpi", "Expected $MIN_DPI..$MAX_DPI"))
    }

    public companion object {
        public const val MAX_MARGIN_MM: Double = 50.0
        public const val MIN_DPI: Int = 72
        public const val MAX_DPI: Int = 600
    }
}
