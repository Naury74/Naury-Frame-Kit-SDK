package com.naury.framekit.motion.model

import com.naury.framekit.core.effect.Adjustments
import com.naury.framekit.core.effect.ColorEffectSpec
import com.naury.framekit.core.effect.FilterSelection
import com.naury.framekit.core.model.ProjectSnapshot
import com.naury.framekit.core.overlay.ImageOverlay
import com.naury.framekit.core.model.SourceId
import com.naury.framekit.core.video.AudioClip
import com.naury.framekit.core.video.AudioRole
import com.naury.framekit.core.video.TimeRangeUs

/** 움직이는 이미지를 만드는 방식. */
public enum class MotionKind {
    /** 사진 여러 장(또는 한 장)을 움직임·전환과 함께 이어 붙인다. */
    SLIDESHOW,

    /** 이미 움직이는 이미지(GIF·움직이는 WebP) 하나를 편집한다. */
    ANIMATION,
}

/** 사진 한 장에 주는 카메라 움직임(켄 번즈). */
public enum class CameraMotion {
    NONE,
    ZOOM_IN,
    ZOOM_OUT,
    PAN_LEFT,
    PAN_RIGHT,

    /** 사진마다 확대·이동을 번갈아 준다. */
    AUTO,
}

/** 사진과 사진 사이의 전환. */
public enum class MotionTransition {
    NONE,
    FADE,
    SLIDE,
    ZOOM,
}

/** 재생 방식. [BOOMERANG]은 끝까지 간 뒤 거꾸로 돌아온다. */
public enum class LoopMode {
    FORWARD,
    BOOMERANG,
}

/** 결과 화면 비율. [ORIGINAL]은 첫 원본의 비율을 따른다. */
public enum class MotionAspect(public val widthOverHeight: Double?) {
    ORIGINAL(null),
    SQUARE(1.0),
    PORTRAIT_4_5(4.0 / 5.0),
    STORY_9_16(9.0 / 16.0),
    WIDE_16_9(16.0 / 9.0),
}

/** 원본을 결과 화면에 맞추는 방식. [FILL]은 잘라서 채우고 [FIT]은 여백을 배경색으로 채운다. */
public enum class FitMode {
    FILL,
    FIT,
}

/**
 * 텍스트·스티커의 움직임. 등장 효과([FADE_IN], [POP], [SLIDE_UP])는 처음 한 번, 반복 효과([BOUNCE], [WIGGLE], [PULSE])는 계속 움직인다.
 */
public enum class OverlayAnimation {
    NONE,
    FADE_IN,
    POP,
    SLIDE_UP,
    BOUNCE,
    WIGGLE,
    PULSE,
    ;

    /** 처음 등장할 때만 움직이면 `true`. */
    public val isEntrance: Boolean get() = this == FADE_IN || this == POP || this == SLIDE_UP
}

/**
 * 배경 음악이나 음성 트랙. 시간은 모두 밀리초다.
 *
 * @property sourceId 소리 원본의 id.
 * @property sourceStartMs 원본에서 쓰기 시작하는 위치.
 * @property sourceDurationMs 원본 전체 길이.
 * @property timelineStartMs 결과에서 소리가 시작하는 위치.
 * @property volume `0..2`(1이 원래 크기).
 * @property loop [timelineEndMs](없으면 결과 끝)까지 반복한다.
 * @property timelineEndMs 결과에서 이 조각이 끝나는 위치. `null`이면 원본 끝이나 결과 끝.
 * @property fadeInMs 시작에서 서서히 키우는 시간.
 * @property fadeOutMs 끝에서 서서히 줄이는 시간.
 * @property startOffsetMs 첫 재생을 [sourceStartMs]에서 얼마나 지난 곳부터 할지(나눈 조각·왼쪽을 자른 조각). 반복은 [sourceStartMs]부터다.
 */
