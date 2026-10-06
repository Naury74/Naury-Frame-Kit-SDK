package com.naury.framekit.core.validation

/** Category of a validation failure. Hosts map these to their own messages. */
public enum class ValidationCode {
    /** A number is NaN or infinite. */
    NOT_FINITE,

    /** A value is outside its documented range. */
    OUT_OF_RANGE,

    /** A rectangle is empty, inverted or outside the unit square. */
    INVALID_RECT,

    /** A size is zero or negative. */
    INVALID_SIZE,

    /** The crop leaves the image area or produces fewer pixels than the minimum output size. */
    INVALID_CROP,

    /** Two overlays or strokes share an id. */
    DUPLICATE_ID,

    /** A preset, font or asset id does not exist in its catalog. */
    UNKNOWN_REFERENCE,

    /** The project refers to a source that does not match the supplied metadata. */
    SOURCE_MISMATCH,
}

/**
 * One reason why a value was rejected.
 *
 * @property path dotted path of the offending field, for example `geometry.crop`.
 */
public data class ValidationIssue(
    val code: ValidationCode,
    val path: String,
    val message: String,
)

/** Result of validating host input or a project. Invalid input is never silently corrected. */
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
