package com.naury.framekit.core.video

import com.naury.framekit.core.overlay.Watermark
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
 * @property mutedRanges 소리를 끈 원본 구간들(원본 시간 µs). 겹치지 않게 정리해 둔다([TimeRanges.normalize]).
 *   클립을 옮기거나 잘라도 같은 장면의 소리가 꺼진 채로 남는다.
 */
public data class VideoClip(
    val id: String,
    val source: SourceId,
    val sourceRange: TimeRangeUs,
    val speed: Double = 1.0,
    val effects: ClipEffects = ClipEffects(),
    val muted: Boolean = false,
    val volume: Double = 1.0,
    val mutedRanges: List<TimeRangeUs> = emptyList(),
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

/** 별도 오디오 트랙의 쓰임새. 트랙마다 하나씩 둘 수 있다. */
public enum class AudioRole {
    /** 배경 음악. */
    MUSIC,

    /** 내레이션 같은 음성(녹음하거나 파일로 넣음). */
    VOICE,
}

/**
 * 별도 트랙에 놓이고 출력 타임라인 기준으로 배치되는 오디오 조각(배경 음악이나 음성). 한 트랙에 여러 조각을 둘 수 있다.
 *
 * @property loop [timelineEndUs](없으면 영상 끝)까지 구간을 반복한다. 오디오가 출력을 영상보다 길게 만들지는 않는다.
 * @property role 배경 음악인지 음성인지. 같은 쓰임새의 조각은 같은 트랙(줄)에 놓인다.
 * @property timelineEndUs 출력 시간에서 이 조각이 끝나는 위치(µs, 그 시각은 포함하지 않음). `null`이면 원본 구간이나 영상 끝에서 끝난다.
 *   타임라인에서 오른쪽 끝을 잘랐거나 나눈 조각에 쓴다.
 * @property fadeInUs 시작에서 소리를 서서히 키우는 시간(µs).
 * @property fadeOutUs 끝에서 소리를 서서히 줄이는 시간(µs).
 * @property startOffsetUs [sourceRange] 안에서 처음 재생을 시작하는 위치(µs, `0..sourceRange 길이`). 반복하면 두 번째부터는
 *   [sourceRange] 처음부터 재생한다. 조각을 나누거나 왼쪽 끝을 잘라도 같은 소리가 같은 시각에 들리게 한다.
 */
public data class AudioClip(
    val id: String,
    val source: SourceId,
    val sourceRange: TimeRangeUs,
    val timelineStartUs: Long,
    val volume: Double = 1.0,
    val loop: Boolean = false,
    val role: AudioRole = AudioRole.MUSIC,
    val timelineEndUs: Long? = null,
    val fadeInUs: Long = 0,
    val fadeOutUs: Long = 0,
    val startOffsetUs: Long = 0,
) {
    /**
     * 영상 길이가 [videoDurationUs]일 때 이 조각이 실제로 들리는 출력 구간. 들리지 않으면 비어 있다.
     */
    public fun outputRange(videoDurationUs: Long): TimeRangeUs {
        val start = timelineStartUs.coerceAtLeast(0)
        val natural = if (loop) Long.MAX_VALUE else start + (sourceRange.durationUs - startOffsetUs).coerceAtLeast(0)
        val end = minOf(natural, timelineEndUs ?: Long.MAX_VALUE, videoDurationUs)
        return TimeRangeUs(start, end.coerceAtLeast(start))
    }
}

/** 시간 범위 목록 계산. */
public object TimeRanges {

    /** 비었거나 겹치거나 맞닿은 범위를 합쳐 시작 순서로 돌려준다. */
    public fun normalize(ranges: List<TimeRangeUs>): List<TimeRangeUs> {
        val sorted = ranges.filter { !it.isEmpty }.sortedBy { it.startUs }
        val merged = mutableListOf<TimeRangeUs>()
        sorted.forEach { range ->
            val last = merged.lastOrNull()
            if (last != null && range.startUs <= last.endExclusiveUs) {
                merged[merged.lastIndex] = TimeRangeUs(last.startUs, maxOf(last.endExclusiveUs, range.endExclusiveUs))
            } else {
                merged += range
            }
        }
        return merged
    }

    /** [ranges]에서 [cut]과 겹치는 부분을 뺀다. */
    public fun subtract(ranges: List<TimeRangeUs>, cut: TimeRangeUs): List<TimeRangeUs> = normalize(
        ranges.flatMap { range ->
            if (cut.endExclusiveUs <= range.startUs || cut.startUs >= range.endExclusiveUs) {
                listOf(range)
            } else {
                listOf(TimeRangeUs(range.startUs, cut.startUs), TimeRangeUs(cut.endExclusiveUs, range.endExclusiveUs)).filter { !it.isEmpty }
            }
        },
    )

    /** [ranges]를 [window] 안으로 자른다. */
    public fun clip(ranges: List<TimeRangeUs>, window: TimeRangeUs): List<TimeRangeUs> = normalize(
        ranges.map { TimeRangeUs(maxOf(it.startUs, window.startUs), minOf(it.endExclusiveUs, window.endExclusiveUs)) },
    )
}

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
 * @property watermark 영상 처음부터 끝까지 모든 것 위에 얹는 글자 워터마크. 없으면 `null`.
 */
public data class Timeline(
    val videoClips: List<VideoClip>,
    val audioClips: List<AudioClip> = emptyList(),
    val overlays: List<TimedOverlay> = emptyList(),
    val privacyMasks: List<TimedPrivacyMask> = emptyList(),
    val watermark: Watermark? = null,
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
