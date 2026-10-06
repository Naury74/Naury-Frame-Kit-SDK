package com.naury.framekit.core.model

/**
 * Key of a media source registered in an editing session.
 *
 * The core model never holds platform objects such as `Uri` or `Bitmap`. The Android layer keeps
 * the mapping from this key to readable bytes and their access grants.
 */
@JvmInline
public value class SourceId(public val value: String) {
    init {
        require(value.isNotBlank()) { "SourceId must not be blank" }
    }
}

/** Stable identifier of one editing project. */
@JvmInline
public value class ProjectId(public val value: String) {
    init {
        require(value.isNotBlank()) { "ProjectId must not be blank" }
    }
}
