package com.naury.framekit

import android.os.Parcelable
import com.naury.framekit.android.input.EditorInput
import com.naury.framekit.android.output.OutputTarget
import com.naury.framekit.core.validation.ValidationIssue
import com.naury.framekit.core.validation.ValidationResult
import com.naury.framekit.image.export.ImageExportConfig
import com.naury.framekit.ui.config.EditorUiConfig
import com.naury.framekit.ui.image.contract.ImageEditorConfig
import com.naury.framekit.ui.image.contract.ImageEditorRequest
import com.naury.framekit.ui.video.contract.VideoEditorConfig
import com.naury.framekit.ui.video.contract.VideoEditorRequest
import com.naury.framekit.video.export.VideoExportConfig
import kotlinx.parcelize.Parcelize

/**
 * Launch request for [FrameKitContract]. FrameKit opens the photo or the video editor depending on
 * the source, so both configurations are given up front.
 *
 * `EditorInput.Pick(MediaKind.ANY)` lets the user pick a photo or a video in the system picker.
 */
@Parcelize
public data class FrameKitRequest(
    val input: EditorInput,
    val image: ImageEditorConfig = ImageEditorConfig(),
    val imageExport: ImageExportConfig = ImageExportConfig(),
    val video: VideoEditorConfig = VideoEditorConfig(),
    val videoExport: VideoExportConfig = VideoExportConfig(),
    val ui: EditorUiConfig = EditorUiConfig(),
    val output: OutputTarget = OutputTarget.AppFile,
) : Parcelable {

    /** Validates both editor configurations; the result is `INVALID_CONFIGURATION` when this fails. */
    public fun validate(): ValidationResult {
        val issues = mutableListOf<ValidationIssue>()
        listOf(image.validate(), imageExport.validate(), video.validate(), videoExport.validate(), ui.validate()).forEach { result ->
            if (result is ValidationResult.Invalid) issues += result.issues
        }
        return ValidationResult.of(issues)
    }

    internal fun forImage(input: EditorInput): ImageEditorRequest = ImageEditorRequest(input, image, imageExport, ui, output)

    internal fun forVideo(input: EditorInput): VideoEditorRequest = VideoEditorRequest(input, video, videoExport, ui, output)
}
