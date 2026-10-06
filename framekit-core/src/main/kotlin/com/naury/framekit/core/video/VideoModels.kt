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
 * 마이크로초 단위 반열린 시간 범위 `[startUs, endExclusiveUs)`.
 *
 * FrameKit의 모든 시간 값은 마이크로초 수를 담은 [Long]이다. 밀리초는 UI 텍스트와 호스트 결과에만
 * 나타난다. 시간을 부동소수로 누적하는 곳은 어디에도 없다.
 */
public data class TimeRangeUs(val startUs: Long, val endExclusiveUs: Long) {
    public val durationUs: Long get() = endExclusiveUs - startUs
    public val isEmpty: Boolean get() = durationUs <= 0

    /** `startUs <= timeUs < endExclusiveUs`이면 `true`. 끝은 범위에 포함되지 않는다. */
    public operator fun contains(timeUs: Long): Boolean = timeUs in startUs until endExclusiveUs
}

/** clip별 편집. geometry, 색상, 필터는 이미지와 같은 모델과 렌더러 계약을 쓴다. */
public data class ClipEffects(
    val geometry: GeometryEdit = GeometryEdit(),
    val adjustments: Adjustments = Adjustments(),
    val filter: FilterSelection = FilterSelection(),
) {
    public fun colorSpec(grainSeed: Long): ColorEffectSpec = ColorEffectSpec.of(adjustments, filter, grainSeed)
}

/**
 * 메인 영상 트랙에 놓이는 소스의 한 구간.
 *
 * 출력 타임라인상의 위치는 저장하지 않는다. clip 순서와 출력 길이에서 결정되므로 순서를 바꿔도
 * 빈틈이나 겹침이 생기지 않는다.
 *
 * @property sourceRange 소스 시간 기준으로 사용하는 소스 구간.
 * @property speed 일정한 재생 속도, `0.25..4`. 출력 길이는 `sourceRange / speed`이다.
 * @property volume 오디오 게인 `0..2`. `muted`는 clip 오디오를 완전히 제거한다.
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
    /** 출력 길이. 가장 가까운 마이크로초로 한 번만 반올림한다. */
    public val outputDurationUs: Long get() = (sourceRange.durationUs / speed).roundToLong()

    public companion object {
        public const val MIN_SPEED: Double = 0.25
        public const val MAX_SPEED: Double = 4.0
        public const val MAX_VOLUME: Double = 2.0

        /** 속도 도구가 제공하는 속도. */
        public val speedPresets: List<Double> = listOf(0.25, 0.5, 1.0, 1.5, 2.0, 4.0)
    }
}

/**
 * 별도 트랙에 놓이고 출력 타임라인 기준으로 배치되는 배경 오디오.
 *
 * @property loop 영상이 끝날 때까지 구간을 반복한다. 오디오가 출력을 영상보다 길게 만들지는
 *   않는다.
 */
public data class AudioClip(
    val id: String,
    val source: SourceId,
    val sourceRange: TimeRangeUs,
    val timelineStartUs: Long,
    val volume: Double = 1.0,
    val loop: Boolean = false,
)

/** 출력 타임라인의 [range] 동안 보이는 텍스트나 스티커. 출력 캔버스 기준으로 배치한다. */
public data class TimedOverlay(val overlay: ImageOverlay, val range: TimeRangeUs)

/** 출력 타임라인의 [range] 동안 출력 캔버스 일부에 적용하는 모자이크 또는 블러. */
public data class TimedPrivacyMask(val mask: PrivacyMask, val range: TimeRangeUs)

/** 가로세로 비율이 다른 clip을 캔버스에 배치하는 방식. */
public enum class CanvasFit {
    /** clip 전체가 보이고 남는 부분은 검은색 레터박스로 채운다. */
    FIT,

    /** 캔버스를 가득 채우고 가장자리는 잘라낸다. */
    FILL,
}

/**
 * 출력 캔버스. size가 `null`이면 캔버스는 첫 clip의 편집된 크기를 따른다.
 *
 * @property aspectWidth 캔버스 비율의 너비 부분.
 * @property aspectHeight 캔버스 비율의 높이 부분.
 */
public data class CanvasSpec(
    val aspectWidth: Int? = null,
    val aspectHeight: Int? = null,
    val fit: CanvasFit = CanvasFit.FIT,
)

/**
 * @property privacyMasks 최대 [MAX_PRIVACY_MASKS]개의 사각형 또는 타원 마스크. 브러시 마스크는
 *   이미지 전용이다.
 */
public data class Timeline(
    val videoClips: List<VideoClip>,
    val audioClips: List<AudioClip> = emptyList(),
    val overlays: List<TimedOverlay> = emptyList(),
    val privacyMasks: List<TimedPrivacyMask> = emptyList(),
) {
    public companion object {
        public const val MAX_PRIVACY_MASKS: Int = 8
    }
}

/** 영상의 최종 편집 상태. 실행 취소는 디코딩된 프레임이 아니라 이 스냅샷을 저장한다. */
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
