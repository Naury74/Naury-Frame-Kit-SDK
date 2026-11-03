package com.naury.framekit.core.video

import com.google.common.truth.Truth.assertThat
import com.naury.framekit.core.model.SourceId
import org.junit.Test

class AudioPlacementTest {

    private val music = AudioClip("a", SourceId("m"), TimeRangeUs(1_000_000, 4_000_000), timelineStartUs = 0)

    @Test
    fun `music longer than the video is cut at the video end`() {
        assertThat(AudioPlacement.segments(music, 2_000_000))
            .containsExactly(AudioSegment.Play(TimeRangeUs(1_000_000, 3_000_000)))
    }

    @Test
    fun `a late start becomes leading silence`() {
        assertThat(AudioPlacement.segments(music.copy(timelineStartUs = 500_000), 10_000_000))
            .containsExactly(AudioSegment.Silence(500_000), AudioSegment.Play(TimeRangeUs(1_000_000, 4_000_000)))
            .inOrder()
    }

    @Test
    fun `looped music repeats and the last loop is trimmed to the video`() {
        val segments = AudioPlacement.segments(music.copy(loop = true), 7_500_000)

        assertThat(segments).containsExactly(
            AudioSegment.Play(TimeRangeUs(1_000_000, 4_000_000)),
            AudioSegment.Play(TimeRangeUs(1_000_000, 4_000_000)),
            AudioSegment.Play(TimeRangeUs(1_000_000, 2_500_000)),
        ).inOrder()
        assertThat(segments.sumOf { (it as AudioSegment.Play).sourceRange.durationUs }).isEqualTo(7_500_000)
    }

    @Test
    fun `music that starts after the video ends is dropped`() {
        assertThat(AudioPlacement.segments(music.copy(timelineStartUs = 5_000_000), 5_000_000)).isEmpty()
    }
}
