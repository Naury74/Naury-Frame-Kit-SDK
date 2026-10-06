package com.naury.framekit.core.validation

import com.google.common.truth.Truth.assertThat
import com.naury.framekit.core.effect.Adjustments
import com.naury.framekit.core.effect.FilterSelection
import com.naury.framekit.core.geometry.GeometryEdit
import com.naury.framekit.core.geometry.RectN
import com.naury.framekit.core.model.ImageProject
import com.naury.framekit.core.model.MediaType
import com.naury.framekit.core.model.PixelSize
import com.naury.framekit.core.model.ProjectId
import com.naury.framekit.core.model.SourceId
import com.naury.framekit.core.model.SourceMetadata
import org.junit.Test

class ImageProjectValidatorTest {

    private val source = SourceId("source")
    private val metadata = SourceMetadata(source, MediaType.IMAGE, "image/jpeg", PixelSize(4000, 3000))
    private val project = ImageProject(ProjectId("project"), source)

    @Test
    fun `default project is valid`() {
        assertThat(ImageProjectValidator.validate(project, metadata)).isEqualTo(ValidationResult.Valid)
    }

    @Test
    fun `V01 NaN straighten is rejected`() {
        val result = validate(GeometryEdit(straightenDegrees = Double.NaN))

        assertThat(result.codes()).containsExactly(ValidationCode.NOT_FINITE)
    }

    @Test
    fun `V01 infinite crop edge is rejected`() {
        val result = validate(GeometryEdit(crop = RectN(0.0, 0.0, Double.POSITIVE_INFINITY, 1.0)))

        assertThat(result.codes()).containsExactly(ValidationCode.NOT_FINITE)
    }

    @Test
    fun `V01 negative source size is rejected`() {
        val result = ImageProjectValidator.validate(project, metadata.copy(uprightSize = PixelSize(-1, 300)))

        assertThat(result.codes()).containsExactly(ValidationCode.INVALID_SIZE)
    }

    @Test
    fun `inverted or out of unit crop is rejected`() {
        assertThat(validate(GeometryEdit(crop = RectN(0.6, 0.0, 0.4, 1.0))).codes())
            .containsExactly(ValidationCode.INVALID_RECT)
        assertThat(validate(GeometryEdit(crop = RectN(-0.1, 0.0, 1.0, 1.0))).codes())
            .containsExactly(ValidationCode.INVALID_RECT)
    }

    @Test
    fun `out of range rotation values are rejected`() {
        assertThat(validate(GeometryEdit(quarterTurns = 4)).codes()).containsExactly(ValidationCode.OUT_OF_RANGE)
        assertThat(validate(GeometryEdit(straightenDegrees = 45.5)).codes()).containsExactly(ValidationCode.OUT_OF_RANGE)
    }

    @Test
    fun `project for another source or a video is rejected`() {
        val other = metadata.copy(id = SourceId("other"))
        val video = metadata.copy(mediaType = MediaType.VIDEO)

        assertThat(ImageProjectValidator.validate(project, other).codes()).containsExactly(ValidationCode.SOURCE_MISMATCH)
        assertThat(ImageProjectValidator.validate(project, video).codes()).containsExactly(ValidationCode.SOURCE_MISMATCH)
    }

    @Test
    fun `crop that includes straighten corners is rejected`() {
        val result = validate(GeometryEdit(straightenDegrees = 10.0))

        assertThat(result.codes()).containsExactly(ValidationCode.INVALID_CROP)
    }

    @Test
    fun `crop smaller than 16 px is rejected`() {
        val result = validate(GeometryEdit(crop = RectN(0.5, 0.5, 0.502, 0.502)))

        assertThat(result.codes()).containsExactly(ValidationCode.INVALID_CROP)
    }

    @Test
    fun `adjustments outside their range and unknown presets are rejected`() {
        val result = ImageProjectValidator.validate(
            project.copy(adjustments = Adjustments(exposure = 3.0, grain = Double.NaN), filter = FilterSelection("nope", 1.5)),
            metadata,
        )

        assertThat(result.codes()).containsExactly(
            ValidationCode.OUT_OF_RANGE,
            ValidationCode.NOT_FINITE,
            ValidationCode.UNKNOWN_REFERENCE,
            ValidationCode.OUT_OF_RANGE,
        )
    }

    private fun validate(geometry: GeometryEdit): ValidationResult =
        ImageProjectValidator.validate(project.copy(geometry = geometry), metadata)

    private fun ValidationResult.codes(): List<ValidationCode> = when (this) {
        ValidationResult.Valid -> emptyList()
        is ValidationResult.Invalid -> issues.map { it.code }
    }
}
