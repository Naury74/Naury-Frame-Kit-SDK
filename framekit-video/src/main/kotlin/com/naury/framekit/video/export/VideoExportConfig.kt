package com.naury.framekit.video.export

import android.os.Parcelable
import com.naury.framekit.core.validation.ValidationCode
import com.naury.framekit.core.validation.ValidationIssue
import com.naury.framekit.core.validation.ValidationResult
import kotlinx.parcelize.Parcelize

/**
 * Output settings for video export. The output is MP4 with H.264 video and AAC audio in SDR.
 *
 * @property maxShortSide upper bound of the shorter output side in pixels; 1080 gives 1080p for
 *   both landscape and portrait video. The output is never larger than the edited source.
 * @property maxFrameRate frames above this rate are dropped; the actual rate is reported.
 * @property allowFallback when `false`, export fails instead of accepting a different resolution
 *   or codec setting from the encoder.
 */
@Parcelize
public data class VideoExportConfig(
    val maxShortSide: Int = 1080,
    val maxFrameRate: Int = 30,
    val allowFallback: Boolean = true,
) : Parcelable {

    public fun validate(): ValidationResult {
        val issues = mutableListOf<ValidationIssue>()
        if (maxShortSide !in MIN_SHORT_SIDE..MAX_SHORT_SIDE) {
            issues += ValidationIssue(ValidationCode.OUT_OF_RANGE, "export.maxShortSide", "Expected $MIN_SHORT_SIDE..$MAX_SHORT_SIDE")
        }
        if (maxFrameRate !in 1..MAX_FRAME_RATE) {
            issues += ValidationIssue(ValidationCode.OUT_OF_RANGE, "export.maxFrameRate", "Expected 1..$MAX_FRAME_RATE")
        }
        return ValidationResult.of(issues)
    }

    public companion object {
        public const val MIN_SHORT_SIDE: Int = 144
        public const val MAX_SHORT_SIDE: Int = 2160
        public const val MAX_FRAME_RATE: Int = 60
    }
}
