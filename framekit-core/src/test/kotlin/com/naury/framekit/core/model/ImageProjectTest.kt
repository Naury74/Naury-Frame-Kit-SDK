package com.naury.framekit.core.model

import com.google.common.truth.Truth.assertThat
import com.naury.framekit.core.geometry.GeometryEdit
import org.junit.Test

class ImageProjectTest {

    private val project = ImageProject(ProjectId("p"), SourceId("s"))

    @Test
    fun `revision is excluded from content comparison`() {
        assertThat(project.sameContentAs(project.withRevision(7))).isTrue()
    }

    @Test
    fun `geometry change is a content change`() {
        assertThat(project.sameContentAs(project.copy(geometry = GeometryEdit(flipX = true)))).isFalse()
    }

    @Test(expected = IllegalArgumentException::class)
    fun `blank source id is rejected`() {
        SourceId(" ")
    }
}
