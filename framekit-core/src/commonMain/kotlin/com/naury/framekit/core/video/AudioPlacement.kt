package com.naury.framekit.core.video

/** 배경 음악 트랙을 이루는 한 조각. */
public sealed interface AudioSegment {
    /** 출력 시간 기준으로 [durationUs] 동안 소리가 없다. */
    public data class Silence(val durationUs: Long) : AudioSegment

    /** 원본의 [sourceRange]를 재생한다. */
    public data class Play(val sourceRange: TimeRangeUs) : AudioSegment
}

/**
 * 배경 음악을 출력 타임라인에 배치한다.
 *
 * 음악은 영상보다 길게 출력을 늘리지 않는다. 영상이 끝나는 지점에서 잘리고, [AudioClip.loop]이면 영상이
 * 끝날 때까지 구간을 반복한 뒤 마지막 반복을 잘라 맞춘다.
 */
public object AudioPlacement {

    /**
     * @param videoDurationUs 출력 영상 길이(µs).
     * @return 앞쪽 무음과 재생 구간 목록. 영상 안에서 들리는 부분이 없으면 빈 목록.
     */
    public fun segments(clip: AudioClip, videoDurationUs: Long): List<AudioSegment> {
        val start = clip.timelineStartUs.coerceAtLeast(0)
        val length = clip.sourceRange.durationUs
        // 조각의 끝([AudioClip.timelineEndUs])이 있으면 영상 끝보다 먼저 멈춘다.
        val end = minOf(videoDurationUs, clip.timelineEndUs ?: Long.MAX_VALUE)
        if (start >= end || length <= 0) return emptyList()
        val segments = mutableListOf<AudioSegment>()
        if (start > 0) segments += AudioSegment.Silence(start)
        var cursor = start
        // 첫 재생은 startOffsetUs부터, 반복은 구간 처음부터 한다.
        var from = clip.sourceRange.startUs + clip.startOffsetUs.coerceIn(0, length - 1)
        do {
            val playable = minOf(clip.sourceRange.endExclusiveUs - from, end - cursor)
            if (playable <= 0) break
            segments += AudioSegment.Play(TimeRangeUs(from, from + playable))
            cursor += playable
            from = clip.sourceRange.startUs
        } while (clip.loop && cursor < end)
        return segments
    }
}
