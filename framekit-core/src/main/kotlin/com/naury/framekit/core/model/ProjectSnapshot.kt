package com.naury.framekit.core.model

/**
 * Immutable project state that can be stored in an edit history.
 *
 * [revision] identifies one committed snapshot for cache invalidation and export tracking. It is not
 * part of the content: two snapshots with different revisions but the same edits are equal in
 * [sameContentAs].
 */
public interface ProjectSnapshot<T : ProjectSnapshot<T>> {
    public val revision: Long

    /** Returns a copy of this snapshot stamped with [revision]. */
    public fun withRevision(revision: Long): T

    /** Compares every edit field except [revision]. */
    public fun sameContentAs(other: T): Boolean
}
