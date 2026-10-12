package com.naury.framekit.core.audio

import com.google.common.truth.Truth.assertThat
import com.naury.framekit.core.model.SourceId
import com.naury.framekit.core.video.AudioClip
import com.naury.framekit.core.video.AudioPlacement
import com.naury.framekit.core.video.AudioRole
import com.naury.framekit.core.video.AudioSegment
import com.naury.framekit.core.video.TimeRangeUs
import org.junit.Test

class AudioProjectEditingTest {

    private var next = 0
    private val ids: () -> String = { "n${next++}" }

    private fun clip(id: String, lengthUs: Long) = AudioClip(id, SourceId(id), TimeRangeUs(0, lengthUs), timelineStartUs = 0, role = AudioRole.VOICE)

    private fun main(project: AudioProject) = project.mainClips.map { it.outputRange(project.durationUs) }

    @Test
    fun `appending and inserting keep the main lane gapless`() {
        var project = AudioProjectEditing.append(AudioProject(emptyList()), clip("a", 4_000_000), ids)
        project = AudioProjectEditing.append(project, clip("b", 2_000_000), ids)
        assertThat(project.durationUs).isEqualTo(6_000_000)
        project = AudioProjectEditing.insert(project, clip("c", 1_000_000), 1_500_000, ids)
        assertThat(main(project)).containsExactly(
            TimeRangeUs(0, 1_500_000), TimeRangeUs(1_500_000, 2_500_000), TimeRangeUs(2_500_000, 5_000_000), TimeRangeUs(5_000_000, 7_000_000),
        ).inOrder()
        // 나뉜 뒤 조각은 원본 1.5초부터 이어서 들린다.
        val rest = project.mainClips[2]
        assertThat(AudioPlacement.segments(rest, project.durationUs).last()).isEqualTo(AudioSegment.Play(TimeRangeUs(1_500_000, 4_000_000)))
    }

    @Test
    fun `deleting a range ripples later sound and layers`() {
        var project = AudioProjectEditing.append(AudioProject(emptyList()), clip("a", 6_000_000), ids)
        project = AudioProjectEditing.overlay(project, AudioClip("m", SourceId("m"), TimeRangeUs(0, 1_000_000), 0), 4_500_000)
        project = AudioProjectEditing.deleteRange(project, TimeRangeUs(1_000_000, 3_000_000), ids)
        assertThat(project.durationUs).isEqualTo(4_000_000)
        assertThat(main(project)).containsExactly(TimeRangeUs(0, 1_000_000), TimeRangeUs(1_000_000, 4_000_000)).inOrder()
        assertThat(project.layerClips.single().timelineStartUs).isEqualTo(2_500_000)
        assertThat(AudioPlacement.segments(project.mainClips[1], project.durationUs).last()).isEqualTo(AudioSegment.Play(TimeRangeUs(3_000_000, 6_000_000)))
    }

    @Test
    fun `keeping a range and deleting a clip`() {
        var project = AudioProjectEditing.append(AudioProject(emptyList()), clip("a", 3_000_000), ids)
        project = AudioProjectEditing.append(project, clip("b", 3_000_000), ids)
        val kept = AudioProjectEditing.keepRange(project, TimeRangeUs(2_000_000, 4_000_000), ids)
        assertThat(kept.durationUs).isEqualTo(2_000_000)
        assertThat(main(kept)).containsExactly(TimeRangeUs(0, 1_000_000), TimeRangeUs(1_000_000, 2_000_000)).inOrder()
        val removed = AudioProjectEditing.deleteClip(project, project.mainClips.first().id)
        assertThat(main(removed)).containsExactly(TimeRangeUs(0, 3_000_000))
    }
}
