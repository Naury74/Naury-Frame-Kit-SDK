package com.naury.framekit.core.history

import com.naury.framekit.core.model.ProjectSnapshot

/**
 * Immutable undo/redo history of project snapshots.
 *
 * Every operation returns a new instance. Snapshots are compared with
 * [ProjectSnapshot.sameContentAs], so committing unchanged content is a no-op and does not advance
 * the revision. New commits get a revision that was never used before in this history, which keeps
 * revision-keyed caches correct after undo followed by a different edit.
 *
 * Only project snapshots are stored. Bitmaps and render caches must never be put in a history.
 *
 * @property baseline state the editor opened with. [isDirty] compares against it rather than
 *   against the undo stack length, so undoing back to the baseline makes the project clean again.
 * @property capacity maximum number of undo steps kept. The oldest step is dropped first.
 */
public class EditHistory<T : ProjectSnapshot<T>> private constructor(
    public val current: T,
    public val baseline: T,
    public val past: List<T>,
    public val future: List<T>,
    public val capacity: Int,
    private val highestRevision: Long,
) {
    public val canUndo: Boolean get() = past.isNotEmpty()
    public val canRedo: Boolean get() = future.isNotEmpty()
    public val isDirty: Boolean get() = !current.sameContentAs(baseline)

    /**
     * Makes [next] the current state and clears the redo stack.
     *
     * Returns this instance unchanged when [next] has the same content as [current].
     */
    public fun commit(next: T): EditHistory<T> {
        if (next.sameContentAs(current)) return this
        val revision = highestRevision + 1
        val newPast = (past + current).takeLast(capacity)
        return EditHistory(next.withRevision(revision), baseline, newPast, emptyList(), capacity, revision)
    }

    /** Restores the previous snapshot. Returns this instance when there is nothing to undo. */
    public fun undo(): EditHistory<T> {
        if (!canUndo) return this
        return EditHistory(past.last(), baseline, past.dropLast(1), listOf(current) + future, capacity, highestRevision)
    }

    /** Re-applies the next snapshot. Returns this instance when there is nothing to redo. */
    public fun redo(): EditHistory<T> {
        if (!canRedo) return this
        return EditHistory(future.first(), baseline, past + current, future.drop(1), capacity, highestRevision)
    }

    public companion object {
        /** Default number of undo steps. */
        public const val DEFAULT_CAPACITY: Int = 50

        /**
         * Starts a history whose baseline and current state are [initial].
         *
         * @throws IllegalArgumentException when [capacity] is not positive.
         */
        public fun <T : ProjectSnapshot<T>> start(initial: T, capacity: Int = DEFAULT_CAPACITY): EditHistory<T> {
            require(capacity > 0) { "History capacity must be positive: $capacity" }
            return EditHistory(initial, initial, emptyList(), emptyList(), capacity, initial.revision)
        }

        /**
         * Rebuilds a history after process death: [current] is the last committed snapshot and
         * [baseline] is the untouched state the editor originally opened with. Undo and redo stacks
         * are not restored, but [isDirty] still compares against the original baseline.
         *
         * @throws IllegalArgumentException when [capacity] is not positive.
         */
        public fun <T : ProjectSnapshot<T>> restore(baseline: T, current: T, capacity: Int = DEFAULT_CAPACITY): EditHistory<T> {
            require(capacity > 0) { "History capacity must be positive: $capacity" }
            return EditHistory(current, baseline, emptyList(), emptyList(), capacity, maxOf(baseline.revision, current.revision))
        }
    }
}
