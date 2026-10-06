package com.naury.framekit.android.export

import com.naury.framekit.android.result.EditedMedia
import com.naury.framekit.android.result.EditorError
import com.naury.framekit.android.result.EditorErrorCode
import com.naury.framekit.android.result.FrameKitException
import com.naury.framekit.android.result.FrameKitResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Lifecycle of one export. `Idle → Preparing → Running → Finalizing → Completed`, with `Cancelling →
 * Cancelled` and `Failed` possible from any non-terminal state. A terminal state is emitted once.
 */
public sealed interface ExportState {
    public data object Idle : ExportState
    public data object Preparing : ExportState

    /** @property progress `0..1`, or `null` when the engine cannot report progress. */
    public data class Running(val progress: Float? = null) : ExportState
    public data object Finalizing : ExportState
    public data object Cancelling : ExportState
    public data class Completed(val output: EditedMedia) : ExportState
    public data object Cancelled : ExportState
    public data class Failed(val error: EditorError) : ExportState

    public val isTerminal: Boolean get() = this is Completed || this is Cancelled || this is Failed
}

/**
 * Handle of a running export.
 *
 * The work starts once when the handle is created. Collecting [state] any number of times never
 * restarts it, and [awaitResult] returns the same terminal result to every caller.
 */
public interface ExportHandle {
    public val state: StateFlow<ExportState>

    /**
     * Suspends until the export finishes. Never throws for export failures; they are returned as
     * [FrameKitResult.Failure]. Cancelling the export returns [FrameKitResult.Cancelled].
     */
    public suspend fun awaitResult(): FrameKitResult

    /** Requests cancellation. No effect once the export completed. */
    public fun cancel()
}

/**
 * [ExportHandle] backed by a coroutine started in [scope]. The handle lives as long as [scope]; when
 * the scope is cancelled the export is cancelled and partial files are removed by the engine.
 *
 * @param work performs the export and reports progress through its argument.
 */
public class CoroutineExportHandle(
    scope: CoroutineScope,
    work: suspend (report: (ExportState) -> Unit) -> EditedMedia,
) : ExportHandle {

    private val _state = MutableStateFlow<ExportState>(ExportState.Idle)
    override val state: StateFlow<ExportState> = _state.asStateFlow()

    private val job: Deferred<FrameKitResult> = scope.async(start = CoroutineStart.UNDISPATCHED) {
        try {
            val media = work(::report)
            finish(ExportState.Completed(media))
            FrameKitResult.Success(media)
        } catch (cancelled: CancellationException) {
            finish(ExportState.Cancelled)
            throw cancelled
        } catch (error: FrameKitException) {
            val editorError = error.toEditorError()
            finish(ExportState.Failed(editorError))
            FrameKitResult.Failure(editorError)
        } catch (error: Exception) {
            val editorError = EditorError(EditorErrorCode.UNKNOWN)
            finish(ExportState.Failed(editorError))
            FrameKitResult.Failure(editorError)
        }
    }

    override suspend fun awaitResult(): FrameKitResult = try {
        job.await()
    } catch (cancelled: CancellationException) {
        // 호출한 쪽 coroutine이 취소됐다면 그대로 전파하고, 내보내기만 취소된 경우에는 결과로 돌려준다.
        currentCoroutineContext().ensureActive()
        FrameKitResult.Cancelled
    }

    override fun cancel() {
        if (_state.value.isTerminal) return
        report(ExportState.Cancelling)
        job.cancel()
    }

    private fun report(state: ExportState) {
        _state.update { current -> if (current.isTerminal || current == ExportState.Cancelling && !state.isTerminal) current else state }
    }

    private fun finish(state: ExportState) {
        _state.update { current -> if (current.isTerminal) current else state }
    }
}
