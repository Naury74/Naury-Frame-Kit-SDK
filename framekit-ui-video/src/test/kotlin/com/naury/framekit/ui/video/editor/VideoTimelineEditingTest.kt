package com.naury.framekit.ui.video.editor

import com.google.common.truth.Truth.assertThat
import com.naury.framekit.core.geometry.RectN
import com.naury.framekit.core.model.ProjectId
import com.naury.framekit.core.model.SourceId
import com.naury.framekit.core.overlay.ImageOverlay
import com.naury.framekit.core.overlay.MaskShape
import com.naury.framekit.core.overlay.PrivacyEffect
import com.naury.framekit.core.overlay.PrivacyMask
import com.naury.framekit.core.video.AudioClip
import com.naury.framekit.core.video.TimeRangeUs
import com.naury.framekit.core.video.TimedOverlay
import com.naury.framekit.core.video.TimedPrivacyMask
import com.naury.framekit.core.video.Timeline
import com.naury.framekit.core.video.VideoClip
import com.naury.framekit.core.video.VideoProject
import org.junit.Test

class VideoTimelineEditingTest {

    private val source = SourceId("s")
    private val project = VideoProject(
        ProjectId("p"),
        Timeline(
            videoClips = listOf(VideoClip("a", source, TimeRangeUs(0, 4_000_000)), VideoClip("b", source, TimeRangeUs(0, 2_000_000))),
            audioClips = listOf(AudioClip("m", SourceId("music"), TimeRangeUs(0, 1_000_000), timelineStartUs = 5_000_000)),
            overlays = listOf(TimedOverlay(ImageOverlay.Sticker("t", "emoji:😀"), TimeRangeUs(3_000_000, 6_000_000))),
            privacyMasks = listOf(TimedPrivacyMask(PrivacyMask("k", MaskShape.Rectangle(RectN(0.1, 0.1, 0.2, 0.2)), PrivacyEffect.Mosaic()), TimeRangeUs(4_500_000, 6_000_000))),
        ),
    )

    @Test
    fun `removing a clip trims timed items at the new end and drops what falls outside`() {
        val result = VideoTimelineEditing.remove(project, "b")

        assertThat(result.timeline.overlays.single().range).isEqualTo(TimeRangeUs(3_000_000, 4_000_000))
        assertThat(result.timeline.privacyMasks).isEmpty()
        assertThat(result.timeline.audioClips).isEmpty()
    }

    @Test
    fun `the last clip is never removed and moves stay inside the list`() {
        val single = project.copy(timeline = project.timeline.copy(videoClips = project.timeline.videoClips.take(1)))

        assertThat(VideoTimelineEditing.remove(single, "a")).isEqualTo(single)
        assertThat(VideoTimelineEditing.move(project, "a", -1)).isEqualTo(project)
        assertThat(VideoTimelineEditing.move(project, "a", 1).timeline.videoClips.map { it.id }).containsExactly("b", "a").inOrder()
    }

    @Test
    fun `edges keep at least the minimum length and stay inside the video`() {
        val range = TimeRangeUs(2_000_000, 5_000_000)

        assertThat(VideoTimelineEditing.moveEdge(range, 4_990_000, start = true, durationUs = 6_000_000, minUs = 33_333))
            .isEqualTo(TimeRangeUs(4_966_667, 5_000_000))
        assertThat(VideoTimelineEditing.moveEdge(range, 9_000_000, start = false, durationUs = 6_000_000, minUs = 33_333))
            .isEqualTo(TimeRangeUs(2_000_000, 6_000_000))
        assertThat(VideoTimelineEditing.defaultRange(5_500_000, 6_000_000, 3_000_000)).isEqualTo(TimeRangeUs(3_000_000, 6_000_000))
    }
}
