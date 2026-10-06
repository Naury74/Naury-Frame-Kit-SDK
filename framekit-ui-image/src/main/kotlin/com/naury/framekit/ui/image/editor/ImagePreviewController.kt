package com.naury.framekit.ui.image.editor

import android.graphics.Bitmap
import androidx.core.graphics.scale
import com.naury.framekit.core.effect.Adjustments
import com.naury.framekit.core.effect.ColorEffectSpec
import com.naury.framekit.core.effect.FilterCatalog
import com.naury.framekit.core.effect.FilterSelection
import com.naury.framekit.core.model.ImageProject
import com.naury.framekit.core.model.SourceMetadata
import com.naury.framekit.image.decode.DecodedImage
import com.naury.framekit.image.effect.ColorEffectRenderer
import com.naury.framekit.image.render.ImagePreviewRenderer
import com.naury.framekit.image.render.PreviewMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 색상 효과나 가리기 마스크가 있어 미리보기를 오프스크린으로 렌더링해야 하면 `true`. */
internal val ImageProject.needsRenderedPreview: Boolean get() = !colorSpec.isIdentity || privacyMasks.isNotEmpty()

/** 프로젝트 스냅샷 하나에 대한 미리보기 비트맵. */
internal data class RenderedPreview(val bitmap: Bitmap, val project: ImageProject, val mode: PreviewMode)

/**
 * 색상 효과 미리보기를 메인 스레드 밖에서 렌더링한다.
 *
 * 최신 요청만 렌더링한다. 슬라이더 드래그가 렌더러보다 빠르면 중간 값을 큐에 쌓지 않고 건너뛴다.
 * 색상 효과가 없는 프로젝트는 캔버스가 직접 그리므로 비트맵이 필요 없다.
 */
internal class ImagePreviewController(
    private val scope: CoroutineScope,
    private val colorRenderer: ColorEffectRenderer,
    private val renderDispatcher: CoroutineDispatcher,
) {
    private val renderer = ImagePreviewRenderer(colorRenderer)
    private val requests = MutableStateFlow<Request?>(null)
    private val viewport = MutableStateFlow<Pair<Int, Int>?>(null)
    private val _rendered = MutableStateFlow<RenderedPreview?>(null)
    private val _thumbnails = MutableStateFlow<Map<String, Bitmap>>(emptyMap())
    private var thumbnailJob: Job? = null
    private var thumbnailSource: DecodedImage? = null

    val rendered: StateFlow<RenderedPreview?> = _rendered.asStateFlow()

    /** 프리셋 id별 필터 썸네일. 소스의 160 px 사본을 같은 렌더러로 렌더링한다. */
    val thumbnails: StateFlow<Map<String, Bitmap>> = _thumbnails.asStateFlow()

    init {
        scope.launch {
            combine(requests.filterNotNull(), viewport.filterNotNull()) { request, size -> request to size }
                .collectLatest { (request, size) ->
                    if (!request.project.needsRenderedPreview) {
                        _rendered.value = null
                        return@collectLatest
                    }
                    try {
                        val bitmap = withContext(renderDispatcher) {
                            renderer.render(request.source, request.project, request.metadata, request.mode, size.first, size.second, request.cutoutMask)
                        }
                        _rendered.value = RenderedPreview(bitmap, request.project, request.mode)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        // 미리보기 실패는 편집을 막지 않는다. 캔버스는 효과 없는 빠른 경로로 계속 그린다.
                        _rendered.value = null
                    }
                }
        }
    }

    fun onViewport(width: Int, height: Int) {
        if (width > 0 && height > 0) viewport.value = width to height
    }

    fun request(source: DecodedImage, project: ImageProject, metadata: SourceMetadata, mode: PreviewMode, cutoutMask: Bitmap?) {
        requests.value = Request(source, project, metadata, mode, cutoutMask)
    }

    /** 소스마다 썸네일을 한 번 렌더링한다. 새 소스가 들어오면 이전 작업을 취소한다. */
    fun ensureThumbnails(source: DecodedImage) {
        if (thumbnailSource === source) return
        thumbnailSource = source
        thumbnailJob?.cancel()
        _thumbnails.value = emptyMap()
        thumbnailJob = scope.launch {
            val base = withContext(renderDispatcher) { runCatching { scaledCopy(source.bitmap) }.getOrNull() }
            if (base == null) {
                // 메모리가 부족하면 다음에 필터 도구를 열 때 다시 시도한다.
                thumbnailSource = null
                return@launch
            }
            FilterCatalog.all.forEach { preset ->
                // 썸네일 하나를 못 만들어도(메모리·GL 오류) 나머지는 계속 만든다.
                val thumbnail = withContext(renderDispatcher) {
                    try {
                        base.copy(Bitmap.Config.ARGB_8888, true)?.also { bitmap ->
                            colorRenderer.apply(bitmap, ColorEffectSpec.of(Adjustments(), FilterSelection(preset.id, 1.0), 0L), includeCanvasEffects = false)
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        null
                    } catch (_: OutOfMemoryError) {
                        null
                    }
                } ?: return@forEach
                _thumbnails.update { it + (preset.id to thumbnail) }
            }
        }
    }

    private fun scaledCopy(bitmap: Bitmap): Bitmap {
        val scale = THUMBNAIL_LONG_EDGE.toFloat() / maxOf(bitmap.width, bitmap.height)
        return bitmap.scale(maxOf(1, (bitmap.width * scale).toInt()), maxOf(1, (bitmap.height * scale).toInt()))
    }

    private data class Request(
        val source: DecodedImage,
        val project: ImageProject,
        val metadata: SourceMetadata,
        val mode: PreviewMode,
        val cutoutMask: Bitmap?,
    )

    private companion object {
        const val THUMBNAIL_LONG_EDGE = 160
    }
}
