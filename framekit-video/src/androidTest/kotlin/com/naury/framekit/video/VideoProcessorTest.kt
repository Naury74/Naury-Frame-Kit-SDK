package com.naury.framekit.video

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.naury.framekit.android.export.ExportState
import com.naury.framekit.android.input.EditorInput
import com.naury.framekit.android.result.FrameKitException
import com.naury.framekit.android.result.FrameKitResult
import com.naury.framekit.core.video.AudioClip
import com.naury.framekit.core.video.TimeRangeUs
import com.naury.framekit.video.headless.VideoProcessor
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

/** UI 없이 열기·편집·내보내기까지 한 번에 동작하는지 확인한다. */
@RunWith(AndroidJUnit4::class)
class VideoProcessorTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun headlessExportWithSpeedAndMusicReportsTheOutput() = runBlocking<Unit> {
        val video = File(context.cacheDir, "fixture.mp4").also { if (!it.exists()) TestVideoFactory.create(it) }
        val song = File(context.cacheDir, "music.m4a").also { if (!it.exists()) TestVideoFactory.createAudio(it, 2_000_000) }
        val processor = VideoProcessor(context)
        val source = processor.open(EditorInput.FileSource(video.absolutePath))
        val music = processor.openAudio(EditorInput.FileSource(song.absolutePath))
        val base = processor.newProject(source)
        val project = base.copy(
            timeline = base.timeline.copy(
                videoClips = base.timeline.videoClips.map { it.copy(sourceRange = TimeRangeUs(0, 4_000_000), speed = 2.0) },
                audioClips = listOf(AudioClip("m", music.metadata.id, TimeRangeUs(0, 1_000_000), timelineStartUs = 0, loop = true)),
            ),
        )
        val scope = MainScope()
        try {
            val handle = processor.startExport(project, listOf(source), scope = scope, music = listOf(music))
            val result = handle.awaitResult() as FrameKitResult.Success

            assertThat(abs(result.output.durationMs!! - 2_000)).isAtMost(40)
            assertThat(handle.state.value).isInstanceOf(ExportState.Completed::class.java)
            processor.deleteOutput(result.output.uri)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun invalidProjectsFailBeforeStarting() = runBlocking<Unit> {
        val video = File(context.cacheDir, "fixture.mp4").also { if (!it.exists()) TestVideoFactory.create(it) }
        val processor = VideoProcessor(context)
        val source = processor.open(EditorInput.FileSource(video.absolutePath))
        val base = processor.newProject(source)
        val tooShort = base.copy(timeline = base.timeline.copy(videoClips = base.timeline.videoClips.map { it.copy(sourceRange = TimeRangeUs(0, 100_000)) }))
        val scope = MainScope()

        assertThrows(FrameKitException::class.java) { processor.startExport(tooShort, listOf(source), scope = scope) }
        scope.cancel()
    }
}
