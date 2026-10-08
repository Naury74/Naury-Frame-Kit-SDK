package com.naury.framekit.image.export

import kotlinx.coroutines.CancellationException
import com.naury.framekit.core.pdf.PdfTextLine
import com.naury.framekit.image.ocr.TextRecognizers
import com.naury.framekit.image.ocr.TextRecognizer
import kotlin.math.roundToInt
import com.naury.framekit.core.pdf.PdfWriter
import com.naury.framekit.core.pdf.PdfPageLayout
import com.naury.framekit.core.pdf.PdfLayout
import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.os.Build
import com.naury.framekit.android.output.AppFileOutputStore
import com.naury.framekit.android.output.OutputTarget
import com.naury.framekit.android.result.EditedMedia
import com.naury.framekit.android.result.EditorErrorCode
import com.naury.framekit.android.result.ExportWarning
import com.naury.framekit.android.result.FrameKitException
import com.naury.framekit.android.session.ProjectAssetStore
import com.naury.framekit.android.source.SourceResolver
import com.naury.framekit.core.geometry.ExifOrientation
import com.naury.framekit.core.geometry.GeometryFrame
import com.naury.framekit.core.model.ImageProject
import com.naury.framekit.core.model.MediaType
import com.naury.framekit.core.model.PixelRect
import com.naury.framekit.core.model.PixelSize
import com.naury.framekit.core.validation.ValidationResult
import com.naury.framekit.image.decode.BitmapDecoder
import com.naury.framekit.image.decode.ImageSourceInfo
import com.naury.framekit.image.cutout.CutoutMasks
import com.naury.framekit.image.decode.SampleSize
import com.naury.framekit.image.effect.ColorEffectRenderer
import com.naury.framekit.image.effect.CpuColorEffectRenderer
import com.naury.framekit.image.overlay.OverlayRenderer
import com.naury.framekit.image.overlay.PrivacyRenderer
import com.naury.framekit.image.render.CanvasGeometryRenderer
import com.naury.framekit.image.render.ImageRenderPlanFactory
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.coroutines.coroutineContext
import kotlin.math.ceil
import kotlin.math.floor

/**
 * PDF 한 쪽이 될 사진.
 *
 * @property assets 배경 제거 mask처럼 프로젝트가 참조하는 asset 저장소.
 */
public data class PdfPage(val project: ImageProject, val source: ImageSourceInfo, val assets: ProjectAssetStore? = null)

/**
 * 불변 [ImageProject] 스냅샷 하나를 파일로 내보낸다.
 *
 * 원본은 절대 수정하지 않는다. 출력은 숨겨진 partial 파일에 쓰고 검증한 뒤에야 publish한다.
 * publish 전에 실패하거나 취소되면 partial 파일을 삭제하며, 일단 publish되면 취소가 늦게 도착해도
 * 내보내기는 완료로 간주한다.
 */
