package com.naury.framekit.mixed

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.naury.framekit.android.result.EditedMedia
import com.naury.framekit.android.result.EditorError
import com.naury.framekit.android.result.EditorErrorCode
import com.naury.framekit.android.result.FrameKitResult
import com.naury.framekit.core.model.MediaType
import com.naury.framekit.ui.component.EmbeddedEditor
import com.naury.framekit.ui.component.EmbeddedEditorState
import com.naury.framekit.ui.layout.FoldPosture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class MixedEditorViewModelTest {

    private val application = ApplicationProvider.getApplicationContext<Application>()

    private fun media(name: String) = EditedMedia(Uri.parse("content://test/$name"), MediaType.IMAGE, 10, 10, null, "image/jpeg", 1)

    private class FakeEditor(
        initial: EmbeddedEditorState,
        private val outcome: () -> FrameKitResult?,
    ) : EmbeddedEditor {
        var saves = 0
        override val state = MutableStateFlow(initial)
        private val _result = MutableStateFlow<FrameKitResult?>(null)
        override val result: StateFlow<FrameKitResult?> = _result

        override fun save() {
            saves++
            _result.value = outcome()
        }

        override fun cancelExport() = Unit
        override fun dismissExportError() = Unit
        override fun discard() {
            if (_result.value == null) _result.value = FrameKitResult.Cancelled
        }
        override fun onStop() = Unit

        @Composable
        override fun Content(posture: FoldPosture) = Unit
    }

    private fun ready(name: String) = FakeEditor(EmbeddedEditorState(ready = true)) { FrameKitResult.Success(media(name)) }

    private fun broken(code: EditorErrorCode) = FakeEditor(EmbeddedEditorState(failed = true, failure = code)) { null }

    @Before
    fun setUp() = Dispatchers.setMain(Dispatchers.Unconfined)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `an item that could not be opened is skipped after asking`() {
        val vm = MixedEditorViewModel(application, SavedStateHandle())
        val good = ready("a")
        vm.attach(listOf(good, broken(EditorErrorCode.DECODE_FAILED)))

        vm.saveAll()
        assertThat(vm.confirmSkip.value).isTrue()
        assertThat(good.saves).isEqualTo(0)

        vm.confirmSkip()
        assertThat(vm.result.value).isEqualTo(FrameKitResult.Success(media("a")))
    }

    @Test
    fun `when nothing could be opened the real failure is returned`() {
        val vm = MixedEditorViewModel(application, SavedStateHandle())
        vm.attach(listOf(broken(EditorErrorCode.PERMISSION_DENIED), broken(EditorErrorCode.DECODE_FAILED)))

        vm.saveAll()
        vm.confirmSkip()
        assertThat((vm.result.value as FrameKitResult.Failure).error.code).isEqualTo(EditorErrorCode.PERMISSION_DENIED)
    }

    @Test
    fun `saved outputs survive in the saved state and are not saved again`() {
        val savedState = SavedStateHandle()
        val first = MixedEditorViewModel(application, savedState)
        var fail = true
        val flaky = FakeEditor(EmbeddedEditorState(ready = true)) {
            if (fail) FrameKitResult.Failure(EditorError(EditorErrorCode.OUTPUT_WRITE_FAILED)) else FrameKitResult.Success(media("b"))
        }
        first.attach(listOf(ready("a"), flaky))
        first.saveAll()
        assertThat(first.saved.value).containsExactly(0)

        // 프로세스가 다시 시작돼 에디터가 새로 만들어졌다.
        val restored = MixedEditorViewModel(application, savedState)
        val reopened = ready("a2")
        fail = false
        restored.attach(listOf(reopened, flaky))
        assertThat(restored.saved.value).containsExactly(0)
        restored.saveAll()
        assertThat(reopened.saves).isEqualTo(0)
        assertThat(restored.result.value).isEqualTo(FrameKitResult.Success(media("a"), listOf(media("a"), media("b"))))
    }
}
