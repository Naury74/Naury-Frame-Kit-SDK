package com.naury.framekit.ui.image.contract

import com.naury.framekit.ui.config.WatermarkConfig
import android.os.Parcelable
import com.naury.framekit.core.validation.ValidationCode
import com.naury.framekit.core.validation.ValidationIssue
import com.naury.framekit.core.validation.ValidationResult
import com.naury.framekit.android.input.EditorInput
import kotlinx.parcelize.Parcelize

/**
 * 이미지 에디터의 동작.
 *
 * @property enabledTools 레일에 표시할 도구. 비활성 도구는 숨겨지고 그 편집은 거부된다.
 *   빈 집합이면 저장만 가능한 미리보기로 열린다.
 * @property allowUndo 실행 취소 버튼을 표시한다. [allowRedo]가 `true`이면 반드시 `true`여야 한다.
 * @property allowRedo 다시 실행 버튼을 표시한다.
 * @property maxImageCount 한 번에 편집할 수 있는 사진 수, `1..100`. 여러 장 입력([EditorInput.Pick]의
 *   `maxItems`, [EditorInput.Multiple])과 사진 추가는 이 수를 넘지 않는다.
 * @property watermark 호스트가 정하는 워터마크(미리 채울 문구, 열 때 바로 얹기, 잠금). `null`이면 사용자가 도구에서 직접 만든다.
 *   여러 장이면 모든 사진에 같은 워터마크가 들어간다.
 */
@Parcelize
public data class ImageEditorConfig(
    val enabledTools: Set<ImageTool> = ImageTool.defaults,
    val allowUndo: Boolean = true,
    val allowRedo: Boolean = true,
    val maxImageCount: Int = 20,
    val watermark: WatermarkConfig? = null,
) : Parcelable {

    public fun validate(): ValidationResult {
        val issues = mutableListOf<ValidationIssue>()
        if (allowRedo && !allowUndo) {
            issues += ValidationIssue(ValidationCode.OUT_OF_RANGE, "image.allowRedo", "Redo requires undo")
        }
        if (maxImageCount !in 1..MAX_IMAGES) {
            issues += ValidationIssue(ValidationCode.OUT_OF_RANGE, "image.maxImageCount", "Expected 1..$MAX_IMAGES")
        }
        (watermark?.validate("image.watermark") as? ValidationResult.Invalid)?.let { issues += it.issues }
        return ValidationResult.of(issues)
    }

    public companion object {
        public const val MAX_IMAGES: Int = 100
    }
}
