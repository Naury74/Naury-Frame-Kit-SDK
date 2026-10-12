package com.naury.framekit.ui.audioeditor.editor

import android.app.Application
import androidx.core.content.FileProvider
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.naury.framekit.android.input.EditorInput
import com.naury.framekit.android.input.MediaKind
import com.naury.framekit.android.output.AppFileOutputStore
import com.naury.framekit.android.result.FrameKitResult
import com.naury.framekit.android.source.SessionSourceRegistry
import com.naury.framekit.core.model.MediaType
import com.naury.framekit.core.video.AudioRole
import com.naury.framekit.ui.audioeditor.contract.AudioEditorConfig
import com.naury.framekit.ui.audioeditor.contract.AudioEditorRequest
import com.naury.framekit.video.media3.AudioMixTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
class AudioEditorViewModelTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val dispatcher = StandardTestDispatcher()
    private val store = AppFileOutputStore(context, availableBytes = { Long.MAX_VALUE })
    private val mixed = mutableListOf<Pair<Long, List<AudioMixTrack>>>()

    // 파일 이름별 길이(µs). 없는 이름은 열 수 없는 파일이다.
    private val durations = mutableMapOf<String, Long>()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        // FileProvider는 경로 규칙을 정적으로 캐시하는데, Robolectric은 테스트마다 데이터 폴더가 달라 이전 경로가 남는다.
        FileProvider::class.java.getDeclaredField("sCache").apply { isAccessible = true }.let { (it.get(null) as MutableMap<*, *>).clear() }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `files open joined on the voice lane`() {
        val viewModel = viewModel(EditorInput.Multiple(listOf(file("a.m4a", 2_000_000), file("b.m4a", 3_000_000))))

        val project = ready(viewModel).project
        assertThat(project.mainClips).hasSize(2)
        assertThat(project.durationUs).isEqualTo(5_000_000)
        assertThat(project.clips.all { it.role == AudioRole.VOICE }).isTrue()
    }

    @Test
    fun `unreadable file fails to load`() {
        val viewModel = viewModel(EditorInput.FileSource(File(context.cacheDir, "broken.m4a").apply { writeBytes(ByteArray(4)) }.absolutePath))

        assertThat(viewModel.state.value).isInstanceOf(AudioEditorUiState.LoadFailed::class.java)
    }

    @Test
    fun `capture starts on the recorder and a recording opens the editor`() {
        val viewModel = viewModel(EditorInput.Capture(MediaKind.AUDIO))
        assertThat(viewModel.state.value).isEqualTo(AudioEditorUiState.Recording)

        viewModel.onRecorded(fileOf("rec.m4a", 4_000_000))
        dispatcher.scheduler.advanceUntilIdle()

        assertThat(ready(viewModel).project.durationUs).isEqualTo(4_000_000)
    }

    @Test
    fun `recorder only mode returns the recording without editing`() {
        val viewModel = viewModel(EditorInput.Capture(MediaKind.AUDIO), AudioEditorConfig(editAfterRecording = false))

        viewModel.onRecorded(fileOf("rec.m4a", 4_000_000))
        dispatcher.scheduler.advanceUntilIdle()

        val result = viewModel.result.value as FrameKitResult.Success
        assertThat(result.output.mediaType).isEqualTo(MediaType.AUDIO)
        assertThat(mixed).isEmpty()
    }

    @Test
    fun `insert recording pushes the rest back while overlay keeps the length`() {
        val viewModel = viewModel(file("a.m4a", 4_000_000))
        viewModel.seekTo(1_000_000)

        viewModel.openRecordSheet(RecordTarget.INSERT)
        viewModel.onRecorded(fileOf("rec1.m4a", 2_000_000))
        dispatcher.scheduler.advanceUntilIdle()
        assertThat(ready(viewModel).project.durationUs).isEqualTo(6_000_000)
        assertThat(ready(viewModel).project.mainClips).hasSize(3)
        // 끼워 넣은 소리 끝으로 재생 위치가 옮겨 간다.
        assertThat(ready(viewModel).positionUs).isEqualTo(3_000_000)

        viewModel.openRecordSheet(RecordTarget.OVERLAY)
        viewModel.onRecorded(fileOf("rec2.m4a", 1_000_000))
        dispatcher.scheduler.advanceUntilIdle()
        val project = ready(viewModel).project
        assertThat(project.durationUs).isEqualTo(6_000_000)
        assertThat(project.clips.count { it.role == AudioRole.MUSIC }).isEqualTo(1)
        assertThat(ready(viewModel).transaction.history.past).hasSize(2)
    }

    @Test
    fun `split then delete closes the gap and undo restores it`() {
        val viewModel = viewModel(file("a.m4a", 4_000_000))
        viewModel.seekTo(1_000_000)
        viewModel.splitAtPlayhead()
        assertThat(ready(viewModel).project.mainClips).hasSize(2)

        viewModel.deleteSelected()
        assertThat(ready(viewModel).project.durationUs).isEqualTo(1_000_000)

        viewModel.undo()
        assertThat(ready(viewModel).project.durationUs).isEqualTo(4_000_000)
    }

    @Test
    fun `split at an edge shows a notice instead of a change`() {
        val viewModel = viewModel(file("a.m4a", 4_000_000))
        viewModel.seekTo(0)

        viewModel.splitAtPlayhead()

        assertThat(ready(viewModel).notice).isEqualTo(AudioNotice.SPLIT_UNAVAILABLE)
        assertThat(ready(viewModel).transaction.history.past).isEmpty()
    }

    @Test
    fun `cut range and keep range change the length`() {
        val viewModel = viewModel(file("a.m4a", 10_000_000))
        viewModel.seekTo(2_000_000)
        viewModel.beginRange()
        viewModel.moveRangeEdge(start = false, timeUs = 6_000_000)
        viewModel.deleteRange()
        assertThat(ready(viewModel).project.durationUs).isEqualTo(6_000_000)

        viewModel.seekTo(1_000_000)
        viewModel.beginRange()
        viewModel.moveRangeEdge(start = false, timeUs = 3_000_000)
        viewModel.keepRange()
        assertThat(ready(viewModel).project.durationUs).isEqualTo(2_000_000)
    }

    @Test
    fun `volume drag is one undo step`() {
        val viewModel = viewModel(file("a.m4a", 4_000_000))
        viewModel.selectClipAtPlayhead()

        listOf(80f, 50f, 150f).forEach(viewModel::changeSelectedVolume)
        viewModel.finishGesture()

        assertThat(ready(viewModel).transaction.history.past).hasSize(1)
        assertThat(ready(viewModel).project.clips.single().volume).isEqualTo(1.5)
    }

    @Test
    fun `trimming the voice lane end keeps it gapless`() {
        val viewModel = viewModel(EditorInput.Multiple(listOf(file("a.m4a", 2_000_000), file("b.m4a", 3_000_000))))
        val first = ready(viewModel).project.mainClips.first()

        assertThat(viewModel.beginDrag(first.id, DragKind.MOVE)).isFalse()
        assertThat(viewModel.beginDrag(first.id, DragKind.END)).isTrue()
        viewModel.dragBy(-1_000_000)
        viewModel.endDrag()

        val project = ready(viewModel).project
        assertThat(project.durationUs).isEqualTo(4_000_000)
        assertThat(project.rangeOf(project.mainClips[1]).startUs).isEqualTo(1_000_000)
    }

    @Test
    fun `save mixes every clip and returns audio`() {
        val viewModel = viewModel(EditorInput.Multiple(listOf(file("a.m4a", 2_000_000), file("b.m4a", 3_000_000))))

        viewModel.save()
        dispatcher.scheduler.advanceUntilIdle()

        val (duration, tracks) = mixed.single()
        assertThat(duration).isEqualTo(5_000_000)
        assertThat(tracks).hasSize(2)
        val media = (viewModel.result.value as FrameKitResult.Success).output
        assertThat(media.mediaType).isEqualTo(MediaType.AUDIO)
        assertThat(media.mimeType).isEqualTo("audio/mp4")
    }

    @Test
    fun `edits survive process death`() {
        val savedState = SavedStateHandle()
        val first = viewModel(file("a.m4a", 4_000_000), savedState = savedState)
        first.seekTo(1_000_000)
        first.splitAtPlayhead()
        dispatcher.scheduler.advanceUntilIdle()

        val restored = viewModel(file("a.m4a", 4_000_000), savedState = SavedStateHandle(savedState.keys().associateWith { savedState.get<Any>(it) }))

        assertThat(ready(restored).project.mainClips).hasSize(2)
        assertThat(ready(restored).notice).isEqualTo(AudioNotice.RESTORED)
    }

    @Test
    fun `back closes the selection before asking to discard`() {
        val viewModel = viewModel(file("a.m4a", 4_000_000))
        viewModel.seekTo(1_000_000)
        viewModel.splitAtPlayhead()

        viewModel.onBack()
        assertThat(ready(viewModel).selectedClipId).isNull()
        viewModel.onBack()
        assertThat(ready(viewModel).showDiscardDialog).isTrue()
        assertThat(viewModel.result.value).isNull()
    }

    private fun fileOf(name: String, durationUs: Long): File {
        durations[name] = durationUs
        return File(context.cacheDir, name).apply { writeBytes(ByteArray(16)) }
    }

    private fun file(name: String, durationUs: Long) = EditorInput.FileSource(fileOf(name, durationUs).absolutePath)

    private fun ready(viewModel: AudioEditorViewModel): AudioEditorUiState.Ready {
        dispatcher.scheduler.advanceUntilIdle()
        return viewModel.state.value as AudioEditorUiState.Ready
    }

    private fun viewModel(
        input: EditorInput,
        config: AudioEditorConfig = AudioEditorConfig(),
        savedState: SavedStateHandle = SavedStateHandle(),
    ): AudioEditorViewModel {
        val viewModel = AudioEditorViewModel(
            request = AudioEditorRequest(input, config = config),
            savedState = savedState,
            registry = SessionSourceRegistry(context),
            outputStore = store,
            ioDispatcher = dispatcher,
            describe = { source ->
                val name = (source as? EditorInput.FileSource)?.let { File(it.absolutePath).name }
                durations[name]?.let { AudioSource(name, it) }
            },
            mixer = { duration, tracks, output, _, onProgress ->
                onProgress(0.5f)
                output.writeBytes(ByteArray(8))
                mixed += duration to tracks
            },
        )
        dispatcher.scheduler.advanceUntilIdle()
        return viewModel
    }
}
