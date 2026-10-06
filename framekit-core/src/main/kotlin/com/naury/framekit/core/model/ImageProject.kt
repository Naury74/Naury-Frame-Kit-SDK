package com.naury.framekit.core.model

import com.naury.framekit.core.effect.Adjustments
import com.naury.framekit.core.effect.ColorEffectSpec
import com.naury.framekit.core.effect.FilterSelection
import com.naury.framekit.core.geometry.GeometryEdit

/**
 * Final edit state of one image.
 *
 * The project stores the result of the edits, not the list of commands that produced it. Renderers
 * build their plan from this snapshot alone, so preview and export cannot drift apart by replaying
 * history differently.
 *
 * @property source key of the original image. The original is never overwritten.
 * @property geometry rotation, flip, straighten and crop applied to the upright source.
 * @property adjustments color and tone adjustments, applied after geometry.
 * @property filter preset and intensity, applied after the adjustments.
 * @property grainSeed fixes the grain pattern so it does not change between redraws or between
 *   preview and export.
 */
public data class ImageProject(
    val id: ProjectId,
    val source: SourceId,
    val geometry: GeometryEdit = GeometryEdit(),
    val adjustments: Adjustments = Adjustments(),
    val filter: FilterSelection = FilterSelection(),
    val grainSeed: Long = 0L,
    override val revision: Long = 0L,
) : ProjectSnapshot<ImageProject> {

    /** Resolved color pipeline for this snapshot. */
    public val colorSpec: ColorEffectSpec get() = ColorEffectSpec.of(adjustments, filter, grainSeed)

    override fun withRevision(revision: Long): ImageProject = copy(revision = revision)

    override fun sameContentAs(other: ImageProject): Boolean =
        id == other.id && source == other.source && geometry == other.geometry &&
            adjustments == other.adjustments && filter == other.filter && grainSeed == other.grainSeed
}
