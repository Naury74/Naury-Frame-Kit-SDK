package com.naury.framekit.motion.render

import com.naury.framekit.motion.model.CameraMotion
import com.naury.framekit.motion.model.LoopMode
import com.naury.framekit.motion.model.MotionKind
import com.naury.framekit.motion.model.MotionProject
import com.naury.framekit.motion.model.MotionTransition
import kotlin.math.ceil
import kotlin.math.roundToLong

/** 결과에 들어갈 프레임 하나. 같은 그림이 이어지는 구간은 하나로 합쳐 [durationMs]가 길어진다. */
public data class OutputFrame(val timeMs: Long, val durationMs: Long)

/** 결과 시각을 원본(사진 번호·움직이는 원본의 프레임)으로 옮기는 계산. */
public object MotionTimeline {

    /** 한 번 재생하는 길이(밀리초). 부메랑은 갔다가 돌아오는 길이까지 더한다. */
    public fun durationMs(project: MotionProject, media: MotionMedia): Long {
        val base = baseDurationMs(project, media)
        return if (project.loop == LoopMode.BOOMERANG) base * 2 else base
    }

    /** 부메랑을 뺀 앞으로 한 번 재생하는 길이(밀리초). */
    public fun baseDurationMs(project: MotionProject, media: MotionMedia): Long = when (project.kind) {
        MotionKind.SLIDESHOW -> project.slideDurationMs * media.slideCount.coerceAtLeast(1)
        MotionKind.ANIMATION -> {
            val total = media.animation?.totalMs ?: 0L
            val end = (project.trimEndMs ?: total).coerceIn(0, total)
            val start = project.trimStartMs.coerceIn(0, end)
            ((end - start) / project.speed).roundToLong().coerceAtLeast(1)
        }
    }

    /** 결과 시각 [timeMs]를 부메랑을 풀어 앞으로 재생하는 시각으로 바꾼다. */
    public fun baseTime(project: MotionProject, media: MotionMedia, timeMs: Long): Long {
        val base = baseDurationMs(project, media)
        val t = timeMs.coerceIn(0, durationMs(project, media))
        return if (project.loop == LoopMode.BOOMERANG && t > base) (2 * base - t).coerceAtLeast(0) else t.coerceAtMost(base)
    }

    /** 움직이는 원본에서 [timeMs]에 보일 프레임 번호. */
    public fun animationFrame(project: MotionProject, media: MotionMedia, timeMs: Long): Int {
        val animation = media.animation ?: return 0
        val source = project.trimStartMs + (baseTime(project, media, timeMs) * project.speed).roundToLong()
        var elapsed = 0L
        animation.durationsMs.forEachIndexed { index, duration ->
            elapsed += duration
            if (source < elapsed) return index
        }
        return animation.frames.lastIndex
    }

    /**
     * 슬라이드쇼에서 [timeMs]에 보일 사진.
     *
     * @return 사진 번호, 사진 안의 경과 시간(밀리초), 전환 진행률(`0..1`, 전환 중이 아니면 `null`).
     */
    public fun slideAt(project: MotionProject, media: MotionMedia, timeMs: Long): SlidePosition {
        val count = media.slideCount.coerceAtLeast(1)
        val t = baseTime(project, media, timeMs)
        val index = (t / project.slideDurationMs).toInt().coerceIn(0, count - 1)
        val local = t - index * project.slideDurationMs
        val transitionMs = project.transitionMs.coerceIn(0, project.slideDurationMs / 2)
        val transition = if (index > 0 && project.transition != MotionTransition.NONE && transitionMs > 0 && local < transitionMs) {
            local.toFloat() / transitionMs
        } else {
            null
        }
        return SlidePosition(index, local, transition)
    }

    /** 사진에 실제로 적용할 카메라 움직임. [CameraMotion.AUTO]는 사진마다 번갈아 고른다. */
    public fun cameraMotion(project: MotionProject, index: Int): CameraMotion =
        if (project.cameraMotion == CameraMotion.AUTO) AUTO_SEQUENCE[index % AUTO_SEQUENCE.size] else project.cameraMotion

    /**
     * 초당 [fps] 간격으로 결과 프레임을 만든다. 그림이 바뀌지 않는 구간(움직임·전환·움직이는 텍스트가 없는 구간)은 한 프레임으로 합쳐
     * GIF·WebP 크기를 줄인다.
     */
    public fun outputFrames(project: MotionProject, media: MotionMedia, fps: Int): List<OutputFrame> {
        val total = durationMs(project, media)
        val step = 1000.0 / fps.coerceIn(1, 60)
        val count = ceil(total / step).toInt().coerceAtLeast(1)
        val frames = mutableListOf<OutputFrame>()
        var previousKey: Long? = null
        for (k in 0 until count) {
            val start = (k * step).roundToLong()
            val end = ((k + 1) * step).roundToLong().coerceAtMost(total).coerceAtLeast(start + 1)
            val key = staticKey(project, media, start)
            if (key != null && key == previousKey) {
                val last = frames.removeAt(frames.lastIndex)
                frames += last.copy(durationMs = end - last.timeMs)
            } else {
                frames += OutputFrame(start, end - start)
            }
            previousKey = key
        }
        return frames
    }

    /** [timeMs]의 그림이 시간이 지나도 그대로면 같은 그림끼리 같은 값을, 움직이는 중이면 `null`을 돌려준다. */
    internal fun staticKey(project: MotionProject, media: MotionMedia, timeMs: Long): Long? {
        if (project.overlays.any { !it.animation.isEntrance && it.animation != com.naury.framekit.motion.model.OverlayAnimation.NONE }) return null
        if (project.overlays.any { it.animation.isEntrance } && timeMs < ENTRANCE_MS) return null
        return when (project.kind) {
            MotionKind.ANIMATION -> animationFrame(project, media, timeMs).toLong()
            MotionKind.SLIDESHOW -> {
                val position = slideAt(project, media, timeMs)
                if (position.transition != null || cameraMotion(project, position.index) != CameraMotion.NONE) null else position.index.toLong()
            }
        }
    }

    /** 등장 효과가 끝나는 시각(밀리초). */
    public const val ENTRANCE_MS: Long = 600

    private val AUTO_SEQUENCE = listOf(CameraMotion.ZOOM_IN, CameraMotion.PAN_LEFT, CameraMotion.ZOOM_OUT, CameraMotion.PAN_RIGHT)
}

/** 슬라이드쇼의 한 시점. [transition]은 앞 사진에서 넘어오는 진행률(`0..1`)이며 전환 중이 아니면 `null`. */
public data class SlidePosition(val index: Int, val localMs: Long, val transition: Float?)
