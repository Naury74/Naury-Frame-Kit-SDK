package com.naury.framekit.core.video

/**
 * 오디오 조각을 타임라인에서 고치는 계산(나누기, 양 끝 자르기, 옮기기). 결과는 같은 소리가 같은 시각에 들리도록 [AudioClip.startOffsetUs]를
 * 맞춘다. 시간은 모두 출력 시간(µs)이다.
 */
public object AudioEditing {

    /**
     * [clip]을 [atUs]에서 둘로 나눈다.
     *
     * @param minUs 나눈 두 조각이 가져야 할 최소 길이.
     * @return 앞 조각과 뒤 조각. [atUs]가 들리는 구간 안이 아니거나 너무 끝이면 `null`.
     */
    public fun split(clip: AudioClip, atUs: Long, videoDurationUs: Long, newId: String, minUs: Long): Pair<AudioClip, AudioClip>? {
        val range = clip.outputRange(videoDurationUs)
        if (atUs - range.startUs < minUs || range.endExclusiveUs - atUs < minUs) return null
        val first = clip.copy(timelineEndUs = atUs, fadeOutUs = 0)
        val second = clip.copy(
            id = newId,
            timelineStartUs = atUs,
            startOffsetUs = offsetAfter(clip, atUs - clip.timelineStartUs),
            timelineEndUs = range.endExclusiveUs,
            fadeInUs = 0,
        )
        return first to second
    }

    /** 왼쪽 끝을 [startUs]로 옮긴다. 들리던 소리는 제자리에 남고 앞부분만 잘리거나(반복하지 않으면 원본 처음까지) 늘어난다. */
    public fun trimStart(clip: AudioClip, startUs: Long, videoDurationUs: Long, minUs: Long): AudioClip {
        val range = clip.outputRange(videoDurationUs)
        val length = clip.sourceRange.durationUs
        // 반복하지 않으면 원본 처음보다 앞으로는 늘릴 수 없다.
        val earliest = if (clip.loop) 0 else (clip.timelineStartUs - clip.startOffsetUs).coerceAtLeast(0)
        val target = startUs.coerceIn(earliest, range.endExclusiveUs - minUs)
        val delta = target - clip.timelineStartUs
        val offset = if (clip.loop) Math.floorMod(clip.startOffsetUs + delta, length) else (clip.startOffsetUs + delta).coerceIn(0, length - 1)
        return clip.copy(timelineStartUs = target, startOffsetUs = offset, timelineEndUs = range.endExclusiveUs)
    }

    /** 오른쪽 끝을 [endUs]로 옮긴다. 반복하지 않으면 원본 끝보다 길어지지 않는다. */
    public fun trimEnd(clip: AudioClip, endUs: Long, videoDurationUs: Long, minUs: Long): AudioClip {
        val natural = if (clip.loop) videoDurationUs else clip.timelineStartUs + clip.sourceRange.durationUs - clip.startOffsetUs
        val target = endUs.coerceIn(clip.timelineStartUs + minUs, minOf(natural, videoDurationUs))
        return clip.copy(timelineEndUs = target)
    }

    /** 조각 전체를 [startUs]로 옮긴다. 길이는 그대로다. */
    public fun move(clip: AudioClip, startUs: Long, videoDurationUs: Long): AudioClip {
        val range = clip.outputRange(videoDurationUs)
        val length = range.durationUs
        val target = startUs.coerceIn(0, (videoDurationUs - length).coerceAtLeast(0))
        return clip.copy(timelineStartUs = target, timelineEndUs = target + length)
    }

    // 시작에서 elapsed만큼 지난 뒤 들리는 원본 위치(구간 처음 기준).
    private fun offsetAfter(clip: AudioClip, elapsed: Long): Long {
        val length = clip.sourceRange.durationUs
        val raw = clip.startOffsetUs + elapsed
        return if (clip.loop) Math.floorMod(raw, length) else raw.coerceIn(0, length - 1)
    }
}
