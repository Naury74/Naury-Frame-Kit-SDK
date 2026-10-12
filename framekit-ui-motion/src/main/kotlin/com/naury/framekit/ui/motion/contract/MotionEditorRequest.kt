package com.naury.framekit.ui.motion.contract

import android.os.Parcelable
import com.naury.framekit.android.catalog.EditorCatalog
import com.naury.framekit.android.input.EditorInput
import com.naury.framekit.android.input.MediaKind
import com.naury.framekit.android.input.isSingleSource
import com.naury.framekit.android.output.OutputTarget
import com.naury.framekit.core.validation.ValidationCode
import com.naury.framekit.core.validation.ValidationIssue
import com.naury.framekit.core.validation.ValidationResult
import com.naury.framekit.motion.export.MotionExportConfig
import com.naury.framekit.ui.config.EditorUiConfig
import kotlinx.parcelize.Parcelize

/**
 * [MotionEditorContract]의 실행 요청.
 *
 * - 사진 여러 장(또는 한 장): 움직임·전환을 넣은 슬라이드쇼를 만든다.
 * - GIF·움직이는 WebP 하나: 그 움직이는 이미지를 편집한다(구간·속도·반복·보정·텍스트 등).
 *
 * @property input `Pick(MediaKind.IMAGE, n)`, `Multiple`, `UriSource`, `FileSource`를 받는다. 카메라 촬영은 받지 않는다.
 */
@Parcelize
public data class MotionEditorRequest(
    val input: EditorInput,
    val config: MotionEditorConfig = MotionEditorConfig(),
    val export: MotionExportConfig = MotionExportConfig(),
    val ui: EditorUiConfig = EditorUiConfig(),
    val output: OutputTarget = OutputTarget.AppFile,
    val catalog: EditorCatalog = EditorCatalog(),
) : Parcelable {

    /** 요청을 검증한다. 실패하면 편집기는 `INVALID_CONFIGURATION`을 돌려준다. */
    public fun validate(): ValidationResult {
        val issues = mutableListOf<ValidationIssue>()
        when (input) {
            is EditorInput.Pick -> {
                if (input.kind != MediaKind.IMAGE) issues += ValidationIssue(ValidationCode.OUT_OF_RANGE, "input.kind", "The motion editor picks images only")
                if (input.maxItems !in 1..config.maxImageCount) issues += ValidationIssue(ValidationCode.OUT_OF_RANGE, "input.maxItems", "Expected 1..${config.maxImageCount}")
            }
            is EditorInput.Multiple -> {
                if (input.items.isEmpty() || input.items.size > config.maxImageCount) {
                    issues += ValidationIssue(ValidationCode.OUT_OF_RANGE, "input.items", "Expected 1..${config.maxImageCount} items")
                }
                if (input.items.any { !it.isSingleSource }) issues += ValidationIssue(ValidationCode.OUT_OF_RANGE, "input.items", "Items must be Uri or file sources")
            }
            is EditorInput.Capture -> issues += ValidationIssue(ValidationCode.OUT_OF_RANGE, "input", "The motion editor does not capture")
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
