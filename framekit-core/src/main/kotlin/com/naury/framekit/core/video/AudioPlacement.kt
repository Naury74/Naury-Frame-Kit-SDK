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
        if (start >= videoDurationUs || length <= 0) return emptyList()
        val segments = mutableListOf<AudioSegment>()
        if (start > 0) segments += AudioSegment.Silence(start)
        var cursor = start
        do {
            val playable = minOf(length, videoDurationUs - cursor)
            segments += AudioSegment.Play(TimeRangeUs(clip.sourceRange.startUs, clip.sourceRange.startUs + playable))
            cursor += playable
        } while (clip.loop && cursor < videoDurationUs)
        return segments
    }
}
