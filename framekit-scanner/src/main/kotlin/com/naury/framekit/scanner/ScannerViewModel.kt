package com.naury.framekit.scanner

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.naury.framekit.android.result.EditedMedia
import com.naury.framekit.android.result.FrameKitResult
import com.naury.framekit.core.document.DocumentQuad
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** 잠깐 띄울 안내. */
internal enum class ScannerMessage { FAILED, LIMIT, CAMERA_UNAVAILABLE }

/**
 * 스캐너 화면 상태.
 *
 * @property quad 화면에 그릴 문서 모서리(정규화, 똑바로 선 프레임 기준). 문서가 없으면 `null`.
 * @property frameAspect 카메라 프레임의 가로/세로 비. 미리보기 위에 테두리를 맞추는 데 쓴다.
 * @property progress 자동 촬영까지의 진행, `0..1`.
 * @property busy 찍은 쪽을 펴는 중이면 `true`.
 * @property shots 셔터를 누른 횟수. 화면의 촬영 효과를 다시 띄우는 데 쓴다.
 */
internal data class ScannerUiState(
    val quad: DocumentQuad? = null,
    val frameAspect: Float = 3f / 4f,
    val progress: Float = 0f,
    val autoCapture: Boolean = true,
    val torch: Boolean = false,
    val pages: List<EditedMedia> = emptyList(),
    val busy: Boolean = false,
    val shots: Int = 0,
    val message: ScannerMessage? = null,
)

/**
 * 카메라 프레임마다 문서를 따라가고, 자동·수동으로 찍은 쪽을 모은다.
 *
 * @param makePage 찍은 사진과 미리보기에서 찾은 모서리로 쪽 하나를 만든다(IO 스레드에서 부른다).
 * @param discardPage 버리는 쪽의 파일을 지운다.
 */
internal class ScannerViewModel(
    private val request: DocumentScanRequest,
    private val makePage: (File, DocumentQuad?) -> EditedMedia,
    private val discardPage: (EditedMedia) -> Unit,
    private val ioDispatcher: CoroutineDispatcher,
) : ViewModel() {

    private val tracker = QuadTracker()
    private val _state = MutableStateFlow(ScannerUiState(autoCapture = request.autoCapture))
    val state: StateFlow<ScannerUiState> = _state.asStateFlow()

    private val captures = Channel<Unit>(Channel.CONFLATED)

    /** 카메라에 촬영을 요청하는 신호. 카메라 화면이 받아 사진을 찍고 [onPhoto]로 돌려준다. */
    val captureRequests: Flow<Unit> = captures.receiveAsFlow()

    private val _result = MutableStateFlow<FrameKitResult?>(null)
    val result: StateFlow<FrameKitResult?> = _result.asStateFlow()

    private var capturing = false

    /** 카메라 프레임 하나에서 찾은 모서리([found])를 반영한다. 분석 스레드에서 부른다. */
    fun onFrame(found: DocumentQuad?, aspect: Float, nowMs: Long) {
        val tracked = synchronized(tracker) { tracker.update(found, nowMs) }
        _state.update { it.copy(quad = tracked.quad, frameAspect = aspect, progress = if (it.autoCapture) tracked.progress else 0f) }
        val current = _state.value
        if (tracked.ready && current.autoCapture && !current.busy && !capturing && current.pages.size < request.maxPages) requestCapture()
    }

    /** 셔터를 눌렀다. */
    fun shutter() {
        if (_state.value.pages.size >= request.maxPages) {
            _state.update { it.copy(message = ScannerMessage.LIMIT) }
            return
        }
        if (!capturing && !_state.value.busy) requestCapture()
    }

    private fun requestCapture() {
        capturing = true
        synchronized(tracker) { tracker.onCaptured() }
        _state.update { it.copy(shots = it.shots + 1, progress = 0f) }
        captures.trySend(Unit)
    }

    /** 카메라가 사진을 [photo]에 저장했다. 쪽으로 만들어 목록 끝에 더한다. */
    fun onPhoto(photo: File) {
        capturing = false
        val quad = _state.value.quad
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            val page = withContext(ioDispatcher) {
                try {
                    makePage(photo, quad)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                } catch (_: OutOfMemoryError) {
                    null
                } finally {
                    photo.delete()
                }
            }
            _state.update { current ->
                if (page == null) current.copy(busy = false, message = ScannerMessage.FAILED) else current.copy(busy = false, pages = current.pages + page)
            }
        }
    }

    /** 카메라가 사진을 찍지 못했다. */
    fun onPhotoFailed() {
        capturing = false
        _state.update { it.copy(message = ScannerMessage.FAILED) }
    }

    fun onCameraUnavailable() = _state.update { it.copy(message = ScannerMessage.CAMERA_UNAVAILABLE) }

    fun toggleAuto() = _state.update { it.copy(autoCapture = !it.autoCapture, progress = 0f) }

    fun toggleTorch() = _state.update { it.copy(torch = !it.torch) }

    /** 마지막 쪽을 버린다(다시 찍기). */
    fun removeLast() {
        val last = _state.value.pages.lastOrNull() ?: return
        _state.update { it.copy(pages = it.pages.dropLast(1)) }
        viewModelScope.launch(ioDispatcher) { discardPage(last) }
    }

    fun dismissMessage() = _state.update { it.copy(message = null) }

    /** 찍은 쪽을 돌려준다. 하나도 없으면 취소로 본다. */
    fun finish() {
        val pages = _state.value.pages
        if (_state.value.busy) return
        _result.value = if (pages.isEmpty()) FrameKitResult.Cancelled else FrameKitResult.Success(pages.first(), pages)
    }

    /** 찍은 쪽을 모두 버리고 닫는다. */
    fun cancel() {
        val pages = _state.value.pages
        _state.update { it.copy(pages = emptyList()) }
        viewModelScope.launch(ioDispatcher) { pages.forEach(discardPage) }
        _result.value = FrameKitResult.Cancelled
    }
}
