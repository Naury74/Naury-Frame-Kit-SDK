package com.naury.framekit.ui.video.contract

import com.naury.framekit.android.catalog.EditorCatalog
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
 * [VideoEditorContract]의 실행 요청. 모든 필드가 Parcelable이므로 요청은 Activity 재생성 후에도
 * 유지된다.
 */
@Parcelize
public data class VideoEditorRequest(
    val input: EditorInput,
    val config: VideoEditorConfig = VideoEditorConfig(),
    val export: VideoExportConfig = VideoExportConfig(),
    val ui: EditorUiConfig = EditorUiConfig(),
    val output: OutputTarget = OutputTarget.AppFile,
    val catalog: EditorCatalog = EditorCatalog(),
) : Parcelable {

    /** 요청의 모든 부분을 검증한다. 검증에 실패하면 에디터는 `INVALID_CONFIGURATION`을 반환한다. */
    public fun validate(): ValidationResult {
        val issues = mutableListOf<ValidationIssue>()
        if (input is EditorInput.Pick && input.kind != MediaKind.VIDEO) {
            issues += ValidationIssue(ValidationCode.OUT_OF_RANGE, "input.kind", "The video editor can only pick videos")
        }
        listOf(config.validate(), export.validate(), ui.validate(), catalog.validate()).forEach { result ->
            if (result is ValidationResult.Invalid) issues += result.issues
        }
        return ValidationResult.of(issues)
    }
}
