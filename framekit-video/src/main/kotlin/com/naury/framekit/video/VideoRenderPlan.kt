package com.naury.framekit.video

import com.naury.framekit.android.result.EditorErrorCode
import com.naury.framekit.android.result.FrameKitException
import com.naury.framekit.android.source.SourceLocation
import com.naury.framekit.core.geometry.GeometryFrame
import com.naury.framekit.core.model.PixelSize
import com.naury.framekit.core.model.SourceId
import com.naury.framekit.core.video.AudioClip
import com.naury.framekit.core.video.VideoClip
import com.naury.framekit.core.video.VideoProject
import com.naury.framekit.video.source.AudioSourceInfo
import com.naury.framekit.video.source.VideoSourceInfo
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Media3 어댑터가 클립을 만드는 데 필요한 정보를 모두 담은 클립이다. */
public data class ResolvedClip(val clip: VideoClip, val source: VideoSourceInfo, val location: SourceLocation)

/** 배경 음악 클립과 그 원본 정보·위치. */
public data class ResolvedAudio(val clip: AudioClip, val source: AudioSourceInfo, val location: SourceLocation)

/**
 * 원본 정보로 해석한 [VideoProject]의 스냅샷이다. 미리보기와 내보내기는 같은 계획으로 Media3
 * composition을 만든다.
 *
 * @property canvasSize 출력 프레임 크기. 첫 클립의 편집 후 크기를 [maxShortSide]로 제한하고,
 *   인코더 요구에 맞춰 짝수로 반올림한다.
 */
public data class VideoRenderPlan(
    val projectRevision: Long,
    val project: VideoProject,
    val canvasSize: PixelSize,
    val clips: List<ResolvedClip>,
    val audio: List<ResolvedAudio> = emptyList(),
)

/** [VideoRenderPlan]을 만든다. */
public object VideoPlanFactory {

    /**
     * @param audioSources 배경 음악 원본 정보. [locations]에는 음악 원본의 위치도 들어 있어야 한다.
     * @throws FrameKitException 클립이나 배경 음악이 알 수 없는 원본을 가리키면 `INVALID_PROJECT`.
     */
    public fun create(
        project: VideoProject,
        sources: Map<SourceId, VideoSourceInfo>,
        locations: Map<SourceId, SourceLocation>,
        maxShortSide: Int,
        audioSources: Map<SourceId, AudioSourceInfo> = emptyMap(),
    ): VideoRenderPlan {
        val clips = project.timeline.videoClips.map { clip ->
            val source = sources[clip.source] ?: throw FrameKitException(EditorErrorCode.INVALID_PROJECT, "Unknown clip source")
            val location = locations[clip.source] ?: throw FrameKitException(EditorErrorCode.INVALID_PROJECT, "Unknown clip location")
            ResolvedClip(clip, source, location)
        }
        if (clips.isEmpty()) throw FrameKitException(EditorErrorCode.INVALID_PROJECT, "Timeline is empty")
        val audio = project.timeline.audioClips.map { clip ->
            val source = audioSources[clip.source] ?: throw FrameKitException(EditorErrorCode.INVALID_PROJECT, "Unknown audio source")
            val location = locations[clip.source] ?: throw FrameKitException(EditorErrorCode.INVALID_PROJECT, "Unknown audio location")
            ResolvedAudio(clip, source, location)
        }
        return VideoRenderPlan(project.revision, project, canvasSize(clips.first(), project, maxShortSide), clips, audio)
    }

    private fun canvasSize(first: ResolvedClip, project: VideoProject, maxShortSide: Int): PixelSize {
        val geometry = first.clip.effects.geometry
        val frame = GeometryFrame(first.source.metadata.uprightSize, geometry)
        val crop = frame.cropPixelSize(geometry.crop)
        val ratio = project.canvas.aspectWidth?.let { w -> project.canvas.aspectHeight?.let { h -> w.toDouble() / h } }
        val (baseWidth, baseHeight) = if (ratio == null) {
            crop.width to crop.height
        } else if (crop.width / crop.height > ratio) {
            crop.height * ratio to crop.height
        } else {
            crop.width to crop.width / ratio
        }
        // 확대하지 않고 짧은 변만 제한한다. 인코더 요구 때문에 양쪽을 짝수로 맞춘다.
        val scale = min(1.0, maxShortSide / min(baseWidth, baseHeight))
        return PixelSize(even(baseWidth * scale), even(baseHeight * scale))
    }

    private fun even(value: Double): Int = max(2, (value / 2).roundToInt() * 2)
}
