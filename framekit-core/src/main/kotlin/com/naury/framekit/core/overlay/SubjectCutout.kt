package com.naury.framekit.core.overlay

/**
 * Background removal: the background of the source becomes transparent.
 *
 * The subject mask is computed once and stored as a project asset; the project keeps only its id, so
 * preview, export and restored sessions all use the same mask and turning the cutout off restores
 * the original.
 *
 * @property maskAssetId id of an alpha mask PNG in the project's asset store, aligned with the
 *   upright source.
 */
public data class SubjectCutout(val maskAssetId: String) {
    public companion object {
        private val ID_PATTERN = Regex("[A-Za-z0-9_-]{1,64}\\.png")

        public fun isValidAssetId(id: String): Boolean = ID_PATTERN.matches(id)
    }
}
