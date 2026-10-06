package com.naury.framekit.image.render

import android.graphics.Bitmap
import com.naury.framekit.core.geometry.OutputSizeCalculator
import com.naury.framekit.core.geometry.Size2D
import com.naury.framekit.core.model.ImageProject
import com.naury.framekit.core.model.SourceMetadata
import com.naury.framekit.image.decode.DecodedImage
import com.naury.framekit.image.effect.ColorEffectRenderer

/** What the preview shows. */
public enum class PreviewMode {
    /** The edited result, as it will be exported. */
    RESULT,

    /** The whole rotated image without crop, for the geometry tools. Vignette and grain are skipped. */
    UNCROPPED,
}

/**
 * Renders a screen-sized preview with the same plan factory, geometry renderer and color renderer as
 * export, so what the user sees is what gets saved.
 */
public class ImagePreviewRenderer(private val colorRenderer: ColorEffectRenderer) {

    /**
     * @param maxWidth largest preview width in pixels, usually the viewport width.
     * @param maxHeight largest preview height in pixels.
     * @return a new bitmap owned by the caller.
     */
    public fun render(
        source: DecodedImage,
        project: ImageProject,
        metadata: SourceMetadata,
        mode: PreviewMode,
        maxWidth: Int,
        maxHeight: Int,
    ): Bitmap {
        val plan = when (mode) {
            PreviewMode.RESULT -> {
                val full = ImageRenderPlanFactory.outputSize(project, metadata, Long.MAX_VALUE)
                val size = OutputSizeCalculator.compute(
                    Size2D(full.width.toDouble(), full.height.toDouble()),
                    Long.MAX_VALUE,
                    maxWidth.coerceAtLeast(1),
                    maxHeight.coerceAtLeast(1),
                )
                ImageRenderPlanFactory.create(project, metadata, size)
            }
            PreviewMode.UNCROPPED -> ImageRenderPlanFactory.uncropped(project, metadata, maxOf(maxWidth, maxHeight))
        }
        val bitmap = CanvasGeometryRenderer.render(source, plan, backgroundArgb = null)
        colorRenderer.apply(bitmap, project.colorSpec, includeCanvasEffects = mode == PreviewMode.RESULT)
        return bitmap
    }
}
