package com.naury.framekit.core.overlay

import com.google.common.truth.Truth.assertThat
import com.naury.framekit.core.geometry.GeometryEdit
import com.naury.framekit.core.model.ImageProject
import com.naury.framekit.core.model.PixelSize
import com.naury.framekit.core.model.ProjectId
import com.naury.framekit.core.model.SourceId
import com.naury.framekit.core.model.SourceMetadata
import com.naury.framekit.core.model.MediaType
import com.naury.framekit.core.validation.ImageProjectValidator
import org.junit.Test

class WatermarkTest {

    @Test
    fun `normalized keeps one trimmed line and clamps numbers`() {
        val watermark = Watermark("  a\nb  ", sizeRatio = 9.0, opacity = 0.0, rotationDegrees = 200.0, density = 0.1).normalized()

        assertThat(watermark.text).isEqualTo("a b")
        assertThat(watermark.sizeRatio).isEqualTo(Watermark.MAX_SIZE)
        assertThat(watermark.opacity).isEqualTo(Watermark.MIN_OPACITY)
        assertThat(watermark.rotationDegrees).isEqualTo(90.0)
        assertThat(watermark.density).isEqualTo(Watermark.MIN_DENSITY)
        assertThat(watermark.isValid).isTrue()
    }

    @Test
    fun `blank or too long text is not valid`() {
        assertThat(Watermark(" ").isValid).isFalse()
        assertThat(Watermark("x".repeat(Watermark.MAX_TEXT_LENGTH + 1)).isValid).isFalse()
        assertThat(Watermark("ok").isValid).isTrue()
    }

    @Test
    fun `project validation reports a bad watermark and content comparison sees it`() {
        val project = ImageProject(ProjectId("p"), SourceId("s"), GeometryEdit())
        val metadata = SourceMetadata(SourceId("s"), MediaType.IMAGE, "image/png", PixelSize(100, 100))
        val marked = project.copy(watermark = Watermark("ok", opacity = 2.0))

        assertThat(ImageProjectValidator.validate(marked, metadata).isValid).isFalse()
        assertThat(ImageProjectValidator.validate(project.copy(watermark = Watermark("ok")), metadata).isValid).isTrue()
        assertThat(project.sameContentAs(project.copy(watermark = Watermark("ok")))).isFalse()
    }
}
