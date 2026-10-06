package com.naury.framekit.ui.video.contract

import android.os.Parcelable
import com.naury.framekit.core.validation.ValidationCode
import com.naury.framekit.core.validation.ValidationIssue
import com.naury.framekit.core.validation.ValidationResult
import kotlinx.parcelize.Parcelize

/**
 * 영상 에디터의 동작.
 *
 * @property enabledTools 레일에 표시할 도구. 빈 집합이면 저장만 가능한 미리보기로 열린다.
 * @property minClipDurationUs 사용자가 트림이나 배속으로 줄일 수 있는 가장 짧은 결과 길이(출력 시간 기준).
 * @property maxTimelineDurationUs 가장 긴 결과 길이. 이보다 긴 소스는 이 길이로 트림된 상태로 열린다.
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
        /** 30 fps 기준 약 3프레임. 이보다 짧은 클립은 안정적으로 인코딩할 수 없다. */
        public const val MIN_CLIP_US: Long = 100_000L

        /** 1시간. */
        public const val MAX_TIMELINE_US: Long = 3_600_000_000L
    }
}
