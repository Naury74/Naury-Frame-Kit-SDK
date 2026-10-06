package com.naury.framekit.ui.image.contract

import android.os.Parcelable
import com.naury.framekit.core.validation.ValidationCode
import com.naury.framekit.core.validation.ValidationIssue
import com.naury.framekit.core.validation.ValidationResult
import kotlinx.parcelize.Parcelize

/**
 * 이미지 에디터의 동작.
 *
 * @property enabledTools 레일에 표시할 도구. 비활성 도구는 숨겨지고 그 편집은 거부된다.
 *   빈 집합이면 저장만 가능한 미리보기로 열린다.
 * @property allowUndo 실행 취소 버튼을 표시한다. [allowRedo]가 `true`이면 반드시 `true`여야 한다.
 * @property allowRedo 다시 실행 버튼을 표시한다.
 */
@Parcelize
public data class ImageEditorConfig(
    val enabledTools: Set<ImageTool> = ImageTool.defaults,
    val allowUndo: Boolean = true,
    val allowRedo: Boolean = true,
) : Parcelable {

    public fun validate(): ValidationResult {
        val issues = mutableListOf<ValidationIssue>()
        if (allowRedo && !allowUndo) {
            issues += ValidationIssue(ValidationCode.OUT_OF_RANGE, "image.allowRedo", "Redo requires undo")
        }
        return ValidationResult.of(issues)
    }
}
