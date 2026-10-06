package com.naury.framekit.core.validation

import com.google.common.truth.Truth.assertThat
import com.naury.framekit.core.geometry.PointN
import com.naury.framekit.core.model.ImageProject
import com.naury.framekit.core.model.MediaType
import com.naury.framekit.core.model.PixelSize
import com.naury.framekit.core.model.ProjectId
import com.naury.framekit.core.model.SourceId
import com.naury.framekit.core.model.SourceMetadata
import com.naury.framekit.core.overlay.BrushKind
import com.naury.framekit.core.overlay.DrawingStroke
import com.naury.framekit.core.overlay.EmojiCatalog
import com.naury.framekit.core.overlay.ImageOverlay
import com.naury.framekit.core.overlay.OverlayTransform
import com.naury.framekit.core.overlay.StrokePoint
import com.naury.framekit.core.overlay.TextStyleSpec
import org.junit.Test

class OverlayValidatorTest {

    private val source = SourceId("s")
    private val metadata = SourceMetadata(source, MediaType.IMAGE, "image/jpeg", PixelSize(1000, 800))
    private val project = ImageProject(ProjectId("p"), source)

    private fun codes(project: ImageProject) = when (val result = ImageProjectValidator.validate(project, metadata)) {
        ValidationResult.Valid -> emptyList()
        is ValidationResult.Invalid -> result.issues.map { it.code }
    }

    @Test
    fun `text sticker and stroke with valid values pass`() {
        val valid = project.copy(
            overlays = listOf(
                ImageOverlay.Text("t", "안녕 👋"),
                ImageOverlay.Sticker("s", EmojiCatalog.assetId("😀")),
            ),
            drawing = listOf(DrawingStroke("d", listOf(StrokePoint(0.1, 0.1), StrokePoint(0.2, 0.3)), 0.01, -1, 1.0, BrushKind.PEN)),
        )

        assertThat(codes(valid)).isEmpty()
    }

    @Test
    fun `V02 duplicate overlay ids are rejected`() {
        val duplicate = project.copy(overlays = listOf(ImageOverlay.Text("a", "x"), ImageOverlay.Sticker("a", EmojiCatalog.assetId("⭐"))))

        assertThat(codes(duplicate)).containsExactly(ValidationCode.DUPLICATE_ID)
    }

    @Test
    fun `V02 unregistered font and sticker asset are rejected`() {
        val unknown = project.copy(
            overlays = listOf(
                ImageOverlay.Text("t", "x", TextStyleSpec(fontId = "comic")),
                ImageOverlay.Sticker("s", "host:missing"),
            ),
        )

        assertThat(codes(unknown)).containsExactly(ValidationCode.UNKNOWN_REFERENCE, ValidationCode.UNKNOWN_REFERENCE)
    }

    @Test
    fun `non finite transform and empty strokes are rejected`() {
        val broken = project.copy(
            overlays = listOf(ImageOverlay.Text("t", "x", transform = OverlayTransform(center = PointN(Double.NaN, 0.5)))),
            drawing = listOf(DrawingStroke("d", emptyList(), 0.01, -1, 1.0, BrushKind.PEN)),
        )

        assertThat(codes(broken)).containsExactly(ValidationCode.NOT_FINITE, ValidationCode.OUT_OF_RANGE)
    }

    @Test
    fun `emoji asset ids round trip`() {
        assertThat(EmojiCatalog.emojiOf(EmojiCatalog.assetId("❤️"))).isEqualTo("❤️")
        assertThat(EmojiCatalog.emojiOf("emoji:")).isNull()
        assertThat(EmojiCatalog.groups.flatMap { it.emoji }).containsNoDuplicates()
    }
}
