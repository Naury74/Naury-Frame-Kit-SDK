package com.naury.framekit.core.geometry

import kotlin.math.min

/**
 * Maps normalized content coordinates (C, or G while cropping) to viewport pixels (V) and back.
 *
 * The content is fitted inside the viewport minus [padding] and centered. [zoom] scales around the
 * viewport center and [panX]/[panY] move the result in viewport pixels. Nothing here depends on screen
 * density, and no viewport value is ever stored in a project.
 *
 * @param contentSize content size in any pixel unit; only its aspect ratio matters.
 * @throws IllegalArgumentException when a size is not positive or [zoom] is not positive.
 */
public class ViewportTransform(
    contentSize: Size2D,
    public val viewportWidth: Double,
    public val viewportHeight: Double,
    padding: Double = 0.0,
    zoom: Double = 1.0,
    panX: Double = 0.0,
    panY: Double = 0.0,
) {
    init {
        require(contentSize.width > 0.0 && contentSize.height > 0.0) { "Content size must be positive" }
        require(viewportWidth > 0.0 && viewportHeight > 0.0) { "Viewport size must be positive" }
        require(zoom > 0.0) { "Zoom must be positive" }
    }

    /** Fitted content size in viewport pixels at zoom 1. */
    public val fittedSize: Size2D

    /** Normalized content → viewport pixels. */
    public val contentToViewport: Affine2D

    /** Viewport pixels → normalized content. */
    public val viewportToContent: Affine2D

    init {
        val availableWidth = (viewportWidth - 2 * padding).coerceAtLeast(1.0)
        val availableHeight = (viewportHeight - 2 * padding).coerceAtLeast(1.0)
        val scale = min(availableWidth / contentSize.width, availableHeight / contentSize.height)
        fittedSize = Size2D(contentSize.width * scale, contentSize.height * scale)
        val fit = Affine2D.translate((viewportWidth - fittedSize.width) / 2.0, (viewportHeight - fittedSize.height) / 2.0) *
            Affine2D.scale(fittedSize.width, fittedSize.height)
        val zoomAroundCenter = Affine2D.translate(viewportWidth / 2.0 + panX, viewportHeight / 2.0 + panY) *
            Affine2D.scale(zoom) *
            Affine2D.translate(-viewportWidth / 2.0, -viewportHeight / 2.0)
        contentToViewport = zoomAroundCenter * fit
        viewportToContent = contentToViewport.inverted()
    }

    public fun toViewport(point: PointN): PointN = contentToViewport.map(point)

    /** Content point under a viewport pixel, or `null` when the pixel is in the letterbox area. */
    public fun toContent(x: Double, y: Double): PointN? {
        val point = viewportToContent.map(x, y)
        return point.takeIf { it.x in 0.0..1.0 && it.y in 0.0..1.0 }
    }

    /** Content point under a viewport pixel without rejecting the letterbox area. */
    public fun toContentUnbounded(x: Double, y: Double): PointN = viewportToContent.map(x, y)

    /** Viewport rectangle covered by a normalized content rectangle. */
    public fun toViewport(rect: RectN): RectN {
        val topLeft = toViewport(PointN(rect.left, rect.top))
        val bottomRight = toViewport(PointN(rect.right, rect.bottom))
        return RectN(topLeft.x, topLeft.y, bottomRight.x, bottomRight.y)
    }
}
