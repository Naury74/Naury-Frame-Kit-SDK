package com.naury.framekit.core.validation

/** 검증 실패의 분류. 호스트가 자체 메시지로 매핑한다. */
public enum class ValidationCode {
    /** 숫자가 NaN이거나 무한대다. */
    NOT_FINITE,

    /** 값이 문서화된 범위를 벗어났다. */
    OUT_OF_RANGE,

    /** 사각형이 비었거나 뒤집혔거나 단위 정사각형을 벗어났다. */
    INVALID_RECT,

    /** 크기가 0이거나 음수다. */
    INVALID_SIZE,

    /** 자르기가 이미지 영역을 벗어나거나 최소 출력 크기보다 적은 픽셀을 만든다. */
    INVALID_CROP,

    /** 두 오버레이 또는 획이 같은 id를 쓴다. */
    DUPLICATE_ID,

    /** 프리셋, 폰트 또는 에셋 id가 해당 카탈로그에 없다. */
    UNKNOWN_REFERENCE,

    /** 프로젝트가 참조하는 소스가 제공된 메타데이터와 맞지 않는다. */
    SOURCE_MISMATCH,
}

/**
 * 값이 거부된 이유 하나.
 *
 * @property path 문제가 된 필드의 점 구분 경로. 예: `geometry.crop`.
 */
public data class ValidationIssue(
    val code: ValidationCode,
    val path: String,
    val message: String,
)

/** 호스트 입력이나 프로젝트의 검증 결과. 잘못된 입력을 조용히 고치지 않는다. */
public sealed interface ValidationResult {
    public data object Valid : ValidationResult

    public data class Invalid(val issues: List<ValidationIssue>) : ValidationResult {
        init {
            require(issues.isNotEmpty()) { "Invalid result needs at least one issue" }
        }
    }

    public val isValid: Boolean get() = this is Valid

    public companion object {
        public fun of(issues: List<ValidationIssue>): ValidationResult =
            if (issues.isEmpty()) Valid else Invalid(issues)
    }
}
