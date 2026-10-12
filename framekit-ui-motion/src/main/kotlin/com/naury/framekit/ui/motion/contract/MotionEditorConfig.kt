package com.naury.framekit.ui.motion.contract

import android.os.Parcelable
import com.naury.framekit.core.validation.ValidationCode
import com.naury.framekit.core.validation.ValidationIssue
import com.naury.framekit.core.validation.ValidationResult
import com.naury.framekit.motion.model.MotionProject
import kotlinx.parcelize.Parcelize

/**
 * 움직이는 이미지 편집기 설정.
 *
 * @property enabledTools 보여 줄 도구. 원본 종류에 맞지 않는 도구(예: 움직이는 원본의 전환)는 자동으로 숨긴다.
 * @property maxImageCount 슬라이드쇼에 넣을 수 있는 최대 사진 수(1..[MAX_IMAGES]).
 * @property slideDurationMs 사진 한 장의 처음 시간(밀리초).
 * @property maxAnimationFrames 움직이는 원본에서 읽을 최대 프레임 수(10..[MAX_FRAMES]). 넘는 프레임은 버린다.
 * @property allowUndo 실행 취소 버튼을 보여 준다.
 * @property allowRedo 다시 실행 버튼을 보여 준다.
 */
@Parcelize
public data class MotionEditorConfig(
    val enabledTools: Set<MotionTool> = MotionTool.entries.toSet(),
    val maxImageCount: Int = 20,
    val slideDurationMs: Long = MotionProject.DEFAULT_SLIDE_MS,
    val maxAnimationFrames: Int = 300,
    val allowUndo: Boolean = true,
    val allowRedo: Boolean = true,
) : Parcelable {

    public fun validate(): ValidationResult {
        val issues = mutableListOf<ValidationIssue>()
        if (maxImageCount !in 1..MAX_IMAGES) issues += ValidationIssue(ValidationCode.OUT_OF_RANGE, "config.maxImageCount", "Expected 1..$MAX_IMAGES")
        if (slideDurationMs !in MotionProject.MIN_SLIDE_MS..MotionProject.MAX_SLIDE_MS) {
            issues += ValidationIssue(ValidationCode.OUT_OF_RANGE, "config.slideDurationMs", "Expected ${MotionProject.MIN_SLIDE_MS}..${MotionProject.MAX_SLIDE_MS}")
        }
        if (maxAnimationFrames !in 10..MAX_FRAMES) issues += ValidationIssue(ValidationCode.OUT_OF_RANGE, "config.maxAnimationFrames", "Expected 10..$MAX_FRAMES")
        return ValidationResult.of(issues)
    }

    public companion object {
        public const val MAX_IMAGES: Int = 50
        public const val MAX_FRAMES: Int = 1_000
    }
}
