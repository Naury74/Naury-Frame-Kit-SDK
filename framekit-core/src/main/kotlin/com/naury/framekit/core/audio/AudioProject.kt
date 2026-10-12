package com.naury.framekit.core.audio

import com.naury.framekit.core.model.ProjectSnapshot
import com.naury.framekit.core.video.AudioClip
import com.naury.framekit.core.video.AudioRole
import com.naury.framekit.core.video.TimeRangeUs

/**
 * 음성 편집 내용. 소리 조각([AudioClip])들을 두 줄에 놓는다.
 *
 * - 본 줄([AudioRole.VOICE]): 녹음·불러온 음성이 빈틈 없이 이어진다. 끼워 넣거나 구간을 지우면 뒤 조각이 당겨지거나 밀린다.
 * - 겹친 줄([AudioRole.MUSIC]): 배경 음악이나 겹쳐 녹음한 소리. 본 줄 위에 자유롭게 놓인다.
 *
 * 결과 길이는 본 줄 끝이다. 본 줄이 비어 있으면 겹친 줄의 끝이다. 시간은 모두 출력 시간(µs)이다.
 */
public data class AudioProject(
    val clips: List<AudioClip>,
    override val revision: Long = 0L,
) : ProjectSnapshot<AudioProject> {

    /** 본 줄 조각을 시작 순서로. */
    public val mainClips: List<AudioClip> get() = clips.filter { it.role == AudioRole.VOICE }.sortedBy { it.timelineStartUs }

    /** 겹친 줄 조각. */
    public val layerClips: List<AudioClip> get() = clips.filter { it.role != AudioRole.VOICE }

    /** 결과 길이(µs). */
    public val durationUs: Long
        get() {
            val main = mainClips.maxOfOrNull { it.outputRange(Long.MAX_VALUE / 4).endExclusiveUs } ?: 0L
            if (main > 0) return main
            return layerClips.filter { !it.loop }.maxOfOrNull { it.outputRange(Long.MAX_VALUE / 4).endExclusiveUs } ?: 0L
        }

    /** [clip]이 결과에서 들리는 구간. */
    public fun rangeOf(clip: AudioClip): TimeRangeUs = clip.outputRange(durationUs)

    override fun withRevision(revision: Long): AudioProject = copy(revision = revision)

    override fun sameContentAs(other: AudioProject): Boolean = clips == other.clips
}
