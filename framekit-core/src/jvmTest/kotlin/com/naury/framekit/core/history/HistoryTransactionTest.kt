package com.naury.framekit.core.history

import com.google.common.truth.Truth.assertThat
import com.naury.framekit.core.geometry.GeometryEdit
import com.naury.framekit.core.geometry.RectN
import com.naury.framekit.core.model.ImageProject
import com.naury.framekit.core.model.ProjectId
import com.naury.framekit.core.model.SourceId
import org.junit.Test

class HistoryTransactionTest {

    private val initial = ImageProject(ProjectId("p"), SourceId("s"))

    @Test
    fun `D03 hundred slider updates then release add one history step`() {
        var transaction = HistoryTransaction.start(initial).begin()
        for (step in 1..100) {
            transaction = transaction.update(initial.copy(geometry = GeometryEdit(straightenDegrees = step * 0.4)))
        }

        val committed = transaction.commit()

        assertThat(committed.history.past).hasSize(1)
        assertThat(committed.displayed.geometry.straightenDegrees).isEqualTo(40.0)
        assertThat(committed.isActive).isFalse()
    }

    @Test
    fun `D04 cancelled crop draft leaves project and history unchanged`() {
        val start = HistoryTransaction.start(initial)
        val draft = start.begin().update(initial.copy(geometry = GeometryEdit(crop = RectN(0.1, 0.1, 0.9, 0.9))))
        assertThat(draft.displayed.geometry.crop).isNotEqualTo(RectN.Full)

        val cancelled = draft.cancel()

        assertThat(cancelled).isEqualTo(start)
        assertThat(cancelled.history.canUndo).isFalse()
    }

    @Test
    fun `update without changes then commit adds nothing`() {
        val committed = HistoryTransaction.start(initial).begin().update(initial).commit()

        assertThat(committed.history.canUndo).isFalse()
    }

    @Test
    fun `undo is ignored while a gesture is active`() {
        val edited = HistoryTransaction.start(initial)
            .update(initial.copy(geometry = GeometryEdit(flipX = true)))
            .commit()
        val active = edited.begin().update(initial.copy(geometry = GeometryEdit(flipY = true)))

        assertThat(active.undo()).isEqualTo(active)
    }
}
