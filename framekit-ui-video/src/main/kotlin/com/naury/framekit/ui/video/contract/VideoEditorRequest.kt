package com.naury.framekit.ui.video.contract

import android.os.Parcelable
import com.naury.framekit.android.input.EditorInput
import com.naury.framekit.android.input.MediaKind
import com.naury.framekit.android.output.OutputTarget
import com.naury.framekit.core.validation.ValidationCode
import com.naury.framekit.core.validation.ValidationIssue
import com.naury.framekit.core.validation.ValidationResult
import com.naury.framekit.ui.config.EditorUiConfig
import com.naury.framekit.video.export.VideoExportConfig
import kotlinx.parcelize.Parcelize

/**
 * Launch request for [VideoEditorContract]. Every field is Parcelable, so the request survives
 * Activity recreation.
 */
@Parcelize
public data class VideoEditorRequest(
    val input: EditorInput,
    val config: VideoEditorConfig = VideoEditorConfig(),
    val export: VideoExportConfig = VideoExportConfig(),
    val ui: EditorUiConfig = EditorUiConfig(),
    val output: OutputTarget = OutputTarget.AppFile,
) : Parcelable {

    /** Validates every part of the request. The editor returns `INVALID_CONFIGURATION` when this fails. */
    public fun validate(): ValidationResult {
        val issues = mutableListOf<ValidationIssue>()
        if (input is EditorInput.Pick && input.kind != MediaKind.VIDEO) {
            issues += ValidationIssue(ValidationCode.OUT_OF_RANGE, "input.kind", "The video editor can only pick videos")
        }
        listOf(config.validate(), export.validate(), ui.validate()).forEach { result ->
            if (result is ValidationResult.Invalid) issues += result.issues
        }
        return ValidationResult.of(issues)
    }
}
