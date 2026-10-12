package com.naury.framekit

import com.naury.framekit.android.input.isSingleSource
import com.naury.framekit.android.input.MediaKind
import com.naury.framekit.core.validation.ValidationCode
import com.naury.framekit.android.catalog.EditorCatalog
import android.os.Parcelable
import com.naury.framekit.android.input.EditorInput
import com.naury.framekit.android.output.OutputTarget
import com.naury.framekit.core.validation.ValidationIssue
import com.naury.framekit.core.validation.ValidationResult
import com.naury.framekit.image.export.ImageExportConfig
import com.naury.framekit.image.export.ImageFormat
import com.naury.framekit.ui.config.EditorUiConfig
import com.naury.framekit.ui.image.contract.ImageEditorConfig
import com.naury.framekit.ui.image.contract.ImageEditorRequest
import com.naury.framekit.ui.video.contract.VideoEditorConfig
import com.naury.framekit.ui.video.contract.VideoEditorRequest
import com.naury.framekit.video.export.VideoExportConfig
import kotlinx.parcelize.Parcelize
import com.naury.framekit.ui.audioeditor.contract.AudioEditorConfig
import com.naury.framekit.ui.audioeditor.contract.AudioEditorRequest
import com.naury.framekit.ui.audioeditor.contract.AudioExportConfig
import com.naury.framekit.motion.export.MotionExportConfig
import com.naury.framekit.ui.motion.contract.MotionEditorConfig
import com.naury.framekit.ui.motion.contract.MotionEditorRequest

/**
 * [FrameKitContract] 실행 요청이다. FrameKit은 소스에 따라 사진 편집기 또는 영상 편집기를 열기 때문에
 * 두 편집기 설정을 모두 미리 전달한다.
 *
 * `EditorInput.Pick(MediaKind.ANY)`를 쓰면 사용자가 시스템 picker에서 사진이나 영상을 고를 수 있다.
 *
 * @property chooser 원본을 받은 뒤 편집기를 자동으로 고를지([EditorChooser.AUTO]), 선택 시트로 물을지([EditorChooser.ASK]).
 * @property chooserActions [EditorChooser.ASK]일 때 보여 줄 동작. 원본에 맞지 않는 동작은 빠진다. 비어 있으면 안 된다.
 * @property openAnimatedInMotionEditor 고른(또는 넘긴) 사진 하나가 움직이는 이미지(GIF·움직이는 WebP)면 사진 편집기 대신
 *   움직이는 이미지 편집기로 연다. `false`면 사진 편집기가 첫 프레임을 편집한다.
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
    val motion: MotionEditorConfig = MotionEditorConfig(),
    val motionExport: MotionExportConfig = MotionExportConfig(),
    val openAnimatedInMotionEditor: Boolean = true,
    val chooser: EditorChooser = EditorChooser.AUTO,
    val chooserActions: Set<EditorAction> = EditorAction.entries.toSet(),
    val audio: AudioEditorConfig = AudioEditorConfig(),
    val audioExport: AudioExportConfig = AudioExportConfig(),
) : Parcelable {

    /** 두 편집기 설정을 모두 검증한다. 검증에 실패하면 결과는 `INVALID_CONFIGURATION`이 된다. */
    public fun validate(): ValidationResult {
        val issues = mutableListOf<ValidationIssue>()
        listOf(image.validate(), imageExport.validate(), video.validate(), videoExport.validate(), motion.validate(), motionExport.validate(), audio.validate(), audioExport.validate(), ui.validate(), catalog.validate()).forEach { result ->
            if (result is ValidationResult.Invalid) issues += result.issues
        }
        when (input) {
            is EditorInput.Pick -> if (input.maxItems !in 1..EditorInput.MAX_PICK_ITEMS) {
                issues += ValidationIssue(ValidationCode.OUT_OF_RANGE, "input.maxItems", "Expected 1..${EditorInput.MAX_PICK_ITEMS}")
            }
            is EditorInput.Capture -> if (input.kind == MediaKind.ANY) {
                issues += ValidationIssue(ValidationCode.OUT_OF_RANGE, "input.kind", "Capture needs IMAGE or VIDEO")
            }
            is EditorInput.Multiple -> if (input.items.isEmpty() || input.items.any { !it.isSingleSource }) {
                issues += ValidationIssue(ValidationCode.OUT_OF_RANGE, "input.items", "Items must be Uri or file sources")
            }
            is EditorInput.UriSource, is EditorInput.FileSource -> Unit
        }
        if (chooserActions.isEmpty()) issues += ValidationIssue(ValidationCode.OUT_OF_RANGE, "chooserActions", "At least one action is required")
        if (ui.uiFontId != null && catalog.fonts.none { it.id == ui.uiFontId }) {
            issues += ValidationIssue(ValidationCode.UNKNOWN_REFERENCE, "ui.uiFontId", "Font is not in the catalog")
        }
        return ValidationResult.of(issues)
    }

    // 편집기마다 개수 상한이 달라 고를 수 있는 개수를 그 편집기에 맞춰 줄인다.
    internal fun forImage(input: EditorInput): ImageEditorRequest =
        ImageEditorRequest(input.limitedTo(MediaKind.IMAGE, image.maxImageCount), image, imageExport, ui, output, catalog)

    internal fun forVideo(input: EditorInput): VideoEditorRequest =
        VideoEditorRequest(input.limitedTo(MediaKind.VIDEO, video.maxClipCount), video, videoExport, ui, output, catalog)

    internal fun forMotion(input: EditorInput): MotionEditorRequest =
        MotionEditorRequest(input.limitedTo(MediaKind.IMAGE, motion.maxImageCount), motion, motionExport, ui, output, catalog)

    internal fun forAudio(input: EditorInput): AudioEditorRequest =
        AudioEditorRequest(input.limitedTo(MediaKind.AUDIO, audio.maxSourceCount), audio, audioExport, ui, output)

    /** 사진 편집기로 열고 PDF 하나로 저장한다. */
    internal fun forPdf(input: EditorInput): ImageEditorRequest =
        forImage(input).copy(export = imageExport.copy(format = ImageFormat.PDF))

    private fun EditorInput.limitedTo(kind: MediaKind, limit: Int): EditorInput = when (this) {
        is EditorInput.Pick -> EditorInput.Pick(kind, maxItems.coerceIn(1, limit))
        is EditorInput.Capture -> EditorInput.Capture(kind)
        // 편집기마다 넣을 수 있는 개수가 달라, 넘치면 앞에서부터 그 수만큼만 연다.
        is EditorInput.Multiple -> if (items.size > limit) EditorInput.Multiple(items.take(limit)) else this
        else -> this
    }
}
