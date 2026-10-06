package com.naury.framekit.core.model

import com.naury.framekit.core.effect.Adjustments
import com.naury.framekit.core.effect.ColorEffectSpec
import com.naury.framekit.core.effect.FilterSelection
import com.naury.framekit.core.geometry.GeometryEdit
import com.naury.framekit.core.overlay.DrawingStroke
import com.naury.framekit.core.overlay.ImageOverlay
import com.naury.framekit.core.overlay.PrivacyMask
import com.naury.framekit.core.overlay.SubjectCutout

/**
 * Final edit state of one image.
 *
 * The project stores the result of the edits, not the list of commands that produced it. Renderers
 * build their plan from this snapshot alone, so preview and export cannot drift apart by replaying
 * history differently.
 *
 * @property source key of the original image. The original is never overwritten.
 * @property geometry rotation, flip, straighten and crop applied to the upright source.
 * @property cutout background removal applied to the source before everything else, or `null`.
 * @property adjustments color and tone adjustments, applied after geometry.
 * @property filter preset and intensity, applied after the adjustments.
 * @property privacyMasks blur and mosaic areas, applied after color and before the drawing layer.
 * @property overlays text and stickers in z-order, last on top, positioned in the output canvas.
 * @property drawing strokes of the drawing layer, drawn below [overlays]. The eraser only affects
 *   this layer.
 * @property grainSeed fixes the grain pattern so it does not change between redraws or between
 *   preview and export.
 */
public data class ImageProject(
    val id: ProjectId,
    val source: SourceId,
    val geometry: GeometryEdit = GeometryEdit(),
    val cutout: SubjectCutout? = null,
    val adjustments: Adjustments = Adjustments(),
    val filter: FilterSelection = FilterSelection(),
    val privacyMasks: List<PrivacyMask> = emptyList(),
    val overlays: List<ImageOverlay> = emptyList(),
    val drawing: List<DrawingStroke> = emptyList(),
    val grainSeed: Long = 0L,
    override val revision: Long = 0L,
) : ProjectSnapshot<ImageProject> {

    /** Resolved color pipeline for this snapshot. */
    public val colorSpec: ColorEffectSpec get() = ColorEffectSpec.of(adjustments, filter, grainSeed)

    override fun withRevision(revision: Long): ImageProject = copy(revision = revision)

    override fun sameContentAs(other: ImageProject): Boolean =
        id == other.id && source == other.source && geometry == other.geometry && cutout == other.cutout &&
            adjustments == other.adjustments && filter == other.filter && privacyMasks == other.privacyMasks && overlays == other.overlays &&
            drawing == other.drawing && grainSeed == other.grainSeed
}
