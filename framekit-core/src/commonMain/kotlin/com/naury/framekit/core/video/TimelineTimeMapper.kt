package com.naury.framekit.core.video

import kotlin.math.roundToLong

/** 출력 시간이 메인 트랙에서 놓이는 위치. */
public data class TimelinePosition(
    val clipIndex: Int,
    val clip: VideoClip,
    val clipStartUs: Long,
    val sourceTimeUs: Long,
)

/**
 * 출력 타임라인 시간과 소스 시간 사이를 변환한다.
 *
 * `sourceTime = sourceStart + (timelineTime - clipStart) × speed`. clip 시작점은 정수 출력 길이의
 * 누적 합이므로 clip이 백 개여도 오차가 쌓이지 않는다.
 */
public object TimelineTimeMapper {

    /** 모든 clip의 출력 시작점. 순서대로다. */
    public fun clipStarts(timeline: Timeline): List<Long> {
        var start = 0L
        return timeline.videoClips.map { clip -> start.also { start += clip.outputDurationUs } }
    }

    /** 메인 트랙의 총 출력 길이. */
    public fun durationUs(timeline: Timeline): Long = timeline.videoClips.sumOf { it.outputDurationUs }

    /**
     * [timelineUs]에 해당하는 clip과 소스 시간. clip 끝과 정확히 같은 시간은 다음 clip에 속하며,
     * 타임라인 끝 자체는 마지막 clip의 마지막 프레임 위치로 매핑된다.
     *
     * @return 타임라인이 비었거나 시간이 음수이면 `null`.
     */
    public fun locate(timeline: Timeline, timelineUs: Long): TimelinePosition? {
        if (timeline.videoClips.isEmpty() || timelineUs < 0) return null
        val starts = clipStarts(timeline)
        val index = starts.indices.lastOrNull { starts[it] <= timelineUs && timelineUs < starts[it] + timeline.videoClips[it].outputDurationUs }
            ?: return if (timelineUs >= durationUs(timeline)) {
                val last = timeline.videoClips.lastIndex
                TimelinePosition(last, timeline.videoClips[last], starts[last], timeline.videoClips[last].sourceRange.endExclusiveUs)
            } else {
                null
            }
        val clip = timeline.videoClips[index]
        return TimelinePosition(index, clip, starts[index], sourceTime(clip, timelineUs - starts[index]))
    }

    /** [clip] 안으로 출력 시간 [offsetUs]만큼 들어간 지점의 소스 시간. */
    public fun sourceTime(clip: VideoClip, offsetUs: Long): Long =
        clip.sourceRange.startUs + (offsetUs * clip.speed).roundToLong()

    /** 범위 안의 소스 시간에 대한 [clip] 안의 출력 오프셋. */
    public fun outputOffset(clip: VideoClip, sourceUs: Long): Long =
        ((sourceUs - clip.sourceRange.startUs) / clip.speed).roundToLong()

    /**
     * [timelineUs] 아래의 clip을 소스와 효과를 공유하는 두 clip으로 나눈다.
     *
     * @param minOutputDurationUs 두 조각 모두 출력 시간 기준으로 최소 이 길이여야 한다.
     * @param newId 두 번째 조각의 id.
     * @return 새 타임라인. clip 경계이거나 한 조각이 너무 짧아지면 `null`.
     */
    public fun split(timeline: Timeline, timelineUs: Long, minOutputDurationUs: Long, newId: String): Timeline? {
        val position = locate(timeline, timelineUs) ?: return null
        val clip = position.clip
        val offset = timelineUs - position.clipStartUs
        if (offset < minOutputDurationUs || clip.outputDurationUs - offset < minOutputDurationUs) return null
        val cut = position.sourceTimeUs
        val first = clip.copy(sourceRange = TimeRangeUs(clip.sourceRange.startUs, cut))
        val second = clip.copy(id = newId, sourceRange = TimeRangeUs(cut, clip.sourceRange.endExclusiveUs))
        val clips = timeline.videoClips.toMutableList().apply {
            set(position.clipIndex, first)
            add(position.clipIndex + 1, second)
        }
        return timeline.copy(videoClips = clips)
    }

    /** clip을 [toIndex]로 옮긴다. 모든 clip의 시작점은 자동으로 따라온다. */
    public fun move(timeline: Timeline, fromIndex: Int, toIndex: Int): Timeline {
        val clips = timeline.videoClips.toMutableList()
        val clip = clips.removeAt(fromIndex)
        clips.add(toIndex.coerceIn(0, clips.size), clip)
        return timeline.copy(videoClips = clips)
    }
}
