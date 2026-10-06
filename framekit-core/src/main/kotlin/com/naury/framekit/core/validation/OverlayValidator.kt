package com.naury.framekit.core.validation

import com.naury.framekit.core.overlay.DrawingStroke
import com.naury.framekit.core.overlay.StickerCatalog
import com.naury.framekit.core.overlay.FontCatalog
import com.naury.framekit.core.overlay.ImageOverlay
import com.naury.framekit.core.overlay.MaskShape
import com.naury.framekit.core.overlay.OverlayTransform
import com.naury.framekit.core.overlay.PrivacyEffect
import com.naury.framekit.core.overlay.PrivacyMask

/** 텍스트, 스티커, 그리기 획의 검증. */
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

    private fun positive(value: Double) = value.isFinite() && value > 0.0
}
