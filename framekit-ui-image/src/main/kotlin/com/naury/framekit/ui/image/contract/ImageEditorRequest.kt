package com.naury.framekit.ui.image.contract

import com.naury.framekit.android.catalog.EditorCatalog
import android.os.Parcelable
import com.naury.framekit.android.input.EditorInput
import com.naury.framekit.android.input.MediaKind
import com.naury.framekit.android.input.isSingleSource
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
    val catalog: EditorCatalog = EditorCatalog(),
) : Parcelable {

    /** 요청의 모든 부분을 검증한다. 검증에 실패하면 에디터는 `INVALID_CONFIGURATION`을 반환한다. */
    public fun validate(): ValidationResult {
        val issues = mutableListOf<ValidationIssue>()
        when (input) {
            is EditorInput.Pick -> {
                if (input.kind != MediaKind.IMAGE) issues += ValidationIssue(ValidationCode.OUT_OF_RANGE, "input.kind", "The image editor can only pick images")
                if (input.maxItems !in 1..config.maxImageCount) {
                    issues += ValidationIssue(ValidationCode.OUT_OF_RANGE, "input.maxItems", "Expected 1..${config.maxImageCount}")
                }
            }
            is EditorInput.Capture -> if (input.kind != MediaKind.IMAGE) {
                issues += ValidationIssue(ValidationCode.OUT_OF_RANGE, "input.kind", "The image editor can only capture photos")
            }
            is EditorInput.Multiple -> {
                if (input.items.isEmpty() || input.items.size > config.maxImageCount) {
                    issues += ValidationIssue(ValidationCode.OUT_OF_RANGE, "input.items", "Expected 1..${config.maxImageCount} items")
                }
                if (input.items.any { !it.isSingleSource }) {
                    issues += ValidationIssue(ValidationCode.OUT_OF_RANGE, "input.items", "Items must be Uri or file sources")
                }
            }
            is EditorInput.UriSource, is EditorInput.FileSource -> Unit
        }
        listOf(config.validate(), export.validate(), ui.validate(), catalog.validate()).forEach { result ->
            if (result is ValidationResult.Invalid) issues += result.issues
        }
        if (ui.uiFontId != null && catalog.fonts.none { it.id == ui.uiFontId }) {
            issues += ValidationIssue(ValidationCode.UNKNOWN_REFERENCE, "ui.uiFontId", "Font is not in the catalog")
        }
        return ValidationResult.of(issues)
    }
}
