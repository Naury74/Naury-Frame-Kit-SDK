package com.naury.framekit.motion.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import androidx.core.graphics.createBitmap
import com.naury.framekit.core.geometry.PointN
import com.naury.framekit.core.model.PixelSize
import com.naury.framekit.core.overlay.ImageOverlay
import com.naury.framekit.image.effect.ColorEffectRenderer
import com.naury.framekit.image.overlay.OverlayRenderer
import com.naury.framekit.motion.model.CameraMotion
import com.naury.framekit.motion.model.FitMode
import com.naury.framekit.motion.model.MotionKind
import com.naury.framekit.motion.model.MotionOverlay
import com.naury.framekit.motion.model.MotionProject
import com.naury.framekit.motion.model.MotionTransition
import com.naury.framekit.motion.model.OverlayAnimation
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 움직이는 이미지의 한 순간을 그린다. 미리보기와 저장이 같은 렌더러를 써서 보이는 그대로 저장된다.
 *
 * 순서: 원본(사진의 카메라 움직임·전환, 또는 움직이는 원본의 프레임) → 색 보정·필터 → 텍스트·스티커(움직임 포함).
 * 같은 렌더러를 여러 스레드에서 동시에 쓰지 않는다.
 */
public class MotionRenderer(
    private val colorRenderer: ColorEffectRenderer,
    private val overlayRenderer: OverlayRenderer = OverlayRenderer(),
) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val matrix = Matrix()

    /**
     * 결과 크기. 긴 변을 [maxLongEdge]에 맞추고, 영상 인코더를 위해 두 변 모두 짝수로 맞춘다. 움직이는 원본은 원본보다 크게 늘리지 않는다.
     */
    public fun outputSize(project: MotionProject, media: MotionMedia, maxLongEdge: Int): PixelSize {
        val aspect = project.aspect.widthOverHeight ?: media.sourceAspect
        var longEdge = maxLongEdge.coerceAtLeast(2)
        media.animation?.let { animation -> if (project.aspect.widthOverHeight == null) longEdge = longEdge.coerceAtMost(maxOf(animation.width, animation.height)) }
        val width = if (aspect >= 1) longEdge.toDouble() else longEdge * aspect
        val height = if (aspect >= 1) longEdge / aspect else longEdge.toDouble()
        return PixelSize(even(width), even(height))
    }

    /**
     * @param timeMs 결과 시각(밀리초).
     * @param reuse 크기가 같으면 이 비트맵에 덮어 그린다.
     * @param animateOverlays `false`면 텍스트·스티커를 움직임 없이 놓인 자리 그대로 그린다(위치를 고칠 때).
     * @return ARGB_8888 비트맵. [reuse]를 쓰지 못하면 새로 만든다.
     */
    public fun render(
        project: MotionProject,
        media: MotionMedia,
        timeMs: Long,
        width: Int,
        height: Int,
        reuse: Bitmap? = null,
        animateOverlays: Boolean = true,
    ): Bitmap {
        val bitmap = reuse?.takeIf { it.width == width && it.height == height && it.isMutable && !it.isRecycled } ?: createBitmap(width, height)
        val canvas = Canvas(bitmap)
        canvas.drawColor(if (project.fit == FitMode.FIT) project.backgroundArgb else BLACK)
        when (project.kind) {
            MotionKind.ANIMATION -> media.animation?.let { animation ->
                val frame = animation.frames[MotionTimeline.animationFrame(project, media, timeMs)]
                drawFitted(canvas, frame, project.fit, width, height, scale = 1f, offsetX = 0f, alpha = 255)
            }
            MotionKind.SLIDESHOW -> drawSlides(canvas, project, media, timeMs, width, height)
        }
        if (!project.colorSpec.isIdentity) colorRenderer.apply(bitmap, project.colorSpec, includeCanvasEffects = true)
        if (project.overlays.isNotEmpty()) {
            val overlays = project.overlays.map { if (animateOverlays) animate(it, timeMs) else it.overlay }
            overlayRenderer.draw(canvas, PixelSize(width, height), overlays, emptyList())
        }
        return bitmap
    }

    private fun drawSlides(canvas: Canvas, project: MotionProject, media: MotionMedia, timeMs: Long, width: Int, height: Int) {
        if (media.slideCount == 0) return
        val position = MotionTimeline.slideAt(project, media, timeMs)
        val current = position.index
        val p = position.transition
        if (p == null) {
            drawSlide(canvas, project, media, current, position.localMs, width, height, 255, 1f, 0f)
            return
        }
        val eased = smooth(p)
        // 앞 사진은 움직임이 끝난 모습으로 멈춰 있고, 새 사진은 처음부터 움직인다.
        val previousLocal = project.slideDurationMs
        when (project.transition) {
            MotionTransition.FADE -> {
                drawSlide(canvas, project, media, current - 1, previousLocal, width, height, 255, 1f, 0f)
                drawSlide(canvas, project, media, current, position.localMs, width, height, (eased * 255).roundToInt(), 1f, 0f)
            }
            MotionTransition.SLIDE -> {
                drawSlide(canvas, project, media, current - 1, previousLocal, width, height, 255, 1f, -eased * width)
                drawSlide(canvas, project, media, current, position.localMs, width, height, 255, 1f, (1 - eased) * width)
            }
            MotionTransition.ZOOM -> {
                drawSlide(canvas, project, media, current, position.localMs, width, height, 255, 1f, 0f)
                drawSlide(canvas, project, media, current - 1, previousLocal, width, height, ((1 - eased) * 255).roundToInt(), 1f + 0.3f * eased, 0f)
            }
            MotionTransition.NONE -> drawSlide(canvas, project, media, current, position.localMs, width, height, 255, 1f, 0f)
        }
    }

    // 사진 한 장을 카메라 움직임(확대·이동)과 함께 그린다.
    private fun drawSlide(canvas: Canvas, project: MotionProject, media: MotionMedia, index: Int, localMs: Long, width: Int, height: Int, alpha: Int, extraScale: Float, offsetX: Float) {
        val bitmap = media.slide(index.coerceIn(0, media.slideCount - 1))
        val q = (localMs.toFloat() / project.slideDurationMs).coerceIn(0f, 1f)
        var scale = 1f
        var panX = 0f
        when (MotionTimeline.cameraMotion(project, index)) {
            CameraMotion.ZOOM_IN -> scale = 1f + KEN_BURNS_ZOOM * q
            CameraMotion.ZOOM_OUT -> scale = 1f + KEN_BURNS_ZOOM * (1 - q)
            CameraMotion.PAN_LEFT, CameraMotion.PAN_RIGHT -> {
                scale = 1f + KEN_BURNS_PAN
                val margin = width * KEN_BURNS_PAN / 2
                val direction = if (MotionTimeline.cameraMotion(project, index) == CameraMotion.PAN_LEFT) 1f else -1f
                panX = direction * margin * (1 - 2 * q)
            }
            CameraMotion.NONE, CameraMotion.AUTO -> Unit
        }
        drawFitted(canvas, bitmap, project.fit, width, height, scale * extraScale, offsetX + panX, alpha)
    }

    private fun drawFitted(canvas: Canvas, bitmap: Bitmap, fit: FitMode, width: Int, height: Int, scale: Float, offsetX: Float, alpha: Int) {
        val sx = width.toFloat() / bitmap.width
        val sy = height.toFloat() / bitmap.height
        val base = if (fit == FitMode.FILL) maxOf(sx, sy) else minOf(sx, sy)
        val s = base * scale
        matrix.reset()
        matrix.postTranslate(-bitmap.width / 2f, -bitmap.height / 2f)
        matrix.postScale(s, s)
        matrix.postTranslate(width / 2f + offsetX, height / 2f)
        paint.alpha = alpha
        canvas.drawBitmap(bitmap, matrix, paint)
        paint.alpha = 255
    }

    private fun smooth(t: Float): Float = t * t * (3 - 2 * t)

    private fun even(value: Double): Int = (value.roundToInt() / 2 * 2).coerceAtLeast(2)

    public companion object {
        private const val BLACK = 0xFF000000.toInt()
        private const val KEN_BURNS_ZOOM = 0.15f
        private const val KEN_BURNS_PAN = 0.12f

        /** [timeMs](결과 시각, 밀리초)의 움직임을 반영한 텍스트·스티커. */
        public fun animate(item: MotionOverlay, timeMs: Long): ImageOverlay {
            val overlay = item.overlay
            val transform = overlay.transform
            val t = timeMs.toDouble()
            val next = when (item.animation) {
                OverlayAnimation.NONE -> return overlay
                OverlayAnimation.FADE_IN -> transform.copy(opacity = transform.opacity * clamp(t / 500))
                OverlayAnimation.POP -> {
                    val e = clamp(t / 450)
                    transform.copy(scale = transform.scale * maxOf(0.05, backOut(e)), opacity = transform.opacity * clamp(t / 150))
                }
                OverlayAnimation.SLIDE_UP -> {
                    val e = 1 - (1 - clamp(t / 500)).let { it * it * it }
                    transform.copy(center = PointN(transform.center.x, transform.center.y + 0.12 * (1 - e)), opacity = transform.opacity * clamp(t / 500))
                }
                OverlayAnimation.BOUNCE -> transform.copy(center = PointN(transform.center.x, transform.center.y - 0.025 * abs(sin(PI * t / 500))))
                OverlayAnimation.WIGGLE -> transform.copy(rotationDegrees = transform.rotationDegrees + 7 * sin(2 * PI * t / 700))
                OverlayAnimation.PULSE -> transform.copy(scale = transform.scale * (1 + 0.07 * sin(2 * PI * t / 900)))
            }
            return overlay.withTransform(next)
        }

        private fun clamp(v: Double): Double = v.coerceIn(0.0, 1.0)

        // 살짝 넘쳤다 돌아오는 곡선. 0에서 시작해 1에서 끝난다.
        private fun backOut(t: Double): Double {
            val c = 1.70158
            val x = t - 1
            return 1 + (c + 1) * x * x * x + c * x * x
        }
    }
}
