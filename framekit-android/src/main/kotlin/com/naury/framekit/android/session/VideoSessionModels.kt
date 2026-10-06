package com.naury.framekit.android.session

import com.naury.framekit.core.effect.FilterCatalog
import com.naury.framekit.core.effect.FilterSelection
import com.naury.framekit.core.geometry.GeometryEdit
import com.naury.framekit.core.geometry.RectN
import com.naury.framekit.core.model.ProjectId
import com.naury.framekit.core.model.SourceId
import com.naury.framekit.core.video.CanvasFit
import com.naury.framekit.core.video.CanvasSpec
import com.naury.framekit.core.video.ClipEffects
import com.naury.framekit.core.video.TimeRangeUs
import com.naury.framekit.core.video.TimedPrivacyMask
import com.naury.framekit.core.video.Timeline
import com.naury.framekit.core.video.VideoClip
import com.naury.framekit.core.video.VideoProject
import kotlinx.serialization.Serializable

/**
 * 디스크에 저장되는 확정된 영상 편집 내용.
 *
 * [SourceId]는 하나의 registry 안에서만 유효하므로, 클립은 세션 원본 목록에서의 위치로 원본을 가리킨다.
 */
@Serializable
public data class VideoProjectSnapshot(
    val projectId: String,
    val revision: Long,
    val grainSeed: Long,
    val clips: List<VideoClipSnapshot>,
    val privacyMasks: List<TimedMaskSnapshot> = emptyList(),
    val canvasAspectWidth: Int? = null,
    val canvasAspectHeight: Int? = null,
    val canvasFit: String = CanvasFit.FIT.name,
) {
    /**
     * @param sources 세션 원본 목록 순서대로 새로 등록한 registry 키.
     * @throws IllegalArgumentException 클립이 [sources]에 없는 원본을 가리킬 때.
     */
    public fun toProject(sources: List<SourceId>): VideoProject = VideoProject(
        id = ProjectId(projectId),
        timeline = Timeline(
            videoClips = clips.map { it.toModel(sources) },
            privacyMasks = privacyMasks.map(TimedMaskSnapshot::toModel),
        ),
        canvas = CanvasSpec(canvasAspectWidth, canvasAspectHeight, CanvasFit.entries.firstOrNull { it.name == canvasFit } ?: CanvasFit.FIT),
        grainSeed = grainSeed,
        revision = revision,
    )

    public companion object {
        /** @param sources 세션 원본 목록 순서대로의 registry 키. */
        public fun of(project: VideoProject, sources: List<SourceId>): VideoProjectSnapshot = VideoProjectSnapshot(
            projectId = project.id.value,
            revision = project.revision,
            grainSeed = project.grainSeed,
            clips = project.timeline.videoClips.map { VideoClipSnapshot.of(it, sources) },
            privacyMasks = project.timeline.privacyMasks.map(TimedMaskSnapshot::of),
            canvasAspectWidth = project.canvas.aspectWidth,
            canvasAspectHeight = project.canvas.aspectHeight,
            canvasFit = project.canvas.fit.name,
        )
    }
}

/** 저장 형식의 [VideoClip]. 구간은 원본 시간(µs)이다. */
@Serializable
public data class VideoClipSnapshot(
    val id: String,
    val sourceIndex: Int,
    val startUs: Long,
    val endUs: Long,
    val speed: Double = 1.0,
    val muted: Boolean = false,
    val volume: Double = 1.0,
    val geometry: GeometrySnapshot = GeometrySnapshot(),
    val adjustments: AdjustmentsSnapshot = AdjustmentsSnapshot(),
    val filterPresetId: String = FilterCatalog.ORIGINAL_ID,
    val filterIntensity: Double = 0.0,
) {
    public fun toModel(sources: List<SourceId>): VideoClip {
        require(sourceIndex in sources.indices) { "Unknown clip source" }
        return VideoClip(
            id = id,
            source = sources[sourceIndex],
            sourceRange = TimeRangeUs(startUs, endUs),
            speed = speed,
            effects = ClipEffects(geometry.toModel(), adjustments.toModel(), FilterSelection(filterPresetId, filterIntensity)),
            muted = muted,
            volume = volume,
        )
    }

    public companion object {
        public fun of(clip: VideoClip, sources: List<SourceId>): VideoClipSnapshot = VideoClipSnapshot(
            id = clip.id,
            sourceIndex = sources.indexOf(clip.source).also { require(it >= 0) { "Unknown clip source" } },
            startUs = clip.sourceRange.startUs,
            endUs = clip.sourceRange.endExclusiveUs,
            speed = clip.speed,
            muted = clip.muted,
            volume = clip.volume,
            geometry = GeometrySnapshot.of(clip.effects.geometry),
            adjustments = AdjustmentsSnapshot.of(clip.effects.adjustments),
            filterPresetId = clip.effects.filter.presetId,
            filterIntensity = clip.effects.filter.intensity,
        )
    }
}

/** 저장 형식의 [GeometryEdit]. [crop]은 left, top, right, bottom 순서다. */
@Serializable
public data class GeometrySnapshot(
    val quarterTurns: Int = 0,
    val straightenDegrees: Double = 0.0,
    val flipX: Boolean = false,
    val flipY: Boolean = false,
    val crop: List<Double> = listOf(0.0, 0.0, 1.0, 1.0),
) {
    public fun toModel(): GeometryEdit = GeometryEdit(
        quarterTurns = quarterTurns,
        straightenDegrees = straightenDegrees,
        flipX = flipX,
        flipY = flipY,
        crop = if (crop.size == 4) RectN(crop[0], crop[1], crop[2], crop[3]) else RectN.Full,
    )

    public companion object {
        public fun of(g: GeometryEdit): GeometrySnapshot =
            GeometrySnapshot(g.quarterTurns, g.straightenDegrees, g.flipX, g.flipY, listOf(g.crop.left, g.crop.top, g.crop.right, g.crop.bottom))
    }
}

/** 저장 형식의 [TimedPrivacyMask]. 구간은 출력 시간(µs)이다. */
@Serializable
public data class TimedMaskSnapshot(val mask: PrivacyMaskSnapshot, val startUs: Long, val endUs: Long) {
    public fun toModel(): TimedPrivacyMask = TimedPrivacyMask(mask.toModel(), TimeRangeUs(startUs, endUs))

    public companion object {
        public fun of(timed: TimedPrivacyMask): TimedMaskSnapshot =
            TimedMaskSnapshot(PrivacyMaskSnapshot.of(timed.mask), timed.range.startUs, timed.range.endExclusiveUs)
    }
}
