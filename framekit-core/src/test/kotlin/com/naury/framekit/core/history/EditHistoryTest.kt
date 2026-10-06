package com.naury.framekit.core.history

import com.google.common.truth.Truth.assertThat
import com.naury.framekit.core.geometry.GeometryEdit
import com.naury.framekit.core.model.ImageProject
import com.naury.framekit.core.model.ProjectId
import com.naury.framekit.core.model.SourceId
import org.junit.Test

class EditHistoryTest {

    private val initial = ImageProject(ProjectId("p"), SourceId("s"))
    private val a = initial.withTurns(1)
    private val b = initial.withTurns(2)
    private val c = initial.withTurns(3)

    @Test
    fun `D01 undo restores previous state and enables redo`() {
        val history = EditHistory.start(initial).commit(a).commit(b).undo()

        assertThat(history.current.sameContentAs(a)).isTrue()
        assertThat(history.canRedo).isTrue()
        assertThat(history.redo().current.sameContentAs(b)).isTrue()
    }

    @Test
    fun `D02 commit after undo clears redo stack`() {
        val history = EditHistory.start(initial).commit(a).commit(b).undo().commit(c)

        assertThat(history.canRedo).isFalse()
        assertThat(history.current.sameContentAs(c)).isTrue()
        assertThat(history.undo().current.sameContentAs(a)).isTrue()
    }

    @Test
    fun `D05 same content commit does not change revision or history`() {
        val history = EditHistory.start(initial).commit(a)

        val same = history.commit(a.withRevision(999))

        assertThat(same).isSameInstanceAs(history)
        assertThat(same.current.revision).isEqualTo(history.current.revision)
    }

    @Test
    fun `D06 history keeps only the most recent capacity steps`() {
        var history = EditHistory.start(initial)
        repeat(60) { index ->
            history = history.commit(initial.copy(geometry = GeometryEdit(straightenDegrees = index * 0.5 + 0.5)))
        }

        assertThat(history.past).hasSize(EditHistory.DEFAULT_CAPACITY)
        assertThat(history.past.first().geometry.straightenDegrees).isEqualTo(5.0)
    }

    @Test
    fun `D07 undo back to baseline makes the project clean`() {
        val edited = EditHistory.start(initial).commit(a)
        assertThat(edited.isDirty).isTrue()

        assertThat(edited.undo().isDirty).isFalse()
    }

    @Test
    fun `returning to baseline content through a new commit is clean`() {
        val history = EditHistory.start(initial).commit(a).commit(initial)

        assertThat(history.isDirty).isFalse()
        assertThat(history.canUndo).isTrue()
    }

    @Test
    fun `revision is never reused after undo and a different commit`() {
        val history = EditHistory.start(initial).commit(a).commit(b)
        val bRevision = history.current.revision

        val afterBranch = history.undo().commit(c)

        assertThat(afterBranch.current.revision).isGreaterThan(bRevision)
    }

    @Test
    fun `undo and redo on empty stacks are no-ops`() {
        val history = EditHistory.start(initial)

        assertThat(history.undo()).isSameInstanceAs(history)
        assertThat(history.redo()).isSameInstanceAs(history)
    }

    private fun ImageProject.withTurns(turns: Int) = copy(geometry = GeometryEdit(quarterTurns = turns))
}
