package com.naury.framekit.video

import com.naury.framekit.android.result.EditorErrorCode
import com.naury.framekit.android.result.FrameKitException
import com.naury.framekit.android.source.SourceLocation
import com.naury.framekit.core.geometry.GeometryFrame
import com.naury.framekit.core.model.PixelSize
import com.naury.framekit.core.model.SourceId
import com.naury.framekit.core.video.VideoClip
import com.naury.framekit.core.video.VideoProject
import com.naury.framekit.video.source.VideoSourceInfo
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** A clip with everything the Media3 adapter needs to build it. */
public data class ResolvedClip(val clip: VideoClip, val source: VideoSourceInfo, val location: SourceLocation)

/**
 * Snapshot of a [VideoProject] resolved against its sources. Preview and export build their Media3
 * composition from the same plan.
 *
 * @property canvasSize output frame size: the first clip's edited size, limited to [maxShortSide]
 *   and rounded to even numbers as encoders require.
 */
public data class VideoRenderPlan(
    val projectRevision: Long,
    val project: VideoProject,
    val canvasSize: PixelSize,
    val clips: List<ResolvedClip>,
)

/** Builds [VideoRenderPlan]s. */
public object VideoPlanFactory {

    /**
     * @throws FrameKitException with `INVALID_PROJECT` when a clip refers to an unknown source.
     */
    public fun create(
        project: VideoProject,
        sources: Map<SourceId, VideoSourceInfo>,
        locations: Map<SourceId, SourceLocation>,
        maxShortSide: Int,
    ): VideoRenderPlan {
        val clips = project.timeline.videoClips.map { clip ->
            val source = sources[clip.source] ?: throw FrameKitException(EditorErrorCode.INVALID_PROJECT, "Unknown clip source")
            val location = locations[clip.source] ?: throw FrameKitException(EditorErrorCode.INVALID_PROJECT, "Unknown clip location")
            ResolvedClip(clip, source, location)
        }
        if (clips.isEmpty()) throw FrameKitException(EditorErrorCode.INVALID_PROJECT, "Timeline is empty")
        return VideoRenderPlan(project.revision, project, canvasSize(clips.first(), project, maxShortSide), clips)
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
