package com.naury.framekit.core.model

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
 */
public data class ImageProject(
    val id: ProjectId,
    val source: SourceId,
    val geometry: GeometryEdit = GeometryEdit(),
    override val revision: Long = 0L,
) : ProjectSnapshot<ImageProject> {

    override fun withRevision(revision: Long): ImageProject = copy(revision = revision)

    override fun sameContentAs(other: ImageProject): Boolean =
        id == other.id && source == other.source && geometry == other.geometry
}