public data class MotionAudio(
    val id: String,
    val sourceId: String,
    val role: AudioRole,
    val sourceStartMs: Long,
    val sourceDurationMs: Long,
    val timelineStartMs: Long = 0,
    val volume: Double = 1.0,
    val loop: Boolean = true,
    val timelineEndMs: Long? = null,
    val fadeInMs: Long = 0,
    val fadeOutMs: Long = 0,
    val startOffsetMs: Long = 0,
) {
    /** 영상 모델의 오디오 클립으로 바꾼다(믹서·배치·나누기 계산에 쓴다). */
    public fun toAudioClip(): AudioClip = AudioClip(
        id = id,
        source = SourceId(sourceId),
        sourceRange = TimeRangeUs(sourceStartMs * 1000, sourceDurationMs.coerceAtLeast(sourceStartMs + 1) * 1000),
        timelineStartUs = timelineStartMs * 1000,
        volume = volume,
        loop = loop,
        role = role,
        timelineEndUs = timelineEndMs?.let { it * 1000 },
        fadeInUs = fadeInMs * 1000,
        fadeOutUs = fadeOutMs * 1000,
        startOffsetUs = startOffsetMs * 1000,
    )

    public companion object {
        public const val MAX_VOLUME: Double = 2.0

        /** [toAudioClip]의 반대. 원본 길이([sourceDurationMs])는 그대로 받는다. */
        public fun fromAudioClip(clip: AudioClip, sourceDurationMs: Long): MotionAudio = MotionAudio(
            id = clip.id,
            sourceId = clip.source.value,
            role = clip.role,
            sourceStartMs = clip.sourceRange.startUs / 1000,
            sourceDurationMs = sourceDurationMs,
            timelineStartMs = clip.timelineStartUs / 1000,
            volume = clip.volume,
            loop = clip.loop,
            timelineEndMs = clip.timelineEndUs?.let { it / 1000 },
            fadeInMs = clip.fadeInUs / 1000,
            fadeOutMs = clip.fadeOutUs / 1000,
            startOffsetMs = clip.startOffsetUs / 1000,
        )
    }
}

/** 움직임이 붙은 텍스트·스티커. */
public data class MotionOverlay(val overlay: ImageOverlay, val animation: OverlayAnimation = OverlayAnimation.NONE)

/**
 * 움직이는 이미지 편집 내용. 원본은 [sourceIds]로만 참조한다.
 *
 * @property sourceIds 사진 순서. [MotionKind.ANIMATION]이면 하나뿐이다.
 * @property slideDurationMs 사진 한 장을 보여 주는 시간(밀리초, [MIN_SLIDE_MS]..[MAX_SLIDE_MS]). 슬라이드쇼에만 쓴다.
 * @property transitionMs 전환 시간(밀리초). [slideDurationMs]의 절반을 넘지 않는다.
 * @property speed 움직이는 원본의 재생 속도(배, [MIN_SPEED]..[MAX_SPEED]).
 * @property trimStartMs 움직이는 원본에서 쓸 구간의 시작(밀리초).
 * @property trimEndMs 쓸 구간의 끝(밀리초). `null`이면 끝까지.
 * @property backgroundArgb [FitMode.FIT]일 때 여백 색.
 * @property audio 배경 음악·음성 조각들. 같은 쓰임새의 조각은 같은 줄에 놓인다. MP4로 저장할 때만 담긴다.
 */
public data class MotionProject(
    val kind: MotionKind,
    val sourceIds: List<String>,
    val slideDurationMs: Long = DEFAULT_SLIDE_MS,
    val transition: MotionTransition = MotionTransition.FADE,
    val transitionMs: Long = DEFAULT_TRANSITION_MS,
    val cameraMotion: CameraMotion = CameraMotion.AUTO,
    val speed: Double = 1.0,
    val trimStartMs: Long = 0,
    val trimEndMs: Long? = null,
    val loop: LoopMode = LoopMode.FORWARD,
    val aspect: MotionAspect = MotionAspect.ORIGINAL,
    val fit: FitMode = FitMode.FILL,
    val backgroundArgb: Int = 0xFF000000.toInt(),
    val adjustments: Adjustments = Adjustments(),
    val filter: FilterSelection = FilterSelection(),
    val overlays: List<MotionOverlay> = emptyList(),
    val audio: List<MotionAudio> = emptyList(),
    val grainSeed: Long = 0L,
    override val revision: Long = 0L,
) : ProjectSnapshot<MotionProject> {

    /** 이 편집의 색 보정·필터. */
    public val colorSpec: ColorEffectSpec get() = ColorEffectSpec.of(adjustments, filter, grainSeed)

    override fun withRevision(revision: Long): MotionProject = copy(revision = revision)

    override fun sameContentAs(other: MotionProject): Boolean = copy(revision = 0) == other.copy(revision = 0)

    public companion object {
        public const val DEFAULT_SLIDE_MS: Long = 2_000
        public const val MIN_SLIDE_MS: Long = 300
        public const val MAX_SLIDE_MS: Long = 10_000
        public const val DEFAULT_TRANSITION_MS: Long = 500
        public const val MIN_SPEED: Double = 0.25
        public const val MAX_SPEED: Double = 4.0
    }
}
