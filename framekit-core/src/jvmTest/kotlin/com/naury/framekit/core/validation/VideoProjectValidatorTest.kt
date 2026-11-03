package com.naury.framekit.core.validation

import com.google.common.truth.Truth.assertThat
import com.naury.framekit.core.geometry.PointN
import com.naury.framekit.core.geometry.RectN
import com.naury.framekit.core.model.MediaType
import com.naury.framekit.core.model.PixelSize
import com.naury.framekit.core.model.ProjectId
import com.naury.framekit.core.model.SourceId
import com.naury.framekit.core.model.SourceMetadata
import com.naury.framekit.core.overlay.MaskShape
import com.naury.framekit.core.overlay.PrivacyEffect
import com.naury.framekit.core.overlay.PrivacyMask
import com.naury.framekit.core.video.TimeRangeUs
import com.naury.framekit.core.video.TimedPrivacyMask
import com.naury.framekit.core.video.Timeline
import com.naury.framekit.core.video.VideoClip
import com.naury.framekit.core.video.VideoProject
import org.junit.Test

class VideoProjectValidatorTest {

    private val source = SourceId("v")
    private val sources = mapOf(source to SourceMetadata(source, MediaType.VIDEO, "video/mp4", PixelSize(1920, 1080), 10_000_000, hasAudio = true))
    private val clip = VideoClip("c", source, TimeRangeUs(0, 6_000_000))

    private fun codes(timeline: Timeline) =
        when (val result = VideoProjectValidator.validate(VideoProject(ProjectId("p"), timeline), sources, 1_000_000)) {
            ValidationResult.Valid -> emptyList()
            is ValidationResult.Invalid -> result.issues.map { it.path }
        }

    private fun mask(id: String, shape: MaskShape = MaskShape.Rectangle(RectN(0.1, 0.1, 0.4, 0.4))) =
        TimedPrivacyMask(PrivacyMask(id, shape, PrivacyEffect.Mosaic()), TimeRangeUs(1_000_000, 3_000_000))

    @Test
    fun `timed mosaic inside the timeline passes`() {
        assertThat(codes(Timeline(listOf(clip), privacyMasks = listOf(mask("m"))))).isEmpty()
    }

    @Test
    fun `mask outside the timeline is rejected`() {
        val late = mask("m").copy(range = TimeRangeUs(5_000_000, 7_000_000))

        assertThat(codes(Timeline(listOf(clip), privacyMasks = listOf(late)))).contains("timeline.timed[0].range")
    }

    @Test
    fun `brush masks and too many masks are rejected for video`() {
        val brush = mask("b", MaskShape.Brush(listOf(PointN(0.1, 0.1), PointN(0.2, 0.2)), 0.05))
        val many = (0..Timeline.MAX_PRIVACY_MASKS).map { mask("m$it") }

        assertThat(codes(Timeline(listOf(clip), privacyMasks = listOf(brush)))).contains("timeline.privacyMasks[0].shape")
        assertThat(codes(Timeline(listOf(clip), privacyMasks = many))).contains("timeline.privacyMasks")
    }

    @Test
    fun `clip shorter than the minimum after speed is rejected`() {
        val fast = clip.copy(sourceRange = TimeRangeUs(0, 3_000_000), speed = 4.0)

        assertThat(codes(Timeline(listOf(fast)))).isNotEmpty()
    }
}
