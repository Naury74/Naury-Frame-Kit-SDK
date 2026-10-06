package com.naury.framekit.core.video

import com.google.common.truth.Truth.assertThat
import com.naury.framekit.core.model.MediaType
import com.naury.framekit.core.model.PixelSize
import com.naury.framekit.core.model.ProjectId
import com.naury.framekit.core.model.SourceId
import com.naury.framekit.core.model.SourceMetadata
import com.naury.framekit.core.validation.ValidationCode
import com.naury.framekit.core.validation.ValidationResult
import com.naury.framekit.core.validation.VideoProjectValidator
import org.junit.Test

class TimelineTimeMapperTest {

    private val source = SourceId("v")

    private fun clip(id: String, start: Long, end: Long, speed: Double = 1.0) = VideoClip(id, source, TimeRangeUs(start, end), speed)

    @Test
    fun `T01 two to eight seconds at double speed lasts three seconds`() {
        assertThat(clip("a", 2_000_000, 8_000_000, 2.0).outputDurationUs).isEqualTo(3_000_000)
    }

    @Test
    fun `T02 output 11_5s in a clip starting at 10s maps to source 5s`() {
        // 앞 clip 10초 뒤에 source 2~8초 2배속 clip이 온다.
        val timeline = Timeline(listOf(clip("pad", 0, 10_000_000), clip("a", 2_000_000, 8_000_000, 2.0)))

        val position = checkNotNull(TimelineTimeMapper.locate(timeline, 11_500_000))

        assertThat(position.clip.id).isEqualTo("a")
        assertThat(position.clipStartUs).isEqualTo(10_000_000)
        assertThat(position.sourceTimeUs).isEqualTo(5_000_000)
    }

    @Test
    fun `T03 the end of a clip belongs to the next clip and the timeline end to the last frame`() {
        val timeline = Timeline(listOf(clip("a", 0, 1_000_000), clip("b", 5_000_000, 6_000_000)))

        assertThat(TimelineTimeMapper.locate(timeline, 999_999)?.clip?.id).isEqualTo("a")
        assertThat(TimelineTimeMapper.locate(timeline, 1_000_000)?.clip?.id).isEqualTo("b")
        assertThat(TimelineTimeMapper.locate(timeline, 1_000_000)?.sourceTimeUs).isEqualTo(5_000_000)
        val end = checkNotNull(TimelineTimeMapper.locate(timeline, 2_000_000))
        assertThat(end.clip.id).isEqualTo("b")
        assertThat(end.sourceTimeUs).isEqualTo(6_000_000)
    }

    @Test
    fun `T04 split keeps the total duration within one microsecond`() {
        val timeline = Timeline(listOf(clip("a", 1_000_000, 9_333_333, 1.5)))
        val before = TimelineTimeMapper.durationUs(timeline)

        val split = checkNotNull(TimelineTimeMapper.split(timeline, 2_777_777, 1_000_000, "b"))

        assertThat(split.videoClips.map { it.id }).containsExactly("a", "b").inOrder()
        assertThat(split.videoClips[0].sourceRange.endExclusiveUs).isEqualTo(split.videoClips[1].sourceRange.startUs)
        assertThat(TimelineTimeMapper.durationUs(split)).isWithin(1).of(before)
        assertThat(split.videoClips[1].effects).isEqualTo(timeline.videoClips[0].effects)
    }

    @Test
    fun `split is refused at boundaries and when a half is too short`() {
        val timeline = Timeline(listOf(clip("a", 0, 4_000_000), clip("b", 0, 4_000_000)))

        assertThat(TimelineTimeMapper.split(timeline, 4_000_000, 1_000_000, "c")).isNull()
        assertThat(TimelineTimeMapper.split(timeline, 500_000, 1_000_000, "c")).isNull()
        assertThat(TimelineTimeMapper.split(timeline, 2_000_000, 1_000_000, "c")).isNotNull()
    }

    @Test
    fun `T05 a hundred clips accumulate no drift`() {
        val clips = (0 until 100).map { clip("c$it", 0, 1_000_001, if (it % 3 == 0) 1.5 else 0.5) }
        val timeline = Timeline(clips)

        val expected = clips.sumOf { it.outputDurationUs }
        assertThat(TimelineTimeMapper.durationUs(timeline)).isEqualTo(expected)
        assertThat(TimelineTimeMapper.clipStarts(timeline).last()).isEqualTo(expected - clips.last().outputDurationUs)
        assertThat(TimelineTimeMapper.locate(timeline, expected - 1)?.clipIndex).isEqualTo(99)
    }

    @Test
    fun `T06 a clip shorter than the minimum after speed is invalid`() {
        val metadata = mapOf(source to SourceMetadata(source, MediaType.VIDEO, "video/mp4", PixelSize(1920, 1080), durationUs = 10_000_000, hasAudio = true))
        val project = VideoProject(ProjectId("p"), Timeline(listOf(clip("a", 0, 1_500_000, 2.0))))

        val result = VideoProjectValidator.validate(project, metadata, minClipOutputDurationUs = 1_000_000)

        assertThat((result as ValidationResult.Invalid).issues.map { it.code }).containsExactly(ValidationCode.OUT_OF_RANGE)
        assertThat(VideoProjectValidator.validate(project.copy(timeline = Timeline(listOf(clip("a", 0, 2_000_000, 2.0)))), metadata, 1_000_000).isValid).isTrue()
    }

    @Test
    fun `ranges outside the source and unknown sources are rejected`() {
        val metadata = mapOf(source to SourceMetadata(source, MediaType.VIDEO, "video/mp4", PixelSize(1920, 1080), durationUs = 5_000_000))
        val beyond = VideoProject(ProjectId("p"), Timeline(listOf(clip("a", 0, 6_000_000))))
        val unknown = VideoProject(ProjectId("p"), Timeline(listOf(VideoClip("a", SourceId("x"), TimeRangeUs(0, 1_000_000)))))

        assertThat(VideoProjectValidator.validate(beyond, metadata, 0).isValid).isFalse()
        assertThat((VideoProjectValidator.validate(unknown, metadata, 0) as ValidationResult.Invalid).issues.single().code)
            .isEqualTo(ValidationCode.SOURCE_MISMATCH)
    }

    @Test
    fun `move keeps clips contiguous`() {
        val timeline = Timeline(listOf(clip("a", 0, 1_000_000), clip("b", 0, 2_000_000), clip("c", 0, 3_000_000)))

        val moved = TimelineTimeMapper.move(timeline, 2, 0)

        assertThat(moved.videoClips.map { it.id }).containsExactly("c", "a", "b").inOrder()
        assertThat(TimelineTimeMapper.clipStarts(moved)).containsExactly(0L, 3_000_000L, 4_000_000L).inOrder()
    }
}
