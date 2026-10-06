package com.naury.framekit.ui.video.contract

import android.os.Parcelable
import com.naury.framekit.core.validation.ValidationCode
import com.naury.framekit.core.validation.ValidationIssue
import com.naury.framekit.core.validation.ValidationResult
import kotlinx.parcelize.Parcelize

/**
 * Behavior of the video editor.
 *
 * @property enabledTools tools shown in the rail. An empty set opens a preview that can only be saved.
 * @property minClipDurationUs shortest result the user can trim or speed up to, in output time.
 * @property maxTimelineDurationUs longest result. Longer sources open trimmed to this length.
 */
@Parcelize
public data class VideoEditorConfig(
    val enabledTools: Set<VideoTool> = VideoTool.defaults,
    val allowUndo: Boolean = true,
    val allowRedo: Boolean = true,
    val minClipDurationUs: Long = 1_000_000L,
    val maxTimelineDurationUs: Long = 300_000_000L,
) : Parcelable {

    public fun validate(): ValidationResult {
        val issues = mutableListOf<ValidationIssue>()
        if (allowRedo && !allowUndo) {
            issues += ValidationIssue(ValidationCode.OUT_OF_RANGE, "video.allowRedo", "Redo requires undo")
        }
        if (minClipDurationUs < MIN_CLIP_US) {
            issues += ValidationIssue(ValidationCode.OUT_OF_RANGE, "video.minClipDurationUs", "Must be at least $MIN_CLIP_US")
        }
        if (maxTimelineDurationUs < minClipDurationUs || maxTimelineDurationUs > MAX_TIMELINE_US) {
            issues += ValidationIssue(ValidationCode.OUT_OF_RANGE, "video.maxTimelineDurationUs", "Expected minClipDurationUs..$MAX_TIMELINE_US")
        }
        return ValidationResult.of(issues)
    }

    public companion object {
        /** About three frames at 30 fps; shorter clips cannot be encoded reliably. */
        public const val MIN_CLIP_US: Long = 100_000L

        /** One hour. */
        public const val MAX_TIMELINE_US: Long = 3_600_000_000L
    }
}
