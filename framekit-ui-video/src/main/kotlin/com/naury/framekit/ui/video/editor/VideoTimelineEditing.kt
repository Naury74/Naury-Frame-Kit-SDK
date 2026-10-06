package com.naury.framekit.ui.video.editor

import com.naury.framekit.core.overlay.ImageOverlay
import com.naury.framekit.core.video.AudioClip
import com.naury.framekit.core.video.TimeRangeUs
import com.naury.framekit.core.video.TimedOverlay
import com.naury.framekit.core.video.TimedPrivacyMask
import com.naury.framekit.core.video.TimelineTimeMapper
import com.naury.framekit.core.video.VideoClip
import com.naury.framekit.core.video.VideoProject

/**
 * 영상 프로젝트의 클립·구간 항목을 고치는 순수 함수 모음. ViewModel 밖에 두어 직접 테스트한다.
 *
 * 텍스트·스티커·마스크·배경 음악은 출력 타임라인의 절대 시간에 붙어 있다. 클립을 자르거나 순서를 바꿔도
 * 그 시간은 그대로이고, 결과가 짧아지면 끝을 넘는 부분만 잘라 낸다.
 */
internal object VideoTimelineEditing {

    fun updateClip(project: VideoProject, id: String, transform: (VideoClip) -> VideoClip): VideoProject =
        project.copy(timeline = project.timeline.copy(videoClips = project.timeline.videoClips.map { if (it.id == id) transform(it) else it }))

    /** 재생 위치에서 클립을 둘로 나눈다. 경계이거나 최소 길이보다 짧아지면 `null`. */
    fun split(project: VideoProject, positionUs: Long, minClipUs: Long, newId: String): VideoProject? =
        TimelineTimeMapper.split(project.timeline, positionUs, minClipUs, newId)?.let { project.copy(timeline = it) }

    /** 클립을 [delta]칸 옮긴다. 범위를 벗어나면 그대로 둔다. */
    fun move(project: VideoProject, id: String, delta: Int): VideoProject {
        val clips = project.timeline.videoClips
        val from = clips.indexOfFirst { it.id == id }
        val to = from + delta
        if (from < 0 || to !in clips.indices) return project
        return project.copy(timeline = TimelineTimeMapper.move(project.timeline, from, to))
    }

    /** 클립을 지운다. 마지막 남은 클립은 지우지 않는다. */
    fun remove(project: VideoProject, id: String): VideoProject {
        val clips = project.timeline.videoClips
        if (clips.size <= 1) return project
        return clampTimed(project.copy(timeline = project.timeline.copy(videoClips = clips.filterNot { it.id == id })))
    }

    fun append(project: VideoProject, clips: List<VideoClip>): VideoProject =
        project.copy(timeline = project.timeline.copy(videoClips = project.timeline.videoClips + clips))

    /** 클립의 출력 시작 시간(µs). 없으면 `0`. */
    fun clipStart(project: VideoProject, id: String): Long {
        val index = project.timeline.videoClips.indexOfFirst { it.id == id }
        return if (index < 0) 0L else TimelineTimeMapper.clipStarts(project.timeline)[index]
    }

    /** 출력 시간 [positionUs]에 있는 클립 id. 끝을 넘으면 마지막 클립. */
    fun clipAt(project: VideoProject, positionUs: Long): String? =
        TimelineTimeMapper.locate(project.timeline, positionUs)?.let { project.timeline.videoClips[it.clipIndex].id }
            ?: project.timeline.videoClips.lastOrNull()?.id

    // ---- 구간 항목 ----

    fun updateMask(project: VideoProject, id: String, transform: (TimedPrivacyMask) -> TimedPrivacyMask): VideoProject =
        project.copy(timeline = project.timeline.copy(privacyMasks = project.timeline.privacyMasks.map { if (it.mask.id == id) transform(it) else it }))

    fun updateOverlay(project: VideoProject, id: String, transform: (TimedOverlay) -> TimedOverlay): VideoProject =
        project.copy(timeline = project.timeline.copy(overlays = project.timeline.overlays.map { if (it.overlay.id == id) transform(it) else it }))

    fun replaceOverlay(project: VideoProject, overlay: ImageOverlay): VideoProject =
        updateOverlay(project, overlay.id) { it.copy(overlay = overlay) }

    fun removeOverlay(project: VideoProject, id: String): VideoProject =
        project.copy(timeline = project.timeline.copy(overlays = project.timeline.overlays.filterNot { it.overlay.id == id }))

    /**
     * 구간 항목(마스크·텍스트·스티커)의 시작 또는 끝을 [positionUs]로 옮긴다. 최소 [minUs]는 남긴다.
     */
    fun moveEdge(range: TimeRangeUs, positionUs: Long, start: Boolean, durationUs: Long, minUs: Long): TimeRangeUs =
        if (start) {
            TimeRangeUs(positionUs.coerceIn(0, (range.endExclusiveUs - minUs).coerceAtLeast(0)), range.endExclusiveUs)
        } else {
            TimeRangeUs(range.startUs, (positionUs + minUs).coerceIn(range.startUs + minUs, durationUs.coerceAtLeast(range.startUs + minUs)))
        }

    /** 재생 위치부터 [lengthUs] 동안. 끝에 가까우면 끝에 맞춰 앞으로 당긴다. */
    fun defaultRange(positionUs: Long, durationUs: Long, lengthUs: Long): TimeRangeUs {
        val length = minOf(lengthUs, durationUs)
        val start = positionUs.coerceIn(0, durationUs - length)
        return TimeRangeUs(start, start + length)
    }

    /**
     * 결과 길이가 바뀐 뒤(구간·속도·클립 삭제) 끝을 넘는 구간 항목을 잘라 내고, 비게 된 항목과
     * 영상이 끝난 뒤에 시작하는 배경 음악은 지운다.
     */
    fun clampTimed(project: VideoProject): VideoProject {
        val timeline = project.timeline
        val duration = TimelineTimeMapper.durationUs(timeline)
        fun clamp(range: TimeRangeUs) = TimeRangeUs(range.startUs.coerceIn(0, duration), range.endExclusiveUs.coerceIn(0, duration))
        val masks = timeline.privacyMasks.mapNotNull { timed -> clamp(timed.range).takeUnless { it.isEmpty }?.let { timed.copy(range = it) } }
        val overlays = timeline.overlays.mapNotNull { timed -> clamp(timed.range).takeUnless { it.isEmpty }?.let { timed.copy(range = it) } }
        val audio = timeline.audioClips.filter { it.timelineStartUs < duration }
        if (masks == timeline.privacyMasks && overlays == timeline.overlays && audio == timeline.audioClips) return project
        return project.copy(timeline = timeline.copy(privacyMasks = masks, overlays = overlays, audioClips = audio))
    }

    // ---- 배경 음악 ----

    /** 배경 음악은 한 곡만 둔다. 새 곡은 기존 곡을 바꾼다. */
    fun setMusic(project: VideoProject, music: AudioClip?): VideoProject =
        project.copy(timeline = project.timeline.copy(audioClips = listOfNotNull(music)))

    fun updateMusic(project: VideoProject, transform: (AudioClip) -> AudioClip): VideoProject =
        project.copy(timeline = project.timeline.copy(audioClips = project.timeline.audioClips.map(transform)))
}
