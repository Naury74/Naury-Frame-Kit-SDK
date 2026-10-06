package com.naury.framekit.image.render

import com.naury.framekit.core.geometry.Affine2D
import com.naury.framekit.core.model.PixelSize

/**
 * Everything a renderer needs to draw one project snapshot at one size.
 *
 * Preview and export build this plan with the same factory. Neither path adds its own corrections,
 * so a difference between them points to a renderer bug rather than a planning difference.
 *
 * @property sourceToOutput transform from full-resolution upright source pixels to output pixels.
 */
public data class ImageRenderPlan(
    val projectRevision: Long,
    val outputSize: PixelSize,
    val sourceToOutput: Affine2D,
)
