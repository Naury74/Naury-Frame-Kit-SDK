package com.naury.framekit.ui.video.contract

import com.naury.framekit.android.input.isSingleSource
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
        when (input) {
            is EditorInput.Pick -> {
                if (input.kind != MediaKind.VIDEO) issues += ValidationIssue(ValidationCode.OUT_OF_RANGE, "input.kind", "The video editor can only pick videos")
                if (input.maxItems !in 1..config.maxClipCount) {
                    issues += ValidationIssue(ValidationCode.OUT_OF_RANGE, "input.maxItems", "Expected 1..${config.maxClipCount}")
                }
            }
            is EditorInput.Capture -> if (input.kind != MediaKind.VIDEO) {
                issues += ValidationIssue(ValidationCode.OUT_OF_RANGE, "input.kind", "The video editor can only record videos")
            }
            is EditorInput.Multiple -> {
                if (input.items.isEmpty() || input.items.size > config.maxClipCount) {
                    issues += ValidationIssue(ValidationCode.OUT_OF_RANGE, "input.items", "Expected 1..${config.maxClipCount} items")
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
