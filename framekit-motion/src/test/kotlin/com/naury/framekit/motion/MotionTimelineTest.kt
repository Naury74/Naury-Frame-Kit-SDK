package com.naury.framekit.motion

import android.graphics.Bitmap
import androidx.core.graphics.createBitmap
import com.google.common.truth.Truth.assertThat
import com.naury.framekit.core.overlay.ImageOverlay
import com.naury.framekit.motion.codec.AnimatedFrames
import com.naury.framekit.motion.model.CameraMotion
import com.naury.framekit.motion.model.LoopMode
import com.naury.framekit.motion.model.MotionKind
import com.naury.framekit.motion.model.MotionOverlay
import com.naury.framekit.motion.model.MotionProject
import com.naury.framekit.motion.model.MotionTransition
import com.naury.framekit.motion.model.OverlayAnimation
import com.naury.framekit.motion.render.MotionMedia
import com.naury.framekit.motion.render.MotionRenderer
import com.naury.framekit.motion.render.MotionTimeline
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MotionTimelineTest {

    private val slides = MotionMedia(MotionKind.SLIDESHOW, listOf(1.5, 1.0, 0.75), { createBitmap(4, 4) }, null)

    @Test
    fun `slideshow duration transitions and boomerang`() {
        val project = MotionProject(MotionKind.SLIDESHOW, listOf("a", "b", "c"), slideDurationMs = 1000, transitionMs = 400)
        assertThat(MotionTimeline.durationMs(project, slides)).isEqualTo(3000)
        assertThat(MotionTimeline.slideAt(project, slides, 1200).transition).isWithin(0.001f).of(0.5f)
        assertThat(MotionTimeline.slideAt(project, slides, 1500).transition).isNull()
        val boomerang = project.copy(loop = LoopMode.BOOMERANG)
        assertThat(MotionTimeline.durationMs(boomerang, slides)).isEqualTo(6000)
        assertThat(MotionTimeline.slideAt(boomerang, slides, 5500).index).isEqualTo(0)
    }

    @Test
    fun `still slides without motion merge into one frame each`() {
        val project = MotionProject(
            MotionKind.SLIDESHOW, listOf("a", "b", "c"), slideDurationMs = 1000,
            transition = MotionTransition.NONE, cameraMotion = CameraMotion.NONE,
        )
        val frames = MotionTimeline.outputFrames(project, slides, fps = 10)
        assertThat(frames.map { it.durationMs }).containsExactly(1000L, 1000L, 1000L).inOrder()

        // 반복해서 움직이는 텍스트가 있으면 합치지 않는다.
        val text = MotionOverlay(ImageOverlay.Text("t", "hi"), OverlayAnimation.PULSE)
        assertThat(MotionTimeline.outputFrames(project.copy(overlays = listOf(text)), slides, fps = 10)).hasSize(30)
    }

    @Test
    fun `animation trim speed and frame lookup`() {
        val frames = List(4) { createBitmap(2, 2) }
        val media = MotionMedia(MotionKind.ANIMATION, emptyList(), { error("no slides") }, AnimatedFrames(frames, listOf(100L, 100L, 200L, 100L), 0))
        val project = MotionProject(MotionKind.ANIMATION, listOf("a"), trimStartMs = 100, trimEndMs = 400, speed = 2.0)
        assertThat(MotionTimeline.durationMs(project, media)).isEqualTo(150)
        assertThat(MotionTimeline.animationFrame(project, media, 0)).isEqualTo(1)
        assertThat(MotionTimeline.animationFrame(project, media, 60)).isEqualTo(2)
        // 같은 원본 프레임이 이어지는 구간은 합쳐진다.
        val merged = MotionTimeline.outputFrames(project.copy(speed = 1.0), media, fps = 20)
        assertThat(merged.map { it.durationMs }.sum()).isEqualTo(300L)
        assertThat(merged).hasSize(2)
    }

    @Test
    fun `entrance animations settle and pop starts invisible`() {
        val overlay = ImageOverlay.Text("t", "hi")
        val pop = MotionOverlay(overlay, OverlayAnimation.POP)
        assertThat(MotionRenderer.animate(pop, 0).transform.opacity).isEqualTo(0.0)
        assertThat(MotionRenderer.animate(pop, 1000).transform).isEqualTo(overlay.transform)
        val fade = MotionOverlay(overlay, OverlayAnimation.FADE_IN)
        assertThat(MotionRenderer.animate(fade, 250).transform.opacity).isWithin(1e-6).of(0.5)
    }

    @Suppress("unused")
    private fun Bitmap.unused() = Unit
}
