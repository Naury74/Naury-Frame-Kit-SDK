package com.naury.framekit.ui.image.editor

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.naury.framekit.android.input.EditorInput
import com.naury.framekit.android.output.AppFileOutputStore
import com.naury.framekit.android.result.EditorErrorCode
import com.naury.framekit.android.result.FrameKitResult
import com.naury.framekit.android.session.EditorSessionStore
import com.naury.framekit.android.source.SessionSourceRegistry
import com.naury.framekit.core.geometry.CropAspectRatio
import com.naury.framekit.core.geometry.CropHandle
import com.naury.framekit.image.export.ImageExportConfig
import com.naury.framekit.image.export.ImageExportCoordinator
import com.naury.framekit.ui.image.contract.ImageEditorConfig
import com.naury.framekit.ui.image.contract.ImageEditorRequest
import com.naury.framekit.ui.image.contract.ImageTool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.concurrent.Executor

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImageEditorViewModelTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val sourceFile = File(context.cacheDir, "photo.png")
    private val store = AppFileOutputStore(context, availableBytes = { Long.MAX_VALUE })
    private val sessions = EditorSessionStore(context, background = Executor { it.run() })

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        FileProvider::class.java.getDeclaredField("sCache").apply { isAccessible = true }.let {
            (it.get(null) as MutableMap<*, *>).clear()
        }
        writeImage(width = 400, height = 200)
    }

    private fun writeImage(width: Int, height: Int) {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(Color.BLUE)
            drawRect(0f, 0f, height.toFloat(), height.toFloat(), Paint().apply { color = Color.RED })
        }
        sourceFile.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `square crop apply undo redo then save returns a square image`() {
        val viewModel = viewModel(EditorInput.FileSource(sourceFile.absolutePath))
        assertThat(viewModel.state.value).isInstanceOf(ImageEditorUiState.Ready::class.java)

        viewModel.selectTool(ImageTool.CROP)
        viewModel.selectAspect(CropAspectRatio.Fixed(1, 1))
        viewModel.applyTool()
        assertThat(ready(viewModel).transaction.history.canUndo).isTrue()

        viewModel.undo()
        assertThat(ready(viewModel).isDirty).isFalse()
        viewModel.redo()
        assertThat(ready(viewModel).isDirty).isTrue()

        viewModel.save()

        val result = viewModel.result.value as FrameKitResult.Success
        assertThat(result.output.width).isEqualTo(200)
        assertThat(result.output.height).isEqualTo(200)
        val exported = BitmapFactory.decodeFile(publishedFiles().single().absolutePath)
        // 1:1은 가운데 정사각형이므로 왼쪽은 빨강, 오른쪽은 파랑이다.
        assertThat(Color.red(exported.getPixel(10, 100))).isGreaterThan(200)
        assertThat(Color.blue(exported.getPixel(190, 100))).isGreaterThan(200)
    }

    @Test
    fun `one tool session with many changes is a single undo step`() {
        val viewModel = viewModel(EditorInput.FileSource(sourceFile.absolutePath))

        viewModel.selectTool(ImageTool.ROTATE)
        viewModel.rotateRight()
        viewModel.flipHorizontal()
        repeat(20) { viewModel.changeStraighten(it * 0.5) }
        viewModel.finishStraighten()
        viewModel.applyTool()

        val history = ready(viewModel).transaction.history
        assertThat(history.past).hasSize(1)
        assertThat(history.current.geometry.quarterTurns).isEqualTo(1)
        assertThat(history.current.geometry.flipX).isTrue()
        assertThat(history.undo().isDirty).isFalse()
    }

    @Test
    fun `cancelled crop draft leaves the project unchanged`() {
        val viewModel = viewModel(EditorInput.FileSource(sourceFile.absolutePath))

        viewModel.selectTool(ImageTool.CROP)
        viewModel.beginCropDrag(CropHandle.TOP_LEFT)
        viewModel.dragCrop(0.2, 0.2)
        viewModel.endCropDrag()
        assertThat(ready(viewModel).hasDraftChanges).isTrue()
        viewModel.cancelTool()

        assertThat(ready(viewModel).isDirty).isFalse()
        assertThat(ready(viewModel).transaction.history.canUndo).isFalse()
    }

    @Test
    fun `save while a tool is open asks to apply first and exports nothing`() {
        val viewModel = viewModel(EditorInput.FileSource(sourceFile.absolutePath))
        viewModel.selectTool(ImageTool.CROP)

        viewModel.save()

        assertThat(ready(viewModel).showApplyHint).isTrue()
        assertThat(viewModel.result.value).isNull()
        assertThat(publishedFiles()).isEmpty()
    }

    @Test
    fun `Q10 second save after completion does not export again`() {
        val viewModel = viewModel(EditorInput.FileSource(sourceFile.absolutePath))

        viewModel.save()
        viewModel.save()

        assertThat(viewModel.result.value).isInstanceOf(FrameKitResult.Success::class.java)
        assertThat(publishedFiles()).hasSize(1)
    }

    @Test
    fun `Q01 dismissing the picker returns cancelled once`() {
        val viewModel = viewModel(EditorInput.Pick())
        assertThat(viewModel.state.value).isEqualTo(ImageEditorUiState.AwaitingPick)

        viewModel.onPicked(null)
        viewModel.onPicked(null)

        assertThat(viewModel.result.value).isEqualTo(FrameKitResult.Cancelled)
    }

    @Test
    fun `picked uri is kept in saved state so recreation does not reopen the picker`() {
        val savedState = SavedStateHandle()
        val first = viewModel(EditorInput.Pick(), savedState)
        first.onPicked(Uri.fromFile(sourceFile))

        val recreated = viewModel(EditorInput.Pick(), savedState)

        assertThat(recreated.state.value).isInstanceOf(ImageEditorUiState.Ready::class.java)
    }

    @Test
    fun `close without changes cancels and close with changes asks first`() {
        val clean = viewModel(EditorInput.FileSource(sourceFile.absolutePath))
        clean.requestClose()
        assertThat(clean.result.value).isEqualTo(FrameKitResult.Cancelled)

        val edited = viewModel(EditorInput.FileSource(sourceFile.absolutePath))
        edited.selectTool(ImageTool.ROTATE)
        edited.rotateRight()
        edited.applyTool()
        edited.requestClose()
        assertThat(edited.result.value).isNull()
        assertThat(ready(edited).showDiscardDialog).isTrue()
    }

    @Test
    fun `back closes an open tool before the editor`() {
        val viewModel = viewModel(EditorInput.FileSource(sourceFile.absolutePath))
        viewModel.selectTool(ImageTool.CROP)

        viewModel.onBack()

        assertThat(ready(viewModel).activeTool).isNull()
        assertThat(viewModel.result.value).isNull()
    }

    @Test
    fun `missing source shows an error and close returns the failure`() {
        val viewModel = viewModel(EditorInput.FileSource(File(context.cacheDir, "missing.png").absolutePath))
        val failed = viewModel.state.value as ImageEditorUiState.LoadFailed
        assertThat(failed.code).isEqualTo(EditorErrorCode.SOURCE_UNAVAILABLE)

        viewModel.requestClose()

        assertThat((viewModel.result.value as FrameKitResult.Failure).error.code).isEqualTo(EditorErrorCode.SOURCE_UNAVAILABLE)
    }

    @Test
    fun `disabled tools cannot be opened`() {
        val viewModel = viewModel(
            EditorInput.FileSource(sourceFile.absolutePath),
            config = ImageEditorConfig(enabledTools = setOf(ImageTool.ROTATE)),
        )

        viewModel.selectTool(ImageTool.CROP)

        assertThat(ready(viewModel).activeTool).isNull()
    }

    @Test
    fun `Q13 committed edits come back after process death without undo history`() {
        val savedState = SavedStateHandle()
        val before = viewModel(EditorInput.FileSource(sourceFile.absolutePath), savedState)
        before.selectTool(ImageTool.ROTATE)
        before.rotateRight()
        before.applyTool()

        // 같은 SavedStateHandle로 새 ViewModel을 만들면 프로세스가 다시 시작된 것과 같다.
        val after = viewModel(EditorInput.FileSource(sourceFile.absolutePath), savedState)

        val restored = ready(after)
        assertThat(restored.displayed.geometry.quarterTurns).isEqualTo(1)
        assertThat(restored.transaction.history.canUndo).isFalse()
        assertThat(restored.isDirty).isTrue()
        assertThat(restored.notice).isEqualTo(SessionNotice.RESTORED)
    }

    @Test
    fun `draft of an open tool is not restored`() {
        val savedState = SavedStateHandle()
        val before = viewModel(EditorInput.FileSource(sourceFile.absolutePath), savedState)
        before.selectTool(ImageTool.ROTATE)
        before.rotateRight()

        val after = viewModel(EditorInput.FileSource(sourceFile.absolutePath), savedState)

        assertThat(ready(after).displayed.geometry.quarterTurns).isEqualTo(0)
        assertThat(ready(after).isDirty).isFalse()
        assertThat(ready(after).notice).isNull()
    }

    @Test
    fun `a different image behind the same reference starts fresh`() {
        val savedState = SavedStateHandle()
        val before = viewModel(EditorInput.FileSource(sourceFile.absolutePath), savedState)
        before.selectTool(ImageTool.CROP)
        before.selectAspect(CropAspectRatio.Fixed(1, 1))
        before.applyTool()
        val oldSession = checkNotNull(savedState.get<String>(ImageSessionRecorder.KEY_SESSION_ID))
        writeImage(width = 300, height = 300)

        val after = viewModel(EditorInput.FileSource(sourceFile.absolutePath), savedState)

        assertThat(ready(after).isDirty).isFalse()
        assertThat(File(sessions.directory, oldSession).exists()).isFalse()
    }

    @Test
    fun `interrupted save is reported after restart`() {
        val savedState = SavedStateHandle()
        viewModel(EditorInput.FileSource(sourceFile.absolutePath), savedState)
        val id = checkNotNull(savedState.get<String>(ImageSessionRecorder.KEY_SESSION_ID))
        val record = checkNotNull(sessions.load(id))
        sessions.saveSnapshot(id, record.snapshot, exportInProgress = true)

        val after = viewModel(EditorInput.FileSource(sourceFile.absolutePath), savedState)

        assertThat(ready(after).notice).isEqualTo(SessionNotice.EXPORT_INTERRUPTED)
        assertThat(sessions.load(id)?.exportWasInterrupted).isFalse()
    }

    @Test
    fun `Q14 lost source can be chosen again and is restored only if it is the same image`() {
        val savedState = SavedStateHandle()
        val uri = Uri.fromFile(sourceFile)
        val before = viewModel(EditorInput.Pick(), savedState)
        before.onPicked(uri)
        before.selectTool(ImageTool.ROTATE)
        before.flipVertical()
        before.applyTool()
        val original = sourceFile.readBytes()
        sourceFile.delete()

        val after = viewModel(EditorInput.Pick(), savedState)
        val failed = after.state.value as ImageEditorUiState.LoadFailed
        assertThat(failed.code).isEqualTo(EditorErrorCode.SOURCE_UNAVAILABLE)
        assertThat(failed.canChooseAnother).isTrue()

        sourceFile.writeBytes(original)
        after.chooseAnother()
        after.onPicked(uri)

        assertThat(ready(after).displayed.geometry.flipY).isTrue()
    }

    @Test
    fun `finishing the editor deletes its session`() {
        val savedState = SavedStateHandle()
        val viewModel = viewModel(EditorInput.FileSource(sourceFile.absolutePath), savedState)
        val id = checkNotNull(savedState.get<String>(ImageSessionRecorder.KEY_SESSION_ID))

        viewModel.save()

        assertThat(viewModel.result.value).isInstanceOf(FrameKitResult.Success::class.java)
        assertThat(File(sessions.directory, id).exists()).isFalse()
        assertThat(savedState.contains(ImageSessionRecorder.KEY_SESSION_ID)).isFalse()
    }

    private fun viewModel(
        input: EditorInput,
        savedState: SavedStateHandle = SavedStateHandle(),
        config: ImageEditorConfig = ImageEditorConfig(),
    ): ImageEditorViewModel {
        val registry = SessionSourceRegistry(context)
        return ImageEditorViewModel(
            request = ImageEditorRequest(input = input, config = config, export = ImageExportConfig()),
            savedState = savedState,
            registry = registry,
            exportCoordinator = ImageExportCoordinator(registry, store, memoryBudgetBytes = 256L * 1024 * 1024, dispatcher = Dispatchers.Unconfined),
            previewLongEdge = 2048,
            ioDispatcher = Dispatchers.Unconfined,
            sessionStore = sessions,
            snapshotDebounceMillis = 0L,
        )
    }

    private fun ready(viewModel: ImageEditorViewModel) = viewModel.state.value as ImageEditorUiState.Ready

    private fun publishedFiles(): List<File> = store.directory.listFiles().orEmpty().filterNot { it.name.startsWith(".") }
}
