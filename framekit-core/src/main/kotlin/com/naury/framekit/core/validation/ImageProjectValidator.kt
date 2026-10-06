package com.naury.framekit.core.validation

import com.naury.framekit.core.geometry.CropBoundsCalculator
import com.naury.framekit.core.geometry.GeometryEdit
import com.naury.framekit.core.geometry.GeometryFrame
import com.naury.framekit.core.model.ImageProject
import com.naury.framekit.core.model.MediaType
import com.naury.framekit.core.model.SourceMetadata

/** Checks that an [ImageProject] can be rendered for the given source. */
public object ImageProjectValidator {

    public fun validate(project: ImageProject, metadata: SourceMetadata): ValidationResult {
        val issues = mutableListOf<ValidationIssue>()
        validateMetadata(metadata, issues)
        if (project.source != metadata.id) {
            issues += ValidationIssue(ValidationCode.SOURCE_MISMATCH, "source", "Project source does not match metadata")
        }
        if (metadata.mediaType != MediaType.IMAGE) {
            issues += ValidationIssue(ValidationCode.SOURCE_MISMATCH, "metadata.mediaType", "Source is not an image")
        }
        validateGeometry(project.geometry, issues)
        if (issues.isEmpty()) validateCropArea(project.geometry, metadata, issues)
        return ValidationResult.of(issues)
    }

    private fun validateCropArea(geometry: GeometryEdit, metadata: SourceMetadata, issues: MutableList<ValidationIssue>) {
        val frame = GeometryFrame(metadata.uprightSize, geometry)
        if (!frame.containsRect(geometry.crop)) {
            issues += ValidationIssue(ValidationCode.INVALID_CROP, "geometry.crop", "Crop must stay inside the rotated image")
        } else if (!CropBoundsCalculator.meetsMinimumSize(frame, geometry.crop)) {
            issues += ValidationIssue(
                ValidationCode.INVALID_CROP,
                "geometry.crop",
                "Crop must be at least ${CropBoundsCalculator.MIN_CROP_SIDE_PX} px on each side",
            )
        }
    }

    internal fun validateMetadata(metadata: SourceMetadata, issues: MutableList<ValidationIssue>) {
        if (!metadata.uprightSize.isValid) {
            issues += ValidationIssue(
                ValidationCode.INVALID_SIZE,
                "metadata.uprightSize",
                "Source size must be positive: ${metadata.uprightSize}",
            )
        }
    }

    internal fun validateGeometry(geometry: GeometryEdit, issues: MutableList<ValidationIssue>) {
        if (geometry.quarterTurns !in 0..3) {
            issues += ValidationIssue(ValidationCode.OUT_OF_RANGE, "geometry.quarterTurns", "Expected 0..3")
        }
        val straighten = geometry.straightenDegrees
        if (!straighten.isFinite()) {
            issues += ValidationIssue(ValidationCode.NOT_FINITE, "geometry.straightenDegrees", "Must be finite")
        } else if (straighten !in GeometryEdit.MIN_STRAIGHTEN_DEGREES..GeometryEdit.MAX_STRAIGHTEN_DEGREES) {
            issues += ValidationIssue(ValidationCode.OUT_OF_RANGE, "geometry.straightenDegrees", "Expected -45..45")
        }
        val crop = geometry.crop
        if (!crop.isFinite) {
            issues += ValidationIssue(ValidationCode.NOT_FINITE, "geometry.crop", "Must be finite")
        } else if (!crop.isValidUnitRect) {
            issues += ValidationIssue(ValidationCode.INVALID_RECT, "geometry.crop", "Expected 0 <= left < right <= 1 and 0 <= top < bottom <= 1")
        }
    }
}
