package com.naury.framekit.core.validation

import com.naury.framekit.core.overlay.Watermark
import com.naury.framekit.core.overlay.DrawingStroke
import com.naury.framekit.core.overlay.StickerCatalog
import com.naury.framekit.core.overlay.FontCatalog
import com.naury.framekit.core.overlay.ImageOverlay
import com.naury.framekit.core.overlay.MaskShape
import com.naury.framekit.core.overlay.OverlayTransform
import com.naury.framekit.core.overlay.PrivacyEffect
import com.naury.framekit.core.overlay.PrivacyMask
import com.naury.framekit.core.overlay.ShapeStyle

/** 텍스트, 스티커, 도형, 그리기 획의 검증. */
internal object OverlayValidator {

    fun validate(overlays: List<ImageOverlay>, drawing: List<DrawingStroke>, issues: MutableList<ValidationIssue>) {
        val ids = overlays.map { it.id } + drawing.map { it.id }
        ids.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.forEach { id ->
            issues += ValidationIssue(ValidationCode.DUPLICATE_ID, "overlays", "Duplicate id $id")
        }
        overlays.forEachIndexed { index, overlay ->
            val path = "overlays[$index]"
            validateTransform(overlay.transform, path, issues)
            when (overlay) {
                is ImageOverlay.Text -> {
                    if (!FontCatalog.contains(overlay.style.fontId)) {
                        issues += ValidationIssue(ValidationCode.UNKNOWN_REFERENCE, "$path.style.fontId", "Unknown font")
                    }
                    val style = overlay.style
                    if (!positive(style.fontSizeHeightRatio) || !positive(style.maxWidthRatio) || !positive(style.lineSpacingMultiplier) ||
                        !style.letterSpacingEm.isFinite()
                    ) {
                        issues += ValidationIssue(ValidationCode.OUT_OF_RANGE, "$path.style", "Sizes must be positive and finite")
                    }
                }
                is ImageOverlay.Sticker -> {
                    if (!StickerCatalog.contains(overlay.assetId)) {
                        issues += ValidationIssue(ValidationCode.UNKNOWN_REFERENCE, "$path.assetId", "Unknown sticker asset")
                    }
                    if (!positive(overlay.widthRatio)) {
                        issues += ValidationIssue(ValidationCode.OUT_OF_RANGE, "$path.widthRatio", "Must be positive")
                    }
                }
                is ImageOverlay.Shape -> {
                    val max = ImageOverlay.Shape.MAX_SIZE_RATIO
                    // 선·화살표는 높이를 쓰지 않으므로 0이어도 된다.
                    val heightOk = overlay.heightRatio.isFinite() && overlay.heightRatio <= max &&
                        (if (overlay.kind.isLine) overlay.heightRatio >= 0.0 else overlay.heightRatio > 0.0)
                    if (!positive(overlay.widthRatio) || overlay.widthRatio > max || !heightOk) {
                        issues += ValidationIssue(ValidationCode.OUT_OF_RANGE, "$path.size", "Expected width 0..$max and height 0..$max")
                    }
                    val stroke = overlay.style.strokeWidthShortEdgeRatio
                    if (!positive(stroke) || stroke > ShapeStyle.MAX_STROKE_RATIO) {
                        issues += ValidationIssue(ValidationCode.OUT_OF_RANGE, "$path.style.strokeWidthShortEdgeRatio", "Expected 0..${ShapeStyle.MAX_STROKE_RATIO}")
                    }
                }
            }
        }
        drawing.forEachIndexed { index, stroke ->
            val path = "drawing[$index]"
            if (stroke.points.isEmpty() || stroke.points.size > DrawingStroke.MAX_POINTS) {
                issues += ValidationIssue(ValidationCode.OUT_OF_RANGE, "$path.points", "Expected 1..${DrawingStroke.MAX_POINTS} points")
            }
            if (stroke.points.any { !it.x.isFinite() || !it.y.isFinite() || it.pressure !in 0.0..1.0 }) {
                issues += ValidationIssue(ValidationCode.NOT_FINITE, "$path.points", "Points must be finite with pressure 0..1")
            }
            if (!positive(stroke.widthShortEdgeRatio) || stroke.opacity !in 0.0..1.0) {
                issues += ValidationIssue(ValidationCode.OUT_OF_RANGE, path, "Width must be positive and opacity 0..1")
            }
        }
    }

