package com.naury.framekit.android.export

import android.net.Uri
import com.google.common.truth.Truth.assertThat
import com.naury.framekit.android.result.EditedMedia
import com.naury.framekit.android.result.EditorErrorCode
import com.naury.framekit.android.result.FrameKitException
import com.naury.framekit.android.result.FrameKitResult
import com.naury.framekit.core.model.MediaType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class CoroutineExportHandleTest {

    private val media = EditedMedia(Uri.parse("content://test/1"), MediaType.IMAGE, 10, 10, null, "image/png", 100L)

    @Test
    fun `work runs once and every caller gets the same result`() = runTest {
        var runs = 0
        val gate = CompletableDeferred<Unit>()
        val handle = CoroutineExportHandle(this) { report ->
            runs++
            report(ExportState.Preparing)
            gate.await()
            report(ExportState.Running(0.5f))
            media
        }
        val states = mutableListOf<ExportState>()
        val collector = launch(UnconfinedTestDispatcher(testScheduler)) { handle.state.toList(states) }

        gate.complete(Unit)
        val first = handle.awaitResult()
        val second = handle.awaitResult()
        collector.cancel()

        assertThat(runs).isEqualTo(1)
        assertThat(first).isEqualTo(FrameKitResult.Success(media))
        assertThat(second).isEqualTo(first)
        assertThat(states.last()).isEqualTo(ExportState.Completed(media))
        assertThat(states.count { it.isTerminal }).isEqualTo(1)
    }

    @Test
    fun `cancel before completion ends in cancelled and cancel after completion is ignored`() = runTest {
        val never = CompletableDeferred<Unit>()
        val running = CoroutineExportHandle(this) { never.await(); media }

        running.cancel()

        assertThat(running.awaitResult()).isEqualTo(FrameKitResult.Cancelled)
        assertThat(running.state.value).isEqualTo(ExportState.Cancelled)

        val done = CoroutineExportHandle(this) { media }
        done.awaitResult()
        done.cancel()
        assertThat(done.state.value).isEqualTo(ExportState.Completed(media))
    }

    @Test
    fun `engine failures become failed state and failure results`() = runTest {
        val known = CoroutineExportHandle(this) { throw FrameKitException(EditorErrorCode.INSUFFICIENT_STORAGE) }
        val unknown = CoroutineExportHandle(this) { throw IllegalStateException("boom") }

        assertThat((known.awaitResult() as FrameKitResult.Failure).error.code).isEqualTo(EditorErrorCode.INSUFFICIENT_STORAGE)
        assertThat((known.state.value as ExportState.Failed).error.code).isEqualTo(EditorErrorCode.INSUFFICIENT_STORAGE)
        assertThat((unknown.awaitResult() as FrameKitResult.Failure).error.code).isEqualTo(EditorErrorCode.UNKNOWN)
    }
}
