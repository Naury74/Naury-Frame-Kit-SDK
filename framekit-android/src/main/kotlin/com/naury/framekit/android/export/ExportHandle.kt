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
 * export 한 번의 생명주기. `Idle → Preparing → Running → Finalizing → Completed` 순서로 진행하며,
 * 종료 상태가 아니면 어디서든 `Cancelling → Cancelled` 또는 `Failed`로 갈 수 있다. 종료 상태는 한 번만 방출된다.
 */
public sealed interface ExportState {
    public data object Idle : ExportState
    public data object Preparing : ExportState

    /** @property progress `0..1`. 엔진이 진행률을 보고할 수 없으면 `null`. */
    public data class Running(val progress: Float? = null) : ExportState
    public data object Finalizing : ExportState
    public data object Cancelling : ExportState
    /** @property outputs 모든 결과. 여러 파일을 만드는 export(사진마다 PDF 등)가 아니면 [output] 하나다. */
    public data class Completed(val output: EditedMedia, val outputs: List<EditedMedia> = listOf(output)) : ExportState
    public data object Cancelled : ExportState
    public data class Failed(val error: EditorError) : ExportState

    public val isTerminal: Boolean get() = this is Completed || this is Cancelled || this is Failed
}

/**
 * 실행 중인 export의 핸들.
 *
 * 작업은 핸들이 생성될 때 한 번만 시작된다. [state]를 여러 번 수집해도 다시 시작되지 않으며,
 * [awaitResult]는 모든 호출자에게 같은 종료 결과를 반환한다.
 */
public interface ExportHandle {
    public val state: StateFlow<ExportState>

    /**
     * export가 끝날 때까지 일시 중단한다. export 실패는 던지지 않고 [FrameKitResult.Failure]로 반환한다.
     * export를 취소하면 [FrameKitResult.Cancelled]를 반환한다.
     */
    public suspend fun awaitResult(): FrameKitResult

    /** 취소를 요청한다. export가 이미 끝났으면 아무 효과가 없다. */
    public fun cancel()
}

/**
 * [scope]에서 시작한 코루틴 기반 [ExportHandle]. 핸들은 [scope]와 수명을 같이 하며, scope가 취소되면
 * export도 취소되고 엔진이 partial 파일을 삭제한다.
 *
 * @param work export를 수행하고 인자로 받은 함수로 진행 상황을 보고한다.
 */
public class CoroutineExportHandle private constructor(
    scope: CoroutineScope,
    work: suspend (report: (ExportState) -> Unit) -> List<EditedMedia>,
    @Suppress("UNUSED_PARAMETER") many: Boolean,
) : ExportHandle {

    public constructor(scope: CoroutineScope, work: suspend (report: (ExportState) -> Unit) -> EditedMedia) :
        this(scope, { report -> listOf(work(report)) }, true)

    private val _state = MutableStateFlow<ExportState>(ExportState.Idle)
    override val state: StateFlow<ExportState> = _state.asStateFlow()

    private val job: Deferred<FrameKitResult> = scope.async(start = CoroutineStart.UNDISPATCHED) {
        try {
            val outputs = work(::report)
            check(outputs.isNotEmpty()) { "Export produced no output" }
            finish(ExportState.Completed(outputs.first(), outputs))
            FrameKitResult.Success(outputs.first(), outputs)
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

    public companion object {
        /** 결과 파일을 여러 개 만들 수 있는 export(사진마다 PDF 등)의 핸들. 빈 목록은 실패로 처리한다. */
        public fun ofMany(scope: CoroutineScope, work: suspend (report: (ExportState) -> Unit) -> List<EditedMedia>): CoroutineExportHandle =
            CoroutineExportHandle(scope, work, true)
    }
}
