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
 * [ImageEditorContract]의 실행 요청.
 *
 * 모든 필드가 원시 타입 또는 Parcelable 참조이므로 요청은 Activity 재생성 후에도 유지된다.
 * Bitmap과 콜백은 의도적으로 받지 않는다.
 */
@Parcelize
public data class ImageEditorRequest(
    val input: EditorInput,
    val config: ImageEditorConfig = ImageEditorConfig(),
    val export: ImageExportConfig = ImageExportConfig(),
    val ui: EditorUiConfig = EditorUiConfig(),
    val output: OutputTarget = OutputTarget.AppFile,
) : Parcelable {

    /** 요청의 모든 부분을 검증한다. 검증에 실패하면 에디터는 `INVALID_CONFIGURATION`을 반환한다. */
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
