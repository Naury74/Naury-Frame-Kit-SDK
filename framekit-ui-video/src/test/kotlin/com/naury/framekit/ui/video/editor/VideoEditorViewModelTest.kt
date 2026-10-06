package com.naury.framekit.ui.video.editor

import com.naury.framekit.core.overlay.EmojiCatalog
import com.naury.framekit.video.source.AudioSourceInfo
import com.naury.framekit.core.video.TimelineTimeMapper
import com.naury.framekit.android.session.EditorSessionStore
import java.util.concurrent.Executor
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
import com.naury.framekit.core.overlay.MaskShape
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
    private val sessions = EditorSessionStore(context, background = Executor { it.run() })
    private val musicDurationUs = 2_000_000L

    // 추가하는 클립의 길이. 키는 SourceLocation 문자열이다.
    private val extraDurations = mutableMapOf<String, Long>()

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
        viewModel.cancelTool()
        assertThat(ready(viewModel).clip.speed).isEqualTo(1.0)
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
        viewModel.applyTool()

        val clamped = ready(viewModel).displayed.timeline.privacyMasks.single()
        assertThat(clamped.range.endExclusiveUs).isEqualTo(ready(viewModel).durationUs)
    }

    @Test
    fun `masks move and resize inside the frame as one undo step each`() {
        val viewModel = viewModel(durationUs = 10_000_000)
        viewModel.selectTool(VideoTool.PRIVACY)
        viewModel.beginMask(0.2, 0.2)
        viewModel.extendMask(0.4, 0.4)
        viewModel.finishMask()
        val id = ready(viewModel).selectedMaskId!!
        fun rect() = (ready(viewModel).displayed.timeline.privacyMasks.single().mask.shape as MaskShape.Rectangle).rect

        viewModel.beginMaskEdit(id, resize = false)
        viewModel.dragMaskEdit(0.9, 0.1)
        viewModel.finishMaskEdit()
        assertThat(rect().right).isWithin(1e-9).of(1.0)
        assertThat(rect().width).isWithin(1e-9).of(0.2)

        viewModel.beginMaskEdit(id, resize = true)
        viewModel.dragMaskEdit(-1.0, 0.3)
        viewModel.finishMaskEdit()
        assertThat(rect().width).isWithin(1e-9).of(0.01)
        assertThat(rect().height).isWithin(1e-9).of(0.5)

        val before = ready(viewModel).transaction.history
        viewModel.beginMaskEdit(id, resize = false)
        viewModel.dragMaskEdit(0.0, 0.0)
        viewModel.finishMaskEdit()
        assertThat(ready(viewModel).transaction.history).isEqualTo(before)

        viewModel.undo()
        assertThat(rect().width).isWithin(1e-9).of(0.2)
    }

    @Test
    fun `scrubbing pauses playback and resumes it only if it was playing`() {
        val viewModel = viewModel(durationUs = 10_000_000)
        engine.play()

        viewModel.seekTo(3_000_000, scrubbing = true)
        assertThat(engine.state.value.isPlaying).isFalse()
        viewModel.finishScrub()
        assertThat(engine.state.value.isPlaying).isTrue()
        assertThat(engine.state.value.positionUs).isEqualTo(3_000_000)

        engine.pause()
        viewModel.seekTo(5_000_000, scrubbing = true)
        viewModel.finishScrub()
        assertThat(engine.state.value.isPlaying).isFalse()
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
    fun `save applies an open draft and exports it`() {
        val viewModel = viewModel(durationUs = 10_000_000)
        viewModel.selectTool(VideoTool.TRIM)
        viewModel.beginTrim(TrimEdge.END)
        viewModel.dragTrim(4_000_000)
        viewModel.save()
        assertThat(ready(viewModel).showApplyHint).isFalse()
        dispatcher.scheduler.advanceUntilIdle()

        assertThat(viewModel.result.value).isInstanceOf(FrameKitResult.Success::class.java)
        assertThat(exported.single().timeline.videoClips.single().sourceRange).isEqualTo(TimeRangeUs(0, 4_000_000))
    }

    @Test
    fun `committed edits come back after process death`() {
        val savedState = SavedStateHandle()
        val first = viewModel(durationUs = 10_000_000, savedState = savedState, sessions = sessions)
        first.selectTool(VideoTool.SPEED)
        first.selectSpeed(2.0)
        first.applyTool()
        dispatcher.scheduler.advanceUntilIdle()

        // 같은 SavedStateHandle로 새 ViewModel을 만들면 프로세스가 다시 시작된 것과 같다.
        val second = viewModel(durationUs = 10_000_000, savedState = savedState, sessions = sessions)

        assertThat(ready(second).clip.speed).isEqualTo(2.0)
        assertThat(ready(second).notice).isEqualTo(VideoNotice.RESTORED)
        assertThat(ready(second).isDirty).isTrue()
        assertThat(ready(second).transaction.history.canUndo).isFalse()
    }

    @Test
    fun `a different video behind the same reference is not restored`() {
        val savedState = SavedStateHandle()
        val first = viewModel(durationUs = 10_000_000, savedState = savedState, sessions = sessions)
        first.selectTool(VideoTool.SPEED)
        first.selectSpeed(2.0)
        first.applyTool()
        dispatcher.scheduler.advanceUntilIdle()

        val second = viewModel(durationUs = 12_000_000, savedState = savedState, sessions = sessions)

        assertThat(ready(second).clip.speed).isEqualTo(1.0)
        assertThat(ready(second).notice).isNull()
    }

    @Test
    fun `clips are added split moved and deleted one undo step each`() {
        val viewModel = viewModel(durationUs = 6_000_000, config = VideoEditorConfig(maxClipCount = 4))
        val second = fileUri("video2")
        extraDurations[locationOf(second)] = 3_000_000

        viewModel.addClips(listOf(second))
        dispatcher.scheduler.advanceUntilIdle()
        assertThat(ready(viewModel).clips).hasSize(2)
        assertThat(ready(viewModel).durationUs).isEqualTo(9_000_000)

        engine.seekTo(2_000_000)
        viewModel.splitAtPlayhead()
        assertThat(ready(viewModel).clips.map { it.outputDurationUs }).containsExactly(2_000_000L, 4_000_000L, 3_000_000L).inOrder()

        viewModel.moveSelectedClip(1)
        val moved = ready(viewModel).clips
        assertThat(moved.map { it.outputDurationUs }).containsExactly(2_000_000L, 3_000_000L, 4_000_000L).inOrder()
        assertThat(ready(viewModel).clip.id).isEqualTo(moved[2].id)

        viewModel.deleteSelectedClip()
        assertThat(ready(viewModel).clips).hasSize(2)
        assertThat(ready(viewModel).transaction.history.past).hasSize(4)

        viewModel.undo()
        assertThat(ready(viewModel).clips).hasSize(3)
    }

    @Test
    fun `adding clips stops at the clip count and length limits`() {
        val viewModel = viewModel(durationUs = 6_000_000, config = VideoEditorConfig(maxClipCount = 2, maxTimelineDurationUs = 8_000_000))
        val uris = (1..3).map { fileUri("video$it") }
        uris.forEach { extraDurations[locationOf(it)] = 5_000_000 }

        viewModel.addClips(uris)
        dispatcher.scheduler.advanceUntilIdle()

        // 두 번째 클립은 남은 2초만큼만 붙고, 나머지는 클립 수 제한에 걸린다.
        assertThat(ready(viewModel).clips.map { it.outputDurationUs }).containsExactly(6_000_000L, 2_000_000L).inOrder()
        assertThat(ready(viewModel).notice).isEqualTo(VideoNotice.CLIP_LIMIT)
    }

    @Test
    fun `single clip editors cannot split or add`() {
        val viewModel = viewModel(durationUs = 6_000_000, config = VideoEditorConfig(maxClipCount = 1))
        engine.seekTo(3_000_000)

        viewModel.splitAtPlayhead()
        viewModel.addClips(listOf(fileUri("video9")))
        dispatcher.scheduler.advanceUntilIdle()

        assertThat(ready(viewModel).clips).hasSize(1)
        assertThat(viewModel.multiClip).isFalse()
    }

    @Test
    fun `music starts at the playhead and is removed when the video ends before it`() {
        val viewModel = viewModel(durationUs = 6_000_000)
        engine.seekTo(4_000_000)

        viewModel.selectTool(VideoTool.AUDIO)
        viewModel.addMusic(fileUri("audio1"))
        dispatcher.scheduler.advanceUntilIdle()
        val music = ready(viewModel).displayed.timeline.audioClips.single()
        assertThat(music.timelineStartUs).isEqualTo(4_000_000)
        assertThat(music.sourceRange).isEqualTo(TimeRangeUs(0, musicDurationUs))
        assertThat(ready(viewModel).music.values.single().name).isEqualTo("song.m4a")

        viewModel.setMusicLoop(true)
        viewModel.closeTool()
        viewModel.selectTool(VideoTool.TRIM)
        viewModel.beginTrim(TrimEdge.END)
        viewModel.dragTrim(3_000_000)
        viewModel.applyTool()

        assertThat(ready(viewModel).displayed.timeline.audioClips).isEmpty()
    }

    @Test
    fun `stickers get three seconds from the playhead and blank text is dropped`() {
        val viewModel = viewModel(durationUs = 10_000_000)
        engine.seekTo(8_000_000)

        viewModel.selectTool(VideoTool.STICKER)
        viewModel.addSticker(EmojiCatalog.assetId("😀"))
        val sticker = ready(viewModel).displayed.timeline.overlays.single()
        assertThat(sticker.range).isEqualTo(TimeRangeUs(7_000_000, 10_000_000))
        viewModel.closeTool()

        viewModel.selectTool(VideoTool.TEXT)
        viewModel.applyTool()
        assertThat(ready(viewModel).displayed.timeline.overlays).hasSize(1)

        viewModel.selectTool(VideoTool.TEXT)
        viewModel.updateText("안녕")
        viewModel.applyTool()
        assertThat(ready(viewModel).displayed.timeline.overlays).hasSize(2)
        assertThat(ready(viewModel).transaction.history.past).hasSize(2)
    }

    @Test
    fun `added clips and music come back after process death`() {
        val savedState = SavedStateHandle()
        val config = VideoEditorConfig(maxClipCount = 3)
        val second = fileUri("video2")
        extraDurations[locationOf(second)] = 3_000_000
        val first = viewModel(durationUs = 6_000_000, config = config, savedState = savedState, sessions = sessions)
        first.addClips(listOf(second))
        dispatcher.scheduler.advanceUntilIdle()
        first.addMusic(fileUri("audio1"))
        dispatcher.scheduler.advanceUntilIdle()

        val restored = viewModel(durationUs = 6_000_000, config = config, savedState = savedState, sessions = sessions)

        assertThat(ready(restored).clips).hasSize(2)
        assertThat(ready(restored).durationUs).isEqualTo(9_000_000)
        assertThat(ready(restored).displayed.timeline.audioClips).hasSize(1)
        assertThat(ready(restored).notice).isEqualTo(VideoNotice.RESTORED)
    }

    private fun fileUri(name: String): Uri = Uri.fromFile(File(context.cacheDir, "$name.mp4").apply { writeBytes(ByteArray(16)) })

    private fun locationOf(uri: Uri) = com.naury.framekit.android.source.SourceLocation.Content(uri).toString()

    private fun viewModel(
        durationUs: Long,
        config: VideoEditorConfig = VideoEditorConfig(),
        savedState: SavedStateHandle = SavedStateHandle(),
        sessions: EditorSessionStore? = null,
    ): VideoEditorViewModel {
        val registry = SessionSourceRegistry(context)
        val viewModel = VideoEditorViewModel(
            request = VideoEditorRequest(EditorInput.FileSource(sourceFile.absolutePath), config = config),
            savedState = savedState,
            registry = registry,
            readSource = { id ->
                VideoSourceInfo(
                    metadata = SourceMetadata(id, MediaType.VIDEO, "video/mp4", PixelSize(640, 360), extraDurations[registry.location(id).toString()] ?: durationUs, hasAudio = true),
                    encodedSize = PixelSize(640, 360),
                    rotationDegrees = 0,
                    videoMimeType = "video/avc",
                    audioMimeType = "audio/mp4a-latm",
                    frameRate = 30f,
                    isHdr = false,
                )
            },
            exporter = { project, _, _, _, onProgress ->
                onProgress(VideoExportProgress.Encoding(50))
                exported += project
                EditedMedia(Uri.EMPTY, MediaType.VIDEO, 640, 360, TimelineTimeMapper.durationUs(project.timeline) / 1000, "video/mp4", 1L)
            },
            readAudio = { id -> AudioSourceInfo(SourceMetadata(id, MediaType.AUDIO, "audio/mp4a-latm", PixelSize(0, 0), musicDurationUs, hasAudio = true), "audio/mp4a-latm") },
            describe = { "song.m4a" },
            previewEngineFactory = { engine },
            frames = { _, _, _, _ -> null },
            ioDispatcher = dispatcher,
            sessionStore = sessions,
            snapshotDebounceMillis = 0,
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
