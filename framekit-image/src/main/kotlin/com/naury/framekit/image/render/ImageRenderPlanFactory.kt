package com.naury.framekit.image.render

import com.naury.framekit.android.result.EditorErrorCode
import com.naury.framekit.android.result.FrameKitException
import com.naury.framekit.core.geometry.GeometryFrame
import com.naury.framekit.core.geometry.OutputSizeCalculator
import com.naury.framekit.core.geometry.RectN
import com.naury.framekit.core.model.ImageProject
import com.naury.framekit.core.model.PixelSize
import com.naury.framekit.core.model.SourceMetadata
import com.naury.framekit.core.validation.ImageProjectValidator
import com.naury.framekit.core.validation.ValidationResult

/** Builds [ImageRenderPlan]s from project snapshots. */
public object ImageRenderPlanFactory {

    /**
     * Plan that renders the edited result at [outputSize].
     *
     * @throws FrameKitException with `INVALID_PROJECT` when the project fails validation.
     */
    public fun create(project: ImageProject, metadata: SourceMetadata, outputSize: PixelSize): ImageRenderPlan {
        requireValid(project, metadata)
        require(outputSize.isValid) { "Output size must be positive: $outputSize" }
        val frame = GeometryFrame(metadata.uprightSize, project.geometry)
        return ImageRenderPlan(project.revision, outputSize, frame.sourceToOutput(project.geometry.crop, outputSize))
    }

    /**
     * Output size of the edited result under the given limits. The crop is never upscaled.
     *
     * @throws FrameKitException with `INVALID_PROJECT` when the project fails validation.
     */
    public fun outputSize(
        project: ImageProject,
        metadata: SourceMetadata,
        maxPixels: Long,
        maxWidth: Int? = null,
        maxHeight: Int? = null,
    ): PixelSize {
        requireValid(project, metadata)
        val frame = GeometryFrame(metadata.uprightSize, project.geometry)
        return OutputSizeCalculator.compute(frame.cropPixelSize(project.geometry.crop), maxPixels, maxWidth, maxHeight)
    }

    /**
     * Plan that renders the whole rotated image (the G space) without cropping, used while the crop
     * tool is open. Areas outside the image stay transparent.
     */
    public fun uncropped(project: ImageProject, metadata: SourceMetadata, maxLongEdge: Int): ImageRenderPlan {
        val geometry = project.geometry.copy(crop = RectN.Full)
        val frame = GeometryFrame(metadata.uprightSize, geometry)
        val scale = minOf(1.0, maxLongEdge / maxOf(frame.bounds.width, frame.bounds.height))
        val size = PixelSize(
            maxOf(1, Math.round(frame.bounds.width * scale).toInt()),
            maxOf(1, Math.round(frame.bounds.height * scale).toInt()),
        )
        return ImageRenderPlan(project.revision, size, frame.sourceToOutput(RectN.Full, size))
    }

    private fun requireValid(project: ImageProject, metadata: SourceMetadata) {
        val result = ImageProjectValidator.validate(project, metadata)
        if (result is ValidationResult.Invalid) {
            throw FrameKitException(EditorErrorCode.INVALID_PROJECT, result.issues.joinToString { "${it.path}: ${it.code}" })
        }
    }
}
