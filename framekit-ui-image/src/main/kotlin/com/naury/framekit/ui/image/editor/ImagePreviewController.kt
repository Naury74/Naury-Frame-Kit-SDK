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

/** Preview bitmap for one project snapshot. */
internal data class RenderedPreview(val bitmap: Bitmap, val project: ImageProject, val mode: PreviewMode)

/**
 * Renders color-effected previews off the main thread.
 *
 * Only the latest request is rendered; a slider drag that outruns the renderer skips intermediate
 * values instead of queueing them. Projects without color effects are drawn directly by the canvas
 * and need no bitmap.
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

    /** Filter thumbnails by preset id, rendered from a 160 px copy of the source with the same renderer. */
    val thumbnails: StateFlow<Map<String, Bitmap>> = _thumbnails.asStateFlow()

    init {
        scope.launch {
            combine(requests.filterNotNull(), viewport.filterNotNull()) { request, size -> request to size }
                .collectLatest { (request, size) ->
                    if (request.project.colorSpec.isIdentity) {
                        _rendered.value = null
                        return@collectLatest
                    }
                    try {
                        val bitmap = withContext(renderDispatcher) {
                            renderer.render(request.source, request.project, request.metadata, request.mode, size.first, size.second)
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

    fun request(source: DecodedImage, project: ImageProject, metadata: SourceMetadata, mode: PreviewMode) {
        requests.value = Request(source, project, metadata, mode)
    }

    /** Renders thumbnails once per source; a new source cancels the previous job. */
    fun ensureThumbnails(source: DecodedImage) {
        if (thumbnailSource === source) return
        thumbnailSource = source
        thumbnailJob?.cancel()
        _thumbnails.value = emptyMap()
        thumbnailJob = scope.launch {
            val base = withContext(renderDispatcher) { scaledCopy(source.bitmap) }
            FilterCatalog.presets.forEach { preset ->
                val thumbnail = withContext(renderDispatcher) {
                    base.copy(Bitmap.Config.ARGB_8888, true).also { bitmap ->
                        colorRenderer.apply(bitmap, ColorEffectSpec.of(Adjustments(), FilterSelection(preset.id, 1.0), 0L), includeCanvasEffects = false)
                    }
                }
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
    )

    private companion object {
        const val THUMBNAIL_LONG_EDGE = 160
    }
}
