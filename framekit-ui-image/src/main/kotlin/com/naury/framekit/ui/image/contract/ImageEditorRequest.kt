package com.naury.framekit.ui.image.contract

import android.os.Parcelable
import com.naury.framekit.android.input.EditorInput
import com.naury.framekit.android.input.MediaKind
import com.naury.framekit.android.output.OutputTarget
import com.naury.framekit.core.validation.ValidationCode
import com.naury.framekit.core.validation.ValidationIssue
import com.naury.framekit.core.validation.ValidationResult
import com.naury.framekit.image.export.ImageExportConfig
import com.naury.framekit.ui.config.EditorUiConfig
import kotlinx.parcelize.Parcelize

/**
 * Launch request for [ImageEditorContract].
 *
 * Every field is a primitive or a Parcelable reference, so the request survives Activity recreation.
 * Bitmaps and callbacks are intentionally not accepted.
 */
@Parcelize
public data class ImageEditorRequest(
    val input: EditorInput,
    val config: ImageEditorConfig = ImageEditorConfig(),
    val export: ImageExportConfig = ImageExportConfig(),
    val ui: EditorUiConfig = EditorUiConfig(),
    val output: OutputTarget = OutputTarget.AppFile,
) : Parcelable {

    /** Validates every part of the request. The editor returns `INVALID_CONFIGURATION` when this fails. */
    public fun validate(): ValidationResult {
        val issues = mutableListOf<ValidationIssue>()
        if (input is EditorInput.Pick && input.kind != MediaKind.IMAGE) {
            issues += ValidationIssue(ValidationCode.OUT_OF_RANGE, "input.kind", "The image editor can only pick images")
        }
        listOf(config.validate(), export.validate(), ui.validate()).forEach { result ->
            if (result is ValidationResult.Invalid) issues += result.issues
        }
        return ValidationResult.of(issues)
    }
}
