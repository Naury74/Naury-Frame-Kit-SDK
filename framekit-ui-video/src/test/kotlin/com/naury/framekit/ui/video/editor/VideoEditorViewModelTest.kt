package com.naury.framekit.ui.video.editor

import android.app.Application
import android.net.Uri
import android.view.SurfaceHolder
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.naury.framekit.android.input.EditorInput
import com.naury.framekit.android.result.EditedMedia
import com.naury.framekit.android.result.FrameKitResult
import com.naury.framekit.android.source.SessionSourceRegistry
import com.naury.framekit.core.model.MediaType
import com.naury.framekit.core.model.PixelSize
import com.naury.framekit.core.model.SourceMetadata
import com.naury.framekit.core.video.TimeRangeUs
import com.naury.framekit.core.video.Timeline
import com.naury.framekit.core.video.VideoProject
import com.naury.framekit.ui.video.contract.VideoEditorConfig
import com.naury.framekit.ui.video.contract.VideoEditorRequest
import com.naury.framekit.ui.video.contract.VideoTool
import com.naury.framekit.video.VideoRenderPlan
import com.naury.framekit.video.export.VideoExportProgress
import com.naury.framekit.video.preview.VideoPlaybackState
import com.naury.framekit.video.preview.VideoPreviewEngine
import com.naury.framekit.video.source.VideoSourceInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class VideoEditorViewModelTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val sourceFile = File(context.cacheDir, "clip.mp4").apply { writeBytes(ByteArray(16)) }
    private val dispatcher = StandardTestDispatcher()
    private val engine = FakeEngine()
    private val exported = mutableListOf<VideoProject>()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `long source opens trimmed to the maximum length`() {
        val viewModel = viewModel(durationUs = 20_000_000, config = VideoEditorConfig(maxTimelineDurationUs = 10_000_000))

        assertThat(ready(viewModel).clip.sourceRange).isEqualTo(TimeRangeUs(0, 10_000_000))
        assertThat(ready(viewModel).durationUs).isEqualTo(10_000_000)
    }

    @Test
    fun `source shorter than the minimum clip fails to load`() {
        val viewModel = viewModel(durationUs = 500_000)

        assertThat(viewModel.state.value).isInstanceOf(VideoEditorUiState.LoadFailed::class.java)
    }

    @Test
    fun `trim keeps the minimum length and is one undo step`() {
        val viewModel = viewModel(durationUs = 10_000_000)

        viewModel.selectTool(VideoTool.TRIM)
        viewModel.beginTrim(TrimEdge.START)
        listOf(1_000_000L, 4_000_000L, 9_800_000L).forEach(viewModel::dragTrim)
        viewModel.endTrim()
        viewModel.beginTrim(TrimEdge.END)
        viewModel.dragTrim(9_500_000)
        viewModel.endTrim()
        viewModel.applyTool()

        val history = ready(viewModel).transaction.history
        assertThat(history.past).hasSize(1)
        // 시작 손잡이는 끝에서 최소 길이(1초)보다 가까이 갈 수 없다.
        assertThat(history.current.timeline.videoClips.single().sourceRange).isEqualTo(TimeRangeUs(9_000_000, 10_000_000))
    }

    @Test
    fun `cancelled trim leaves the project unchanged`() {
        val viewModel = viewModel(durationUs = 10_000_000)

        viewModel.selectTool(VideoTool.TRIM)
        viewModel.beginTrim(TrimEdge.END)
        viewModel.dragTrim(5_000_000)
        viewModel.cancelTool()

        assertThat(ready(viewModel).isDirty).isFalse()
    }

    @Test
    fun `speed that breaks the length limits is rejected with a notice`() {
        val viewModel = viewModel(durationUs = 8_000_000, config = VideoEditorConfig(maxTimelineDurationUs = 10_000_000))

        viewModel.selectTool(VideoTool.SPEED)
        viewModel.selectSpeed(0.5)
        assertThat(ready(viewModel).clip.speed).isEqualTo(1.0)
        assertThat(ready(viewModel).notice).isEqualTo(VideoNotice.SPEED_TOO_LONG)

        viewModel.selectSpeed(2.0)
        assertThat(ready(viewModel).clip.speed).isEqualTo(2.0)
        assertThat(ready(viewModel).durationUs).isEqualTo(4_000_000)
    }

    @Test
    fun `mask drawn at the playhead covers three seconds and is clamped when the video gets shorter`() {
        val viewModel = viewModel(durationUs = 10_000_000)
        engine.seekTo(6_000_000)

        viewModel.selectTool(VideoTool.PRIVACY)
        viewModel.beginMask(0.2, 0.2)
        viewModel.extendMask(0.6, 0.5)
        viewModel.finishMask()
        val mask = ready(viewModel).displayed.timeline.privacyMasks.single()
        assertThat(mask.range).isEqualTo(TimeRangeUs(6_000_000, 9_000_000))
        viewModel.closeTool()

        viewModel.selectTool(VideoTool.SPEED)
        viewModel.selectSpeed(1.5)

        val clamped = ready(viewModel).displayed.timeline.privacyMasks.single()
        assertThat(clamped.range.endExclusiveUs).isEqualTo(ready(viewModel).durationUs)
    }

    @Test
    fun `a tap does not create a mask and the limit is enforced`() {
        val viewModel = viewModel(durationUs = 10_000_000)
        viewModel.selectTool(VideoTool.PRIVACY)

        viewModel.beginMask(0.5, 0.5)
        viewModel.finishMask()
        assertThat(ready(viewModel).displayed.timeline.privacyMasks).isEmpty()
        assertThat(ready(viewModel).transaction.isActive).isFalse()

        repeat(Timeline.MAX_PRIVACY_MASKS + 1) {
            viewModel.beginMask(0.1, 0.1)
            viewModel.extendMask(0.3, 0.3)
            viewModel.finishMask()
        }
        assertThat(ready(viewModel).displayed.timeline.privacyMasks).hasSize(Timeline.MAX_PRIVACY_MASKS)
        assertThat(ready(viewModel).notice).isEqualTo(VideoNotice.MASK_LIMIT)
    }

    @Test
    fun `preview is rebuilt once after a burst of slider changes`() {
        val viewModel = viewModel(durationUs = 10_000_000)
        val before = engine.plans.size

        viewModel.selectTool(VideoTool.ADJUST)
        repeat(10) { viewModel.changeAdjustment(it * 5f) }
        viewModel.finishGesture()
        dispatcher.scheduler.advanceUntilIdle()

        assertThat(engine.plans.size - before).isEqualTo(1)
        assertThat(engine.plans.last().project.timeline.videoClips.single().effects.adjustments)
            .isEqualTo(ready(viewModel).clip.effects.adjustments)
    }

    @Test
    fun `save exports the committed project and asks to apply an open draft first`() {
        val viewModel = viewModel(durationUs = 10_000_000)
        viewModel.selectTool(VideoTool.TRIM)
        viewModel.beginTrim(TrimEdge.END)
        viewModel.dragTrim(4_000_000)
        viewModel.save()
        assertThat(ready(viewModel).showApplyHint).isTrue()

        viewModel.applyTool()
        viewModel.save()
        dispatcher.scheduler.advanceUntilIdle()

        assertThat(viewModel.result.value).isInstanceOf(FrameKitResult.Success::class.java)
        assertThat(exported.single().timeline.videoClips.single().sourceRange).isEqualTo(TimeRangeUs(0, 4_000_000))
    }

    private fun viewModel(durationUs: Long, config: VideoEditorConfig = VideoEditorConfig()): VideoEditorViewModel {
        val registry = SessionSourceRegistry(context)
        val viewModel = VideoEditorViewModel(
            request = VideoEditorRequest(EditorInput.FileSource(sourceFile.absolutePath), config = config),
            savedState = SavedStateHandle(),
            registry = registry,
            readSource = { id ->
                VideoSourceInfo(
                    metadata = SourceMetadata(id, MediaType.VIDEO, "video/mp4", PixelSize(640, 360), durationUs, hasAudio = true),
                    encodedSize = PixelSize(640, 360),
                    rotationDegrees = 0,
                    videoMimeType = "video/avc",
                    audioMimeType = "audio/mp4a-latm",
                    frameRate = 30f,
                    isHdr = false,
                )
            },
            exporter = { project, _, _, onProgress ->
                onProgress(VideoExportProgress.Encoding(50))
                exported += project
                EditedMedia(Uri.EMPTY, MediaType.VIDEO, 640, 360, project.timeline.videoClips.single().sourceRange.durationUs / 1000, "video/mp4", 1L)
            },
            previewEngineFactory = { engine },
            frames = { _, _, _, _ -> null },
            ioDispatcher = dispatcher,
        )
        dispatcher.scheduler.advanceUntilIdle()
        return viewModel
    }

    private fun ready(viewModel: VideoEditorViewModel) = viewModel.state.value as VideoEditorUiState.Ready

    private class FakeEngine : VideoPreviewEngine {
        private val _state = MutableStateFlow(VideoPlaybackState())
        override val state: StateFlow<VideoPlaybackState> = _state
        val plans = mutableListOf<VideoRenderPlan>()

        override fun setPlan(plan: VideoRenderPlan, positionUs: Long) {
            plans += plan
            _state.value = _state.value.copy(positionUs = positionUs, planRevision = plan.projectRevision)
        }

        override fun play() {
            _state.value = _state.value.copy(isPlaying = true)
        }

        override fun pause() {
            _state.value = _state.value.copy(isPlaying = false)
        }

        override fun seekTo(positionUs: Long) {
            _state.value = _state.value.copy(positionUs = positionUs)
        }

        override fun setScrubbing(enabled: Boolean) = Unit
        override fun attachSurface(holder: SurfaceHolder) = Unit
        override fun detachSurface(holder: SurfaceHolder) = Unit
        override fun release() = Unit
    }
}
