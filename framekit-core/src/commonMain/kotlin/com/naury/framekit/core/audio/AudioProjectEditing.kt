package com.naury.framekit.core.audio

import com.naury.framekit.core.video.AudioClip
import com.naury.framekit.core.video.AudioEditing
import com.naury.framekit.core.video.AudioRole
import com.naury.framekit.core.video.TimeRangeUs

/**
 * [AudioProject] 편집 계산. 본 줄은 빈틈 없이 이어지도록(리플) 고치고, 겹친 줄은 같은 시각의 소리가 같은 자리에 남도록 함께 옮긴다.
 */
public object AudioProjectEditing {

    /**
     * 본 줄의 [atUs]에 [clip]을 끼워 넣는다. 그 자리에 조각이 있으면 둘로 나누고, 뒤의 소리(겹친 줄 포함)는 [clip] 길이만큼 밀린다.
     *
     * @param clip 원본 구간 전체를 쓰는 새 조각. 시작 위치와 줄은 이 함수가 정한다.
     */
    public fun insert(project: AudioProject, clip: AudioClip, atUs: Long, newId: () -> String): AudioProject {
        val at = atUs.coerceIn(0, project.durationUs)
        val length = clip.sourceRange.durationUs - clip.startOffsetUs
        val cut = cutAt(project, at, newId)
        val shifted = cut.clips.map { if (it.timelineStartUs >= at) shift(it, length) else it }
        val inserted = clip.copy(role = AudioRole.VOICE, timelineStartUs = at, timelineEndUs = at + length, loop = false)
        return cut.copy(clips = shifted + inserted)
    }

    /** 겹친 줄의 [atUs]에 [clip]을 겹쳐 놓는다. 다른 소리는 그대로다. */
    public fun overlay(project: AudioProject, clip: AudioClip, atUs: Long): AudioProject =
        project.copy(clips = project.clips + clip.copy(role = AudioRole.MUSIC, timelineStartUs = atUs.coerceAtLeast(0)))

    /** 본 줄 끝에 [clip]을 이어 붙인다. */
    public fun append(project: AudioProject, clip: AudioClip, newId: () -> String): AudioProject =
        insert(project, clip, project.durationUs, newId)

    /** [range]를 잘라 내고 뒤를 당긴다. 겹친 줄의 그 구간도 지운다. */
    public fun deleteRange(project: AudioProject, range: TimeRangeUs, newId: () -> String): AudioProject {
        val start = range.startUs.coerceIn(0, project.durationUs)
        val end = range.endExclusiveUs.coerceIn(start, project.durationUs)
        if (end <= start) return project
        val cut = cutAt(cutAt(project, start, newId), end, newId)
        val length = end - start
        val kept = cut.clips.mapNotNull { clip ->
            val r = clip.outputRange(cut.durationUs)
            when {
                r.startUs >= start && r.endExclusiveUs <= end && !(clip.loop && clip.role != AudioRole.VOICE) -> null
                r.startUs >= end -> shift(clip, -length)
                else -> clip
            }
        }
        return cut.copy(clips = kept)
    }

    /** [range]만 남기고 나머지를 지운다. */
    public fun keepRange(project: AudioProject, range: TimeRangeUs, newId: () -> String): AudioProject {
        val afterEnd = deleteRange(project, TimeRangeUs(range.endExclusiveUs, project.durationUs), newId)
        return deleteRange(afterEnd, TimeRangeUs(0, range.startUs), newId)
    }

    /** 조각을 지운다. 본 줄이면 뒤를 당겨 빈틈을 없앤다. */
    public fun deleteClip(project: AudioProject, id: String): AudioProject {
        val clip = project.clips.firstOrNull { it.id == id } ?: return project
        if (clip.role != AudioRole.VOICE) return project.copy(clips = project.clips.filterNot { it.id == id })
        val r = clip.outputRange(project.durationUs)
        val length = r.durationUs
        return project.copy(
            clips = project.clips.filterNot { it.id == id }.map { other -> if (other.timelineStartUs >= r.endExclusiveUs) shift(other, -length) else other },
        )
    }

    /** [id] 조각을 [atUs]에서 나눈다. 너무 끝이면 그대로다. */
    public fun split(project: AudioProject, id: String, atUs: Long, newId: String, minUs: Long): AudioProject {
        val clip = project.clips.firstOrNull { it.id == id } ?: return project
        val pieces = AudioEditing.split(clip, atUs, project.durationUs, newId, minUs) ?: return project
        return project.copy(clips = project.clips.flatMap { if (it.id == id) pieces.toList() else listOf(it) })
    }

    /** 결과 시간 [atUs]를 지나는 조각을 모두 둘로 나눈다(구간 편집 전 경계 만들기). */
    public fun cutAt(project: AudioProject, atUs: Long, newId: () -> String): AudioProject {
        val duration = project.durationUs
        val clips = project.clips.flatMap { clip ->
            val r = clip.outputRange(duration)
            if (atUs <= r.startUs || atUs >= r.endExclusiveUs) listOf(clip)
            else AudioEditing.split(clip, atUs, duration, newId(), 1)?.toList() ?: listOf(clip)
        }
        return project.copy(clips = clips)
    }

    /** 본 줄 조각을 시작 순서대로 빈틈 없이 붙인다(불러오기·되돌린 뒤 정리). */
    public fun closeGaps(project: AudioProject): AudioProject {
        var cursor = 0L
        val main = project.mainClips.map { clip ->
            val length = clip.outputRange(Long.MAX_VALUE / 4).durationUs
            val placed = clip.copy(timelineStartUs = cursor, timelineEndUs = cursor + length)
            cursor += length
            placed
        }
        return project.copy(clips = main + project.layerClips)
    }

    private fun shift(clip: AudioClip, deltaUs: Long): AudioClip =
        clip.copy(timelineStartUs = (clip.timelineStartUs + deltaUs).coerceAtLeast(0), timelineEndUs = clip.timelineEndUs?.let { (it + deltaUs).coerceAtLeast(0) })
}
