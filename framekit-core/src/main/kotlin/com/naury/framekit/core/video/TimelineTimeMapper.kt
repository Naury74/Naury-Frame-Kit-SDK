package com.naury.framekit.core.video

import kotlin.math.roundToLong

/** Where an output time falls on the main track. */
public data class TimelinePosition(
    val clipIndex: Int,
    val clip: VideoClip,
    val clipStartUs: Long,
    val sourceTimeUs: Long,
)

/**
 * Converts between output timeline time and source time.
 *
 * `sourceTime = sourceStart + (timelineTime - clipStart) × speed`. Clip starts are the running sum of
 * integer output durations, so a hundred clips accumulate no drift.
 */
public object TimelineTimeMapper {

    /** Output start of every clip, in order. */
    public fun clipStarts(timeline: Timeline): List<Long> {
        var start = 0L
        return timeline.videoClips.map { clip -> start.also { start += clip.outputDurationUs } }
    }

    /** Total output duration of the main track. */
    public fun durationUs(timeline: Timeline): Long = timeline.videoClips.sumOf { it.outputDurationUs }

    /**
     * Clip and source time under [timelineUs]. A time exactly at a clip end belongs to the next clip;
     * the timeline end itself maps to the last frame position of the last clip.
     *
     * @return `null` for an empty timeline or a negative time.
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

    /** Source time at [offsetUs] of output time into [clip]. */
    public fun sourceTime(clip: VideoClip, offsetUs: Long): Long =
        clip.sourceRange.startUs + (offsetUs * clip.speed).roundToLong()

    /** Output offset into [clip] for a source time inside its range. */
    public fun outputOffset(clip: VideoClip, sourceUs: Long): Long =
        ((sourceUs - clip.sourceRange.startUs) / clip.speed).roundToLong()

    /**
     * Splits the clip under [timelineUs] into two clips that share the source and effects.
     *
     * @param minOutputDurationUs both halves must be at least this long in output time.
     * @param newId id of the second half.
     * @return the new timeline, or `null` at a clip boundary or when a half would be too short.
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

    /** Moves a clip to [toIndex]. Starts of all clips follow automatically. */
    public fun move(timeline: Timeline, fromIndex: Int, toIndex: Int): Timeline {
        val clips = timeline.videoClips.toMutableList()
        val clip = clips.removeAt(fromIndex)
        clips.add(toIndex.coerceIn(0, clips.size), clip)
        return timeline.copy(videoClips = clips)
    }
}
