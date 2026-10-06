package com.naury.framekit.video.export

import android.os.Parcelable
import com.naury.framekit.core.validation.ValidationCode
import com.naury.framekit.core.validation.ValidationIssue
import com.naury.framekit.core.validation.ValidationResult
import kotlinx.parcelize.Parcelize

/**
 * 영상 내보내기 출력 설정이다. 출력은 H.264 영상과 AAC 오디오를 담은 SDR MP4다.
 *
 * @property maxShortSide 출력 짧은 변의 상한(픽셀). 1080이면 가로·세로 영상 모두 1080p가 된다.
 *   출력은 편집된 원본보다 커지지 않는다.
 * @property maxFrameRate 이 frame rate를 넘는 프레임은 버린다. 실제 frame rate는 결과에 보고한다.
 * @property allowFallback `false`면 인코더가 다른 해상도나 코덱 설정을 제안할 때 받아들이지 않고
 *   내보내기를 실패시킨다.
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
