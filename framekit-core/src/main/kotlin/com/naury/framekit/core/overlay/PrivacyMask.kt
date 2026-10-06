package com.naury.framekit.core.overlay

import com.naury.framekit.core.geometry.PointN
import com.naury.framekit.core.geometry.RectN

/** Area covered by a privacy mask, in output canvas (C) coordinates. */
public sealed interface MaskShape {
    /** Free-hand brush; [widthShortEdgeRatio] is the brush diameter relative to the canvas short edge. */
    public data class Brush(val points: List<PointN>, val widthShortEdgeRatio: Double) : MaskShape

    public data class Rectangle(val rect: RectN) : MaskShape

    /** Ellipse inscribed in [rect]. */
    public data class Ellipse(val rect: RectN) : MaskShape
}

/** How a privacy mask hides what is under it. */
public sealed interface PrivacyEffect {
    /**
     * Gaussian blur. Blur can sometimes be reversed; use [Mosaic] or an opaque shape for sensitive
     * information.
     *
     * @property radiusShortEdgeRatio Gaussian sigma relative to the canvas short edge.
     */
    public data class Blur(val radiusShortEdgeRatio: Double = 0.015) : PrivacyEffect

    /**
     * Pixelation on a grid anchored at the canvas origin.
     *
     * @property blockShortEdgeRatio block side relative to the canvas short edge.
     */
    public data class Mosaic(val blockShortEdgeRatio: Double = 0.03) : PrivacyEffect

    public companion object {
        public const val MAX_RATIO: Double = 0.2
    }
}

/**
 * Blur or mosaic over part of the image. It is applied to the color-adjusted photo only; text,
 * stickers and drawings stay sharp on top. Overlapping masks all read the unmasked photo, so edges do
 * not get blurred twice.
 */
public data class PrivacyMask(val id: String, val shape: MaskShape, val effect: PrivacyEffect)
