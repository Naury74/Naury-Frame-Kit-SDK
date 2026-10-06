package com.naury.framekit.ui.image.contract

import android.os.Parcelable
import com.naury.framekit.core.validation.ValidationCode
import com.naury.framekit.core.validation.ValidationIssue
import com.naury.framekit.core.validation.ValidationResult
import kotlinx.parcelize.Parcelize

/**
 * Behavior of the image editor.
 *
 * @property enabledTools tools shown in the rail. Disabled tools are hidden and their edits are
 *   rejected. An empty set opens a preview that can only be saved.
 * @property allowUndo shows the undo button. Must be `true` when [allowRedo] is `true`.
 * @property allowRedo shows the redo button.
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
