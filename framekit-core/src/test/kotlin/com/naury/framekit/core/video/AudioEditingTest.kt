package com.naury.framekit.core.video

import com.google.common.truth.Truth.assertThat
import com.naury.framekit.core.model.SourceId
import org.junit.Test

class AudioEditingTest {

    private val song = AudioClip("a", SourceId("m"), TimeRangeUs(0, 4_000_000), timelineStartUs = 1_000_000)

    @Test
    fun `splitting keeps every sound at the same time`() {
        val (first, second) = AudioEditing.split(song, 2_500_000, 10_000_000, "b", 100_000)!!
        assertThat(first.outputRange(10_000_000)).isEqualTo(TimeRangeUs(1_000_000, 2_500_000))
        assertThat(second.outputRange(10_000_000)).isEqualTo(TimeRangeUs(2_500_000, 5_000_000))
        // 뒤 조각은 원본 1.5초 지점부터 들린다.
        assertThat(AudioPlacement.segments(second, 10_000_000).last()).isEqualTo(AudioSegment.Play(TimeRangeUs(1_500_000, 4_000_000)))
        assertThat(AudioEditing.split(song, 1_050_000, 10_000_000, "b", 100_000)).isNull()
    }

    @Test
    fun `split looping clip continues the loop phase`() {
        val loop = song.copy(loop = true, timelineStartUs = 0)
        val (_, second) = AudioEditing.split(loop, 5_000_000, 10_000_000, "b", 100_000)!!
        val plays = AudioPlacement.segments(second, 10_000_000).filterIsInstance<AudioSegment.Play>()
        // 5초 지점은 두 번째 반복의 1초 지점이고, 그다음은 처음부터 다시 반복한다.
        assertThat(plays.first().sourceRange).isEqualTo(TimeRangeUs(1_000_000, 4_000_000))
        assertThat(plays[1].sourceRange).isEqualTo(TimeRangeUs(0, 2_000_000))
    }

    @Test
    fun `trimming edges and moving`() {
        val trimmed = AudioEditing.trimStart(song, 2_000_000, 10_000_000, 100_000)
        assertThat(trimmed.outputRange(10_000_000)).isEqualTo(TimeRangeUs(2_000_000, 5_000_000))
        assertThat(AudioPlacement.segments(trimmed, 10_000_000).last()).isEqualTo(AudioSegment.Play(TimeRangeUs(1_000_000, 4_000_000)))
        // 반복하지 않으면 원본 처음보다 앞으로 늘어나지 않는다.
        assertThat(AudioEditing.trimStart(trimmed, 0, 10_000_000, 100_000).timelineStartUs).isEqualTo(1_000_000)
        assertThat(AudioEditing.trimEnd(song, 9_000_000, 10_000_000, 100_000).outputRange(10_000_000).endExclusiveUs).isEqualTo(5_000_000)
        val moved = AudioEditing.move(trimmed, 7_500_000, 10_000_000)
        assertThat(moved.outputRange(10_000_000)).isEqualTo(TimeRangeUs(7_000_000, 10_000_000))
    }

    @Test
    fun `muted ranges merge and subtract`() {
        val merged = TimeRanges.normalize(listOf(TimeRangeUs(5, 8), TimeRangeUs(0, 3), TimeRangeUs(2, 4)))
        assertThat(merged).containsExactly(TimeRangeUs(0, 4), TimeRangeUs(5, 8)).inOrder()
        assertThat(TimeRanges.subtract(merged, TimeRangeUs(1, 6))).containsExactly(TimeRangeUs(0, 1), TimeRangeUs(6, 8)).inOrder()
    }
}
