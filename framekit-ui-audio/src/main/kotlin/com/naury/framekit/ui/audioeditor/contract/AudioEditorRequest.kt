package com.naury.framekit.ui.audioeditor.contract

import android.os.Parcelable
import com.naury.framekit.android.input.EditorInput
import com.naury.framekit.android.input.MediaKind
import com.naury.framekit.android.input.isSingleSource
import com.naury.framekit.android.output.OutputTarget
import com.naury.framekit.core.validation.ValidationCode
import com.naury.framekit.core.validation.ValidationIssue
import com.naury.framekit.core.validation.ValidationResult
import com.naury.framekit.ui.config.EditorUiConfig
import kotlinx.parcelize.Parcelize

/**
 * [AudioEditorContract]의 실행 요청.
 *
 * | 입력 | 동작 |
 * | --- | --- |
 * | `Capture(MediaKind.AUDIO)` | 바로 녹음 화면을 연다. 녹음이 끝나면 편집 화면으로 넘어가거나([AudioEditorConfig.editAfterRecording]) 바로 돌려준다 |
 * | `Pick(MediaKind.AUDIO, n)` | 시스템 파일 선택기로 소리 파일을 고른 뒤 편집한다(여러 개면 순서대로 이어 붙임) |
 * | `UriSource`·`FileSource`·`Multiple` | 넘긴 소리 파일을 편집한다 |
 */
@Parcelize
public data class AudioEditorRequest(
    val input: EditorInput,
    val config: AudioEditorConfig = AudioEditorConfig(),
    val export: AudioExportConfig = AudioExportConfig(),
    val ui: EditorUiConfig = EditorUiConfig(),
    val output: OutputTarget = OutputTarget.AppFile,
) : Parcelable {

    /** 요청을 검증한다. 실패하면 편집기는 `INVALID_CONFIGURATION`을 돌려준다. */
    public fun validate(): ValidationResult {
        val issues = mutableListOf<ValidationIssue>()
        when (input) {
            is EditorInput.Pick -> {
                if (input.kind != MediaKind.AUDIO) issues += ValidationIssue(ValidationCode.OUT_OF_RANGE, "input.kind", "The audio editor picks audio only")
                if (input.maxItems !in 1..config.maxSourceCount) issues += ValidationIssue(ValidationCode.OUT_OF_RANGE, "input.maxItems", "Expected 1..${config.maxSourceCount}")
            }
            is EditorInput.Capture -> if (input.kind != MediaKind.AUDIO) issues += ValidationIssue(ValidationCode.OUT_OF_RANGE, "input.kind", "The audio editor records audio only")
            is EditorInput.Multiple -> {
                if (input.items.isEmpty() || input.items.size > config.maxSourceCount) issues += ValidationIssue(ValidationCode.OUT_OF_RANGE, "input.items", "Expected 1..${config.maxSourceCount} items")
                if (input.items.any { !it.isSingleSource }) issues += ValidationIssue(ValidationCode.OUT_OF_RANGE, "input.items", "Items must be Uri or file sources")
            }
            is EditorInput.UriSource, is EditorInput.FileSource -> Unit
        }
        listOf(config.validate(), export.validate(), ui.validate()).forEach { if (it is ValidationResult.Invalid) issues += it.issues }
        return ValidationResult.of(issues)
    }
}
