package com.naury.framekit

import com.naury.framekit.android.catalog.EditorCatalog
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
 * [FrameKitContract] 실행 요청이다. FrameKit은 소스에 따라 사진 편집기 또는 영상 편집기를 열기 때문에
 * 두 편집기 설정을 모두 미리 전달한다.
 *
 * `EditorInput.Pick(MediaKind.ANY)`를 쓰면 사용자가 시스템 picker에서 사진이나 영상을 고를 수 있다.
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
    val catalog: EditorCatalog = EditorCatalog(),
) : Parcelable {

    /** 두 편집기 설정을 모두 검증한다. 검증에 실패하면 결과는 `INVALID_CONFIGURATION`이 된다. */
    public fun validate(): ValidationResult {
        val issues = mutableListOf<ValidationIssue>()
        listOf(image.validate(), imageExport.validate(), video.validate(), videoExport.validate(), ui.validate(), catalog.validate()).forEach { result ->
            if (result is ValidationResult.Invalid) issues += result.issues
        }
        return ValidationResult.of(issues)
    }

    internal fun forImage(input: EditorInput): ImageEditorRequest = ImageEditorRequest(input, image, imageExport, ui, output, catalog)

    internal fun forVideo(input: EditorInput): VideoEditorRequest = VideoEditorRequest(input, video, videoExport, ui, output, catalog)
}
