package com.naury.framekit.ui.motion

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import androidx.core.content.FileProvider
import androidx.core.graphics.createBitmap
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.naury.framekit.android.input.EditorInput
import com.naury.framekit.android.output.AppFileOutputStore
import com.naury.framekit.android.result.FrameKitResult
import com.naury.framekit.android.source.SessionSourceRegistry
import com.naury.framekit.core.model.MediaType
import com.naury.framekit.image.effect.CpuColorEffectRenderer
import com.naury.framekit.motion.codec.GifDecoder
import com.naury.framekit.motion.codec.GifEncoder
import com.naury.framekit.motion.export.MotionExporter
import com.naury.framekit.motion.export.MotionFormat
import com.naury.framekit.motion.export.MotionSize
import com.naury.framekit.motion.model.CameraMotion
import com.naury.framekit.motion.model.LoopMode
import com.naury.framekit.motion.model.MotionKind
import com.naury.framekit.motion.model.OverlayAnimation
import com.naury.framekit.motion.source.MotionSourceLoader
import com.naury.framekit.ui.motion.contract.MotionEditorRequest
import com.naury.framekit.ui.motion.contract.MotionTool
import com.naury.framekit.ui.motion.editor.MotionEditorUiState
import com.naury.framekit.ui.motion.editor.MotionEditorViewModel
import com.naury.framekit.ui.motion.editor.MotionAudioSource
import com.naury.framekit.core.video.AudioRole
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MotionEditorViewModelTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val store = AppFileOutputStore(context, availableBytes = { Long.MAX_VALUE })
    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        FileProvider::class.java.getDeclaredField("sCache").apply { isAccessible = true }.let {
            (it.get(null) as MutableMap<*, *>).clear()
        }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `photos open as a slideshow and edits are undoable steps`() {
        val viewModel = viewModel(EditorInput.Multiple(listOf(photo("a.png", Color.RED), photo("b.png", Color.BLUE))))
        val ready = ready(viewModel)
        assertThat(ready.displayed.kind).isEqualTo(MotionKind.SLIDESHOW)
        assertThat(ready.displayed.sourceIds).hasSize(2)

        viewModel.selectTool(MotionTool.MOTION)
        viewModel.commit { it.copy(cameraMotion = CameraMotion.ZOOM_IN) }
        viewModel.commit { it.copy(cameraMotion = CameraMotion.PAN_LEFT) }
        viewModel.applyTool()
        assertThat(ready(viewModel).displayed.cameraMotion).isEqualTo(CameraMotion.PAN_LEFT)
        viewModel.undo()
        assertThat(ready(viewModel).displayed.cameraMotion).isEqualTo(CameraMotion.ZOOM_IN)

        // 뒤로 가서 버리면 도구를 열기 전으로 돌아간다.
        viewModel.selectTool(MotionTool.LOOP)
        viewModel.commit { it.copy(loop = LoopMode.BOOMERANG) }
        viewModel.onBack()
        assertThat(ready(viewModel).showDraftDialog).isTrue()
        viewModel.discardDraftFromDialog()
        assertThat(ready(viewModel).displayed.loop).isEqualTo(LoopMode.FORWARD)
    }

    @Test
    fun `slides can be reordered and removed`() {
        val viewModel = viewModel(EditorInput.Multiple(listOf(photo("a.png", Color.RED), photo("b.png", Color.BLUE), photo("c.png", Color.GREEN))))
        val ids = ready(viewModel).displayed.sourceIds
        viewModel.selectSlide(0)
        viewModel.moveSlide(1)
        assertThat(ready(viewModel).displayed.sourceIds).containsExactly(ids[1], ids[0], ids[2]).inOrder()
        viewModel.removeSlide()
        assertThat(ready(viewModel).displayed.sourceIds).containsExactly(ids[1], ids[2]).inOrder()
    }

    @Test
    fun `text with animation is saved as an animated gif`() {
        val viewModel = viewModel(EditorInput.Multiple(listOf(photo("a.png", Color.RED), photo("b.png", Color.BLUE))))
        viewModel.selectTool(MotionTool.TEXT)
        viewModel.updateText("Hi")
        viewModel.setOverlayAnimation(OverlayAnimation.POP)
        viewModel.applyTool()
        assertThat(ready(viewModel).displayed.overlays.single().animation).isEqualTo(OverlayAnimation.POP)

        viewModel.save(MotionFormat.GIF, MotionSize.SMALL)
        val result = awaitResult(viewModel) as FrameKitResult.Success
        assertThat(result.output.mediaType).isEqualTo(MediaType.ANIMATED_IMAGE)
        assertThat(result.output.mimeType).isEqualTo("image/gif")
        assertThat(result.output.durationMs).isEqualTo(4000)
        val file = File(context.filesDir, "framekit/exports").listFiles()!!.single { it.extension == "gif" }
        val decoded = GifDecoder.decode(file.readBytes(), 1000, 500, 500_000_000)
        assertThat(decoded.frames.size).isGreaterThan(2)
    }

    @Test
    fun `an animated gif opens for editing with trim and speed`() {
        val gif = File(context.cacheDir, "anim.gif")
        gif.outputStream().use { out ->
            GifEncoder(out, 40, 30).use { encoder ->
                listOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW).forEach { color -> encoder.addFrame(createBitmap(40, 30).apply { eraseColor(color) }, 250) }
            }
        }
        val viewModel = viewModel(EditorInput.FileSource(gif.absolutePath))
        val ready = ready(viewModel)
        assertThat(ready.displayed.kind).isEqualTo(MotionKind.ANIMATION)
        assertThat(ready.sourceDurationMs).isEqualTo(1000)

        viewModel.selectTool(MotionTool.TIMING)
        viewModel.commit { it.copy(speed = 2.0, trimStartMs = 250) }
        viewModel.applyTool()
        viewModel.save(MotionFormat.WEBP, MotionSize.SMALL)
        val result = awaitResult(viewModel) as FrameKitResult.Success
        assertThat(result.output.mimeType).isEqualTo("image/webp")
        assertThat(result.output.durationMs).isEqualTo(375)
        assertThat(result.output.width).isEqualTo(40)
    }

    @Test
    fun `music and voice are separate tracks and edits survive process death`() {
        val savedState = SavedStateHandle()
        val input = EditorInput.Multiple(listOf(photo("a.png", Color.RED), photo("b.png", Color.BLUE)))
        val viewModel = viewModel(input, savedState)
        val song = File(context.cacheDir, "song.m4a").apply { writeText("x") }
        val voice = File(context.cacheDir, "voice.m4a").apply { writeText("x") }
        viewModel.addMusic(android.net.Uri.fromFile(song))
        viewModel.addRecordedVoice(voice)
        viewModel.selectAudio(ready(viewModel).displayed.audio.first { it.role == AudioRole.MUSIC }.id)
        viewModel.changeSelectedAudioVolume(50f)
        viewModel.finishDrag()
        // 고른 조각을 재생 위치(0)에서 나누면 너무 앞이라 나뉘지 않고, 1초에서는 둘로 나뉜다.
        viewModel.seekTo(1_000)
        viewModel.splitSelectedAudio()
        assertThat(ready(viewModel).displayed.audio.count { it.role == AudioRole.MUSIC }).isEqualTo(2)
        viewModel.undo()
        viewModel.commit { it.copy(cameraMotion = CameraMotion.ZOOM_OUT, loop = LoopMode.BOOMERANG) }
        val audio = ready(viewModel).displayed.audio
        assertThat(audio.map { it.role }).containsExactly(AudioRole.MUSIC, AudioRole.VOICE)
        assertThat(audio.first { it.role == AudioRole.MUSIC }.volume).isWithin(1e-9).of(0.5)

        // 같은 저장 상태로 새로 만들면(프로세스 종료 뒤 복원) 편집과 소리 트랙이 이어진다.
        val restored = viewModel(input, savedState)
        val project = ready(restored).displayed
        assertThat(project.cameraMotion).isEqualTo(CameraMotion.ZOOM_OUT)
        assertThat(project.loop).isEqualTo(LoopMode.BOOMERANG)
        assertThat(project.audio.map { it.role }).containsExactly(AudioRole.MUSIC, AudioRole.VOICE)
        assertThat(project.audio.first { it.role == AudioRole.MUSIC }.volume).isWithin(1e-9).of(0.5)
        assertThat(restored.uriOf(project.audio.first { it.role == AudioRole.VOICE }.sourceId)?.path).isEqualTo(voice.absolutePath)
    }

    private fun photo(name: String, color: Int): EditorInput {
        val file = File(context.cacheDir, name)
        val bitmap: Bitmap = createBitmap(200, 100).apply { eraseColor(color) }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return EditorInput.FileSource(file.absolutePath)
    }

    private fun viewModel(input: EditorInput, savedState: SavedStateHandle = SavedStateHandle()): MotionEditorViewModel {
        val registry = SessionSourceRegistry(context)
        return MotionEditorViewModel(
            request = MotionEditorRequest(input),
            savedState = savedState,
            registry = registry,
            loader = MotionSourceLoader(registry, context.contentResolver),
            exporter = MotionExporter(store) { CpuColorEffectRenderer },
            colorRenderer = CpuColorEffectRenderer,
            ioDispatcher = dispatcher,
            renderDispatcher = dispatcher,
            memoryBudgetBytes = 512L * 1024 * 1024,
            describeAudio = { MotionAudioSource("clip", 5_000) },
        )
    }

    private fun ready(viewModel: MotionEditorViewModel) = viewModel.state.value as MotionEditorUiState.Ready

    private fun awaitResult(viewModel: MotionEditorViewModel): FrameKitResult = runBlocking {
        withTimeout(30_000) { viewModel.result.filterNotNull().first() }
    }
}
