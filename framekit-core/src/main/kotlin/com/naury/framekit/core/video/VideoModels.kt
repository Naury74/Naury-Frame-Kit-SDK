package com.naury.framekit.core.video

import com.naury.framekit.core.effect.Adjustments
import com.naury.framekit.core.effect.ColorEffectSpec
import com.naury.framekit.core.effect.FilterSelection
import com.naury.framekit.core.geometry.GeometryEdit
import com.naury.framekit.core.model.ProjectId
import com.naury.framekit.core.model.ProjectSnapshot
import com.naury.framekit.core.model.SourceId
import com.naury.framekit.core.overlay.ImageOverlay
import com.naury.framekit.core.overlay.PrivacyMask
import kotlin.math.roundToLong

/**
 * Half-open time range `[startUs, endExclusiveUs)` in microseconds.
 *
 * Every time value in FrameKit is a [Long] count of microseconds; milliseconds appear only in UI text
 * and host results. Floating-point accumulation of time is not used anywhere.
 */
public data class TimeRangeUs(val startUs: Long, val endExclusiveUs: Long) {
    public val durationUs: Long get() = endExclusiveUs - startUs
    public val isEmpty: Boolean get() = durationUs <= 0

    /** `true` for `startUs <= timeUs < endExclusiveUs`; the end is not part of the range. */
    public operator fun contains(timeUs: Long): Boolean = timeUs in startUs until endExclusiveUs
}

/** Per-clip edits. Geometry, color and filter use the same models and renderer contract as images. */
public data class ClipEffects(
    val geometry: GeometryEdit = GeometryEdit(),
    val adjustments: Adjustments = Adjustments(),
    val filter: FilterSelection = FilterSelection(),
) {
    public fun colorSpec(grainSeed: Long): ColorEffectSpec = ColorEffectSpec.of(adjustments, filter, grainSeed)
}

/**
 * One segment of a source on the main video track.
 *
 * Its position on the output timeline is not stored; it follows from the order of clips and their
 * output durations, so reordering never leaves gaps or overlaps.
 *
 * @property sourceRange part of the source used, in source time.
 * @property speed constant playback speed, `0.25..4`. The output duration is `sourceRange / speed`.
 * @property volume audio gain `0..2`; `muted` removes the clip audio entirely.
 */
public data class VideoClip(
    val id: String,
    val source: SourceId,
    val sourceRange: TimeRangeUs,
    val speed: Double = 1.0,
    val effects: ClipEffects = ClipEffects(),
    val muted: Boolean = false,
    val volume: Double = 1.0,
) {
    /** Output duration, rounded once to the nearest microsecond. */
    public val outputDurationUs: Long get() = (sourceRange.durationUs / speed).roundToLong()

    public companion object {
        public const val MIN_SPEED: Double = 0.25
        public const val MAX_SPEED: Double = 4.0
        public const val MAX_VOLUME: Double = 2.0

        /** Speeds offered by the speed tool. */
        public val speedPresets: List<Double> = listOf(0.25, 0.5, 1.0, 1.5, 2.0, 4.0)
    }
}

/**
 * Background audio on its own track, positioned on the output timeline.
 *
 * @property loop repeats the range until the video ends; audio never makes the output longer than
 *   the video.
 */
public data class AudioClip(
    val id: String,
    val source: SourceId,
    val sourceRange: TimeRangeUs,
    val timelineStartUs: Long,
    val volume: Double = 1.0,
    val loop: Boolean = false,
)

/** Text or sticker shown during [range] of the output timeline, positioned in the output canvas. */
public data class TimedOverlay(val overlay: ImageOverlay, val range: TimeRangeUs)

/** Mosaic or blur over part of the output canvas during [range] of the output timeline. */
public data class TimedPrivacyMask(val mask: PrivacyMask, val range: TimeRangeUs)

/** How clips with a different aspect ratio are placed on the canvas. */
public enum class CanvasFit {
    /** Whole clip visible, letterboxed with black. */
    FIT,

    /** Canvas filled, edges cropped. */
    FILL,
}

/**
 * Output canvas. `null` size means the canvas follows the first clip's edited size.
 *
 * @property aspectWidth width part of the canvas ratio.
 * @property aspectHeight height part of the canvas ratio.
 */
public data class CanvasSpec(
    val aspectWidth: Int? = null,
    val aspectHeight: Int? = null,
    val fit: CanvasFit = CanvasFit.FIT,
)

public data class Timeline(
    val videoClips: List<VideoClip>,
    val audioClips: List<AudioClip> = emptyList(),
    val overlays: List<TimedOverlay> = emptyList(),
    val privacyMasks: List<TimedPrivacyMask> = emptyList(),
)

/** Final edit state of a video. Undo stores these snapshots, never decoded frames. */
public data class VideoProject(
    val id: ProjectId,
    val timeline: Timeline,
    val canvas: CanvasSpec = CanvasSpec(),
    val grainSeed: Long = 0L,
    override val revision: Long = 0L,
) : ProjectSnapshot<VideoProject> {

    override fun withRevision(revision: Long): VideoProject = copy(revision = revision)

    override fun sameContentAs(other: VideoProject): Boolean =
        id == other.id && timeline == other.timeline && canvas == other.canvas && grainSeed == other.grainSeed
}
