package com.naury.framekit.core.history

import com.naury.framekit.core.model.ProjectSnapshot

/**
 * History plus an optional in-progress change such as a slider drag, pinch or crop draft.
 *
 * A gesture is `begin → update* → commit` or `begin → update* → cancel`. Updates only change
 * [displayed]; the history receives at most one entry when the gesture is committed, and nothing when
 * it is cancelled.
 */
public data class HistoryTransaction<T : ProjectSnapshot<T>>(
    val history: EditHistory<T>,
    val draft: T? = null,
) {
    /** State that preview should render: the draft while a gesture is active, otherwise the current snapshot. */
    public val displayed: T get() = draft ?: history.current

    public val isActive: Boolean get() = draft != null

    /** Starts a gesture from the current snapshot. Restarting an active gesture keeps its draft. */
    public fun begin(): HistoryTransaction<T> = if (isActive) this else copy(draft = history.current)

    /** Replaces the draft. Starts a gesture implicitly when none is active. */
    public fun update(value: T): HistoryTransaction<T> = copy(draft = value)

    /** Commits the draft as one history entry and ends the gesture. */
    public fun commit(): HistoryTransaction<T> {
        val value = draft ?: return this
        return HistoryTransaction(history.commit(value))
    }

    /** Drops the draft and returns to the state before [begin]. */
    public fun cancel(): HistoryTransaction<T> = copy(draft = null)

    /** Undo is only applied between gestures so that a draft never mixes with history navigation. */
    public fun undo(): HistoryTransaction<T> = if (isActive) this else copy(history = history.undo())

    public fun redo(): HistoryTransaction<T> = if (isActive) this else copy(history = history.redo())

    public companion object {
        public fun <T : ProjectSnapshot<T>> start(
            initial: T,
            capacity: Int = EditHistory.DEFAULT_CAPACITY,
        ): HistoryTransaction<T> = HistoryTransaction(EditHistory.start(initial, capacity))
    }
}
