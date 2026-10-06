package com.naury.framekit.ui.layout

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class EditorLayoutPolicyTest {

    @Test
    fun `portrait phone stacks controls at full width`() {
        assertThat(EditorLayoutPolicy.decide(411f, 891f, FoldPosture.Flat)).isEqualTo(EditorLayout.Stacked(null))
    }

    @Test
    fun `landscape phone uses a side panel`() {
        assertThat(EditorLayoutPolicy.decide(891f, 411f, FoldPosture.Flat)).isInstanceOf(EditorLayout.SidePanel::class.java)
    }

    @Test
    fun `unfolded foldable in portrait keeps controls centered and limited`() {
        // 펼친 Fold 내부 화면은 600dp 이상이지만 세로가 더 길다.
        assertThat(EditorLayoutPolicy.decide(749f, 832f, FoldPosture.Flat)).isEqualTo(EditorLayout.Stacked(640))
    }

    @Test
    fun `tablet and expanded windows use a side panel`() {
        assertThat(EditorLayoutPolicy.decide(1280f, 800f, FoldPosture.Flat)).isEqualTo(EditorLayout.SidePanel(360))
        assertThat(EditorLayoutPolicy.decide(900f, 1200f, FoldPosture.Flat)).isEqualTo(EditorLayout.SidePanel(360))
    }

    @Test
    fun `half opened postures split at the hinge`() {
        assertThat(EditorLayoutPolicy.decide(749f, 832f, FoldPosture.Tabletop(1080, 1100)))
            .isEqualTo(EditorLayout.SplitAtHorizontalHinge(1080, 1100))
        assertThat(EditorLayoutPolicy.decide(1500f, 900f, FoldPosture.Book(1180, 1200)))
            .isEqualTo(EditorLayout.SplitAtVerticalHinge(1180, 1200))
    }
}