public class ImageExportCoordinator(
    private val resolver: SourceResolver,
    private val outputStore: AppFileOutputStore,
    private val memoryBudgetBytes: Long,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    contentResolver: ContentResolver? = null,
    private val colorRenderer: ColorEffectRenderer = CpuColorEffectRenderer,
    private val textRecognizer: TextRecognizer? = null,
) {
    public constructor(context: Context, resolver: SourceResolver) : this(
        resolver = resolver,
        outputStore = AppFileOutputStore(context),
        memoryBudgetBytes = ImageMemoryBudget.bytes(context),
        contentResolver = context.applicationContext.contentResolver,
        textRecognizer = TextRecognizers.find(context),
    )

    private val decoder = BitmapDecoder(resolver, contentResolver)
    private val overlayRenderer = OverlayRenderer()
    private val exifWriter = SafeExifWriter(resolver)

    /**
     * coordinator의 dispatcher에서 내보내기를 실행한다.
     *
     * @param assets 배경 제거 mask 같은 프로젝트 asset을 보관하는 store. 프로젝트에 누끼가 있으면
     *   필수다.
     * @param onStage 새 단계가 시작될 때 내보내기 스레드에서 호출된다.
     * @throws FrameKitException `INVALID_CONFIGURATION`, `INVALID_PROJECT`, `INSUFFICIENT_MEMORY`,
     *   `INSUFFICIENT_STORAGE`, `DECODE_FAILED`, `ENCODE_FAILED` 또는 `OUTPUT_WRITE_FAILED`.
     * @throws kotlinx.coroutines.CancellationException 출력이 publish되기 전에 호출 coroutine이
     *   취소된 경우.
     */
    public suspend fun export(
        project: ImageProject,
        source: ImageSourceInfo,
        config: ImageExportConfig,
        target: OutputTarget = OutputTarget.AppFile,
        assets: ProjectAssetStore? = null,
        onStage: (ImageExportStage) -> Unit = {},
    ): EditedMedia = withContext(dispatcher) {
        if (config.format == ImageFormat.PDF) {
            return@withContext exportPdf(listOf(PdfPage(project, source, assets)), config, target) { _, _, stage -> onStage(stage) }.single()
        }
        onStage(ImageExportStage.PREPARING)
        checkConfig(config)
        val metadata = source.metadata
        val outputSize = ImageRenderPlanFactory.outputSize(project, metadata, config.maxOutputPixels, config.maxWidth, config.maxHeight)
        checkStorage(outputSize, config.format)

        val warnings = mutableListOf<ExportWarning>()
        var partial: File? = null
        try {
            coroutineContext.ensureActive()
            onStage(ImageExportStage.RENDERING)
            val background = if (config.format.supportsAlpha) null else config.jpegBackgroundArgb
            val rendered = render(project, source, outputSize, background, assets, warnings)

            coroutineContext.ensureActive()
            onStage(ImageExportStage.ENCODING)
            partial = outputStore.createPartial(config.format.extension)
            try {
                encode(rendered, config, partial)
            } finally {
                rendered.recycle()
            }

            coroutineContext.ensureActive()
            onStage(ImageExportStage.FINALIZING)
            verify(partial, outputSize, config.format)
            if (config.format != ImageFormat.PNG && config.metadataPolicy != MetadataPolicy.NONE) {
                writeMetadata(project, partial, outputSize, config.metadataPolicy)
            }
            coroutineContext.ensureActive()
            val finished = partial
            // publish가 끝난 뒤에 도착한 취소는 결과를 되돌리지 않는다.
            withContext(NonCancellable) {
                val published = outputStore.publish(finished, config.format.extension)
                partial = null
                EditedMedia(
                    uri = outputStore.uriFor(published),
                    mediaType = MediaType.IMAGE,
                    width = outputSize.width,
                    height = outputSize.height,
                    durationMs = null,
                    mimeType = config.format.mimeType,
                    fileSize = published.length(),
                    warnings = warnings.distinct(),
                )
            }
        } finally {
            partial?.delete()
        }
    }

    /**
     * 여러 사진을 PDF로 내보낸다. [ImageExportConfig.pdf]의 [PdfOptions.combinePages]가 `true`면 하나의 문서,
     * `false`면 사진마다 문서를 만든다. 쪽은 하나씩 렌더링해 바로 파일에 쓰므로 메모리는 한 쪽만큼만 쓴다.
     *
     * 여러 문서를 만들다 하나라도 실패하거나 취소되면 이번에 만든 문서를 모두 지운다.
     *
     * @param onProgress 쪽마다 (`0`부터의 쪽 번호, 전체 쪽 수, 단계)로 내보내기 스레드에서 호출된다.
     * @return 만든 문서들. 묶으면 하나다.
     * @throws FrameKitException [export]와 같은 코드.
     */
    public suspend fun exportPdf(
        pages: List<PdfPage>,
        config: ImageExportConfig,
        target: OutputTarget = OutputTarget.AppFile,
        onProgress: (Int, Int, ImageExportStage) -> Unit = { _, _, _ -> },
    ): List<EditedMedia> = withContext(dispatcher) {
        require(pages.isNotEmpty()) { "At least one page is needed" }
        onProgress(0, pages.size, ImageExportStage.PREPARING)
        checkConfig(config.copy(format = ImageFormat.PDF))
        val options = config.pdf
        val layouts = pages.map { page ->
            val natural = ImageRenderPlanFactory.outputSize(page.project, page.source.metadata, config.maxOutputPixels, config.maxWidth, config.maxHeight)
            PdfLayout.layout(natural, options.pageSize, options.orientation, options.marginMm, options.dpi, config.maxOutputPixels)
        }
        val estimate = layouts.sumOf { it.pixels.pixelCount } + STORAGE_MARGIN_BYTES
        if (outputStore.allocatableBytes() < estimate) throw FrameKitException(EditorErrorCode.INSUFFICIENT_STORAGE, "Not enough free space")

        val groups = if (options.combinePages) listOf(pages.indices.toList()) else pages.indices.map { listOf(it) }
        val published = mutableListOf<File>()
        val results = mutableListOf<EditedMedia>()
        var completed = false
        try {
            for (group in groups) {
                results += writePdf(group, pages, layouts, config, published, onProgress)
            }
            completed = true
            results
        } finally {
            // 사진마다 문서를 만들다 중간에 실패하면 이번 호출의 결과는 하나도 남기지 않는다.
            if (!completed) withContext(NonCancellable) { published.forEach(File::delete) }
        }
    }

    // 쪽 이미지에서 글자를 찾아 PDF 글자 레이어로 바꾼다. 인식은 부가 기능이라 실패해도 저장을 멈추지 않는다.
    private suspend fun recognize(page: Bitmap, warnings: MutableList<ExportWarning>): List<PdfTextLine> {
        val recognizer = textRecognizer ?: return emptyList()
        return try {
            recognizer.recognize(page).mapNotNull { line ->
                val width = line.right - line.left
                val height = line.bottom - line.top
                if (line.text.isBlank() || width <= 0f || height <= 0f) null else PdfTextLine(line.text, line.left, line.top, width, height)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            warnings += ExportWarning.TEXT_RECOGNITION_SKIPPED
            emptyList()
        }
    }

    private suspend fun writePdf(
        indices: List<Int>,
        pages: List<PdfPage>,
        layouts: List<PdfPageLayout>,
        config: ImageExportConfig,
        published: MutableList<File>,
        onProgress: (Int, Int, ImageExportStage) -> Unit,
    ): EditedMedia {
        val warnings = mutableListOf<ExportWarning>()
        val pageTexts = mutableListOf<String>()
        var partial: File? = outputStore.createPartial(ImageFormat.PDF.extension)
        try {
            try {
                checkNotNull(partial).outputStream().use { stream ->
                    PdfWriter(stream).use { writer ->
                        indices.forEach { index ->
                            coroutineContext.ensureActive()
                            onProgress(index, pages.size, ImageExportStage.RENDERING)
                            val page = pages[index]
                            val layout = layouts[index]
                            val rendered = render(page.project, page.source, layout.pixels, config.pdf.backgroundArgb, page.assets, warnings)
                            val text = if (config.pdf.recognizeText) recognize(rendered, warnings) else emptyList()
                            text.joinToString("\n") { it.text }.takeIf { it.isNotBlank() }?.let(pageTexts::add)
                            onProgress(index, pages.size, ImageExportStage.ENCODING)
                            val jpeg = try {
                                java.io.ByteArrayOutputStream().also { buffer ->
                                    if (!rendered.compress(Bitmap.CompressFormat.JPEG, config.quality, buffer)) {
                                        throw FrameKitException(EditorErrorCode.ENCODE_FAILED, "Page could not be encoded")
                                    }
                                }.toByteArray()
                            } finally {
                                rendered.recycle()
                            }
                            writer.addPage(jpeg, layout.pixels.width, layout.pixels.height, layout, text = text)
                        }
                    }
                }
            } catch (error: IOException) {
                throw FrameKitException(storageAwareCode(), "Writing the PDF failed", error)
            } catch (error: OutOfMemoryError) {
                throw FrameKitException(EditorErrorCode.INSUFFICIENT_MEMORY, "PDF page did not fit in memory", error)
            }
            coroutineContext.ensureActive()
            onProgress(indices.last(), pages.size, ImageExportStage.FINALIZING)
            val finished = checkNotNull(partial)
            verifyPdf(finished)
            return withContext(NonCancellable) {
                val file = outputStore.publish(finished, ImageFormat.PDF.extension)
                partial = null
                published += file
                val first = layouts[indices.first()]
                EditedMedia(
                    uri = outputStore.uriFor(file),
                    mediaType = MediaType.DOCUMENT,
                    width = first.pageWidthPt.roundToInt(),
                    height = first.pageHeightPt.roundToInt(),
                    durationMs = null,
                    mimeType = ImageFormat.PDF.mimeType,
                    fileSize = file.length(),
                    warnings = warnings.distinct(),
                    pageCount = indices.size,
                    recognizedText = pageTexts.joinToString("\n\n").take(EditedMedia.MAX_RECOGNIZED_TEXT).ifBlank { null },
                )
            }
        } finally {
            partial?.delete()
        }
    }

    // 디코딩·기하·색·가리기·오버레이까지 그린 출력 크기의 bitmap. 호출한 쪽이 recycle한다.
    private suspend fun render(
        project: ImageProject,
        source: ImageSourceInfo,
        outputSize: PixelSize,
        background: Int?,
        assets: ProjectAssetStore?,
        warnings: MutableList<ExportWarning>,
    ): Bitmap {
        val metadata = source.metadata
        val plan = ImageRenderPlanFactory.create(project, metadata, outputSize)
        val decodePlan = decodePlan(source, project, outputSize)
        val cutoutMask = project.cutout?.let { cutout ->
            assets?.let { CutoutMasks.load(it, cutout.maskAssetId) }
                ?: throw FrameKitException(EditorErrorCode.INVALID_PROJECT, "Background removal mask is missing")
        }
        val colorSpec = project.colorSpec
        val colorBytes = maxOf(
            colorRenderer.workingBytes(outputSize.width, outputSize.height, colorSpec),
            PrivacyRenderer.workingBytes(outputSize.width, outputSize.height, project.privacyMasks),
        )
        val strategy = try {
            renderStrategy(source, decodePlan, outputSize, colorBytes)
        } catch (error: FrameKitException) {
            cutoutMask?.recycle()
            throw error
        }
        val context = coroutineContext
        if (!source.isSrgb) warnings += ExportWarning.COLOR_SPACE_CONVERTED_TO_SRGB
        if (source.hasGainMap) warnings += ExportWarning.HDR_GAIN_MAP_DROPPED
        val rendered = try {
            when (strategy) {
                RenderStrategy.Whole -> {
                    val decoded = decoder.decodeRegion(source, decodePlan.region, decodePlan.sampleSize)
                        ?: decoder.decode(source, decodePlan.fullSampleSize)
                    try {
                        CanvasGeometryRenderer.render(decoded, plan, background, cutoutMask)
                    } finally {
                        decoded.recycle()
                    }
                }
                is RenderStrategy.Banded -> CanvasGeometryRenderer.renderBanded(
                    plan = plan,
                    backgroundArgb = background,
                    bands = strategy.bands,
                    padding = strategy.padding,
                    uprightSize = source.metadata.uprightSize,
                    cutoutMask = cutoutMask,
                ) { band ->
                    context.ensureActive()
                    decoder.decodeRegion(source, band, decodePlan.sampleSize)
                        ?: throw FrameKitException(EditorErrorCode.INSUFFICIENT_MEMORY, "Format cannot be decoded in bands")
                }
            }
        } catch (error: OutOfMemoryError) {
            throw FrameKitException(EditorErrorCode.INSUFFICIENT_MEMORY, "Output bitmap allocation failed", error)
        } finally {
            cutoutMask?.recycle()
        }
        try {
            colorRenderer.apply(rendered, colorSpec, includeCanvasEffects = true)
            PrivacyRenderer.apply(rendered, project.privacyMasks)
            if (project.overlays.isNotEmpty() || project.drawing.isNotEmpty()) {
                overlayRenderer.draw(Canvas(rendered), outputSize, project.overlays, project.drawing)
            }
        } catch (error: OutOfMemoryError) {
            rendered.recycle()
            throw FrameKitException(EditorErrorCode.INSUFFICIENT_MEMORY, "Effects did not fit in memory", error)
        } catch (error: Throwable) {
            rendered.recycle()
            throw error
        }
        return rendered
    }

    private fun checkConfig(config: ImageExportConfig) {
        val validation = config.validate()
        if (validation is ValidationResult.Invalid) {
            throw FrameKitException(EditorErrorCode.INVALID_CONFIGURATION, validation.issues.joinToString { it.path })
        }
        if (config.format == ImageFormat.WEBP_LOSSLESS && Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            throw FrameKitException(EditorErrorCode.UNSUPPORTED_FORMAT, "Lossless WEBP needs API 30")
        }
    }

    private fun verifyPdf(file: File) {
        val head = ByteArray(5)
        val read = runCatching { file.inputStream().use { it.read(head) } }.getOrDefault(-1)
        if (read != 5 || String(head, Charsets.US_ASCII) != "%PDF-") throw FrameKitException(EditorErrorCode.ENCODE_FAILED, "PDF header is missing")
    }

    /** 이 coordinator가 만든 결과 파일을 지운다. 여러 장 저장이 중간에 실패했을 때 쓴다. */
    public fun deleteOutputs(outputs: List<EditedMedia>) {
        outputs.forEach { runCatching { outputStore.delete(it.uri) } }
    }

    /** 중단된 내보내기가 남긴 지 하루가 넘은 partial 파일을 삭제한다. */
    public fun deleteStalePartials() {
        outputStore.deleteStalePartials()
    }

    /**
     * 원본의 어느 부분을 디코딩하고 얼마나 subsampling할지 정한다.
     *
     * crop의 bounding box만 디코딩하므로, 200 MP 사진에서 작은 영역을 내보낼 때 사진 전체가 아니라
     * crop 영역만큼의 메모리만 필요하다.
     */
    private fun decodePlan(source: ImageSourceInfo, project: ImageProject, outputSize: PixelSize): DecodePlan {
        val upright = source.metadata.uprightSize
        val frame = GeometryFrame(upright, project.geometry)
        val cropSize = frame.cropPixelSize(project.geometry.crop)
        val scale = minOf(1.0, outputSize.width / cropSize.width, outputSize.height / cropSize.height)

        val toUpright = frame.uprightToBounds.inverted()
        val corners = project.geometry.crop.corners().map { toUpright.map(it.x * frame.bounds.width, it.y * frame.bounds.height) }
        // 보간에 필요한 가장자리 픽셀을 포함하도록 2px 여유를 둔다.
        val region = PixelRect(
            (floor(corners.minOf { it.x }).toInt() - REGION_PADDING_PX).coerceIn(0, upright.width),
            (floor(corners.minOf { it.y }).toInt() - REGION_PADDING_PX).coerceIn(0, upright.height),
            (ceil(corners.maxOf { it.x }).toInt() + REGION_PADDING_PX).coerceIn(0, upright.width),
            (ceil(corners.maxOf { it.y }).toInt() + REGION_PADDING_PX).coerceIn(0, upright.height),
        )
        return DecodePlan(
            region = region,
            sampleSize = SampleSize.forMinimumSize(region.size, region.size.scaledUp(scale)),
            fullSampleSize = SampleSize.forMinimumSize(upright, upright.scaledUp(scale)),
        )
    }

    /**
     * crop 영역이 메모리 예산에 들어가면 한 번에 디코딩하고, 아니면 가로 띠 단위로 디코딩해
     * 출력과 띠 하나만 함께 메모리에 올라오게 한다.
     *
     * @throws FrameKitException 출력 bitmap조차 예산에 들어가지 않으면 `INSUFFICIENT_MEMORY`.
     */
    private fun renderStrategy(source: ImageSourceInfo, plan: DecodePlan, outputSize: PixelSize, colorBytes: Long): RenderStrategy {
        // 보정은 geometry 렌더가 끝난 뒤 실행되므로 디코딩 메모리와 동시에 잡히지 않는다. 둘 중 큰 쪽이 기준이다.
        val outputBytes = ImageMemoryBudget.argbBytes(outputSize.width, outputSize.height)
        if (outputBytes + colorBytes > memoryBudgetBytes) {
            throw FrameKitException(EditorErrorCode.INSUFFICIENT_MEMORY, "Export needs ${outputBytes + colorBytes} bytes")
        }
        val available = memoryBudgetBytes - outputBytes
        // EXIF 방향을 적용할 때 디코딩 결과 사본을 하나 더 만든다.
        val copies = if (source.orientation != ExifOrientation.NORMAL) 2L else 1L
        val region = plan.region
        val sample = plan.sampleSize
        val bytesPerRow = ImageMemoryBudget.argbBytes(region.width / sample + 1, 1) * copies
        val wholeBytes = bytesPerRow * (region.height / sample + 1)
        if (wholeBytes <= available) return RenderStrategy.Whole

        val padding = REGION_PADDING_PX * sample + REGION_PADDING_PX
        val rowsPerBand = (available / bytesPerRow) * sample - 2 * padding
        if (rowsPerBand < MIN_BAND_ROWS * sample) {
            throw FrameKitException(EditorErrorCode.INSUFFICIENT_MEMORY, "Export needs ${outputBytes + bytesPerRow * MIN_BAND_ROWS} bytes")
        }
        val step = (rowsPerBand / sample).toInt() * sample
        val bands = (region.top until region.bottom step step).map { top ->
            PixelRect(region.left, top, region.right, minOf(top + step, region.bottom))
        }
        return RenderStrategy.Banded(bands, padding)
    }

    private sealed interface RenderStrategy {
        data object Whole : RenderStrategy
        data class Banded(val bands: List<PixelRect>, val padding: Int) : RenderStrategy
    }

    private fun PixelSize.scaledUp(scale: Double) = PixelSize(
        ceil(width * scale).toInt().coerceAtLeast(1),
        ceil(height * scale).toInt().coerceAtLeast(1),
    )

    private data class DecodePlan(val region: PixelRect, val sampleSize: Int, val fullSampleSize: Int)

    private fun checkStorage(outputSize: PixelSize, format: ImageFormat) {
        // PNG는 압축되지 않는 최악의 경우를, JPEG는 고품질 평균보다 넉넉한 픽셀당 1바이트를 잡는다.
        val bytesPerPixel = if (format == ImageFormat.PNG || format == ImageFormat.WEBP_LOSSLESS) 4L else 1L
        val estimate = outputSize.pixelCount * bytesPerPixel + STORAGE_MARGIN_BYTES
        if (outputStore.allocatableBytes() < estimate) {
            throw FrameKitException(EditorErrorCode.INSUFFICIENT_STORAGE, "Not enough free space")
        }
    }

    private fun encode(bitmap: Bitmap, config: ImageExportConfig, file: File) {
        val compressFormat = compressFormatOf(config.format)
        val success = try {
            file.outputStream().buffered().use { bitmap.compress(compressFormat, config.quality, it) }
        } catch (error: IOException) {
            throw FrameKitException(storageAwareCode(), "Writing output failed", error)
        }
        if (!success) throw FrameKitException(EditorErrorCode.ENCODE_FAILED, "Encoder rejected the bitmap")
    }

    private fun compressFormatOf(format: ImageFormat): Bitmap.CompressFormat = when (format) {
        ImageFormat.JPEG -> Bitmap.CompressFormat.JPEG
        ImageFormat.PNG -> Bitmap.CompressFormat.PNG
        ImageFormat.WEBP_LOSSY -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Bitmap.CompressFormat.WEBP_LOSSY
        } else {
            @Suppress("DEPRECATION")
            Bitmap.CompressFormat.WEBP
        }
        ImageFormat.WEBP_LOSSLESS -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Bitmap.CompressFormat.WEBP_LOSSLESS
        } else {
            throw FrameKitException(EditorErrorCode.UNSUPPORTED_FORMAT, "Lossless WEBP needs API 30")
        }
        ImageFormat.PDF -> Bitmap.CompressFormat.JPEG
    }

    private fun verify(file: File, expected: PixelSize, format: ImageFormat) {
        if (file.length() <= 0L) throw FrameKitException(EditorErrorCode.ENCODE_FAILED, "Output is empty")
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        if (options.outWidth != expected.width || options.outHeight != expected.height ||
            options.outMimeType?.lowercase() != format.mimeType
        ) {
            throw FrameKitException(EditorErrorCode.ENCODE_FAILED, "Output header does not match the request")
        }
    }

    private fun writeMetadata(project: ImageProject, file: File, outputSize: PixelSize, policy: MetadataPolicy) {
        try {
            exifWriter.write(project.source, file, outputSize, includeAll = policy == MetadataPolicy.ALL)
        } catch (error: IOException) {
            throw FrameKitException(storageAwareCode(), "Writing metadata failed", error)
        }
    }

    private fun storageAwareCode(): EditorErrorCode {
        val free = runCatching { outputStore.allocatableBytes() }.getOrDefault(Long.MAX_VALUE)
        return if (free < STORAGE_MARGIN_BYTES) EditorErrorCode.INSUFFICIENT_STORAGE else EditorErrorCode.OUTPUT_WRITE_FAILED
    }

    private companion object {
        const val STORAGE_MARGIN_BYTES = 1L * 1024 * 1024
        const val REGION_PADDING_PX = 2
        const val MIN_BAND_ROWS = 64
    }
}