    fun validatePrivacy(masks: List<PrivacyMask>, issues: MutableList<ValidationIssue>) {
        masks.map { it.id }.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.forEach { id ->
            issues += ValidationIssue(ValidationCode.DUPLICATE_ID, "privacyMasks", "Duplicate id $id")
        }
        masks.forEachIndexed { index, mask ->
            val path = "privacyMasks[$index]"
            val ratio = when (val effect = mask.effect) {
                is PrivacyEffect.Blur -> effect.radiusShortEdgeRatio
                is PrivacyEffect.Mosaic -> effect.blockShortEdgeRatio
            }
            if (!positive(ratio) || ratio > PrivacyEffect.MAX_RATIO) {
                issues += ValidationIssue(ValidationCode.OUT_OF_RANGE, "$path.effect", "Expected 0..${PrivacyEffect.MAX_RATIO}")
            }
            val valid = when (val shape = mask.shape) {
                is MaskShape.Brush -> shape.points.isNotEmpty() && shape.points.size <= DrawingStroke.MAX_POINTS &&
                    shape.points.all { it.isFinite } && positive(shape.widthShortEdgeRatio)
                is MaskShape.Rectangle -> shape.rect.isFinite && shape.rect.width > 0 && shape.rect.height > 0
                is MaskShape.Ellipse -> shape.rect.isFinite && shape.rect.width > 0 && shape.rect.height > 0
            }
            if (!valid) issues += ValidationIssue(ValidationCode.INVALID_RECT, "$path.shape", "Shape must be finite and non-empty")
        }
    }

    private fun validateTransform(transform: OverlayTransform, path: String, issues: MutableList<ValidationIssue>) {
        val values = listOf(transform.center.x, transform.center.y, transform.scale, transform.rotationDegrees, transform.opacity)
        if (values.any { !it.isFinite() }) {
            issues += ValidationIssue(ValidationCode.NOT_FINITE, "$path.transform", "Must be finite")
            return
        }
        if (transform.scale !in OverlayTransform.MIN_SCALE..OverlayTransform.MAX_SCALE || transform.opacity !in 0.0..1.0) {
            issues += ValidationIssue(ValidationCode.OUT_OF_RANGE, "$path.transform", "Scale or opacity out of range")
        }
    }

    /** 워터마크 글자와 숫자 범위를 검사한다. `null`이면 검사하지 않는다. */
    fun validateWatermark(watermark: Watermark?, path: String, issues: MutableList<ValidationIssue>) {
        if (watermark == null) return
        if (watermark.text.isBlank() || watermark.text.trim().length > Watermark.MAX_TEXT_LENGTH || '\n' in watermark.text) {
            issues += ValidationIssue(ValidationCode.OUT_OF_RANGE, "$path.text", "Expected 1..${Watermark.MAX_TEXT_LENGTH} characters on one line")
        }
        listOf(
            Triple("sizeRatio", watermark.sizeRatio, Watermark.MIN_SIZE..Watermark.MAX_SIZE),
            Triple("opacity", watermark.opacity, Watermark.MIN_OPACITY..1.0),
            Triple("rotationDegrees", watermark.rotationDegrees, -90.0..90.0),
            Triple("density", watermark.density, Watermark.MIN_DENSITY..Watermark.MAX_DENSITY),
        ).forEach { (name, value, range) ->
            when {
                !value.isFinite() -> issues += ValidationIssue(ValidationCode.NOT_FINITE, "$path.$name", "Must be finite")
                value !in range -> issues += ValidationIssue(ValidationCode.OUT_OF_RANGE, "$path.$name", "Expected $range")
            }
        }
    }

    private fun positive(value: Double) = value.isFinite() && value > 0.0
}
