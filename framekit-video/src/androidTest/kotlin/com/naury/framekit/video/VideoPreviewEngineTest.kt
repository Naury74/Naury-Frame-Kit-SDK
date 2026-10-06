package com.naury.framekit.video

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.naury.framekit.android.input.EditorInput
import com.naury.framekit.android.source.SessionSourceRegistry
import com.naury.framekit.core.model.ProjectId
import com.naury.framekit.core.video.TimeRangeUs
import com.naury.framekit.core.video.Timeline
import com.naury.framekit.core.video.VideoClip
import com.naury.framekit.core.video.VideoProject
import com.naury.framekit.video.preview.Media3VideoPreviewEngine
import com.naury.framekit.video.source.VideoMetadataReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** 미리보기 플레이어가 trim·배속된 계획을 prepare하고 출력 길이를 보고하는지 확인한다. */
@RunWith(AndroidJUnit4::class)
class VideoPreviewEngineTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun trimmedPlanBecomesReadyWithItsOutputDuration() = runBlocking {
        val file = File(context.cacheDir, "fixture.mp4")
        if (!file.exists()) TestVideoFactory.create(file)
        val registry = SessionSourceRegistry(context)
        val id = registry.register(EditorInput.FileSource(file.absolutePath))
        val info = VideoMetadataReader(context, registry).read(id)
        val clip = VideoClip("c", id, TimeRangeUs(1_000_000, 4_000_000), speed = 2.0)
        val plan = VideoPlanFactory.create(VideoProject(ProjectId("p"), Timeline(listOf(clip))), mapOf(id to info), mapOf(id to registry.location(id)), 360)
        val scope = MainScope()

        val state = withContext(Dispatchers.Main) {
            val engine = Media3VideoPreviewEngine(context, scope)
            try {
                engine.setPlan(plan, 0)
                withTimeout(10_000) { engine.state.first { it.isReady || it.error != null } }
            } finally {
                engine.release()
                scope.cancel()
            }
        }

        assertThat(state.error).isNull()
        assertThat(state.durationUs).isIn(1_400_000L..1_600_000L)
    }
}
