package com.naury.framekit.image.export

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
 * Exports one immutable [ImageProject] snapshot to a file.
 *
 * The original is never modified. Output is written to a hidden partial file, verified, and only then
 * published. Any failure or cancellation before publishing deletes the partial file; once the file is
 * published the export counts as completed even if cancellation arrives late.
 */
public class ImageExportCoordinator(
    private val resolver: SourceResolver,
    private val outputStore: AppFileOutputStore,
    private val memoryBudgetBytes: Long,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    contentResolver: ContentResolver? = null,
    private val colorRenderer: ColorEffectRenderer = CpuColorEffectRenderer,
) {
    public constructor(context: Context, resolver: SourceResolver) : this(
        resolver = resolver,
        outputStore = AppFileOutputStore(context),
        memoryBudgetBytes = ImageMemoryBudget.bytes(context),
        contentResolver = context.applicationContext.contentResolver,
    )

    private val decoder = BitmapDecoder(resolver, contentResolver)
    private val overlayRenderer = OverlayRenderer()
    private val exifWriter = SafeExifWriter(resolver)

    /**
     * Runs the export on the coordinator's dispatcher.
     *
     * @param assets store that holds project assets such as the background-removal mask; required
     *   when the project has a cutout.
     * @param onStage called on the export thread when a new stage starts.
     * @throws FrameKitException with `INVALID_CONFIGURATION`, `INVALID_PROJECT`, `INSUFFICIENT_MEMORY`,
     *   `INSUFFICIENT_STORAGE`, `DECODE_FAILED`, `ENCODE_FAILED` or `OUTPUT_WRITE_FAILED`.
     * @throws kotlinx.coroutines.CancellationException when the calling coroutine is cancelled before
     *   the output is published.
     */
    public suspend fun export(
        project: ImageProject,
        source: ImageSourceInfo,
        config: ImageExportConfig,
        target: OutputTarget = OutputTarget.AppFile,
        assets: ProjectAssetStore? = null,
        onStage: (ImageExportStage) -> Unit = {},
    ): EditedMedia = withContext(dispatcher) {
        onStage(ImageExportStage.PREPARING)
        val validation = config.validate()
        if (validation is ValidationResult.Invalid) {
            throw FrameKitException(EditorErrorCode.INVALID_CONFIGURATION, validation.issues.joinToString { it.path })
        }
        if (config.format == ImageFormat.WEBP_LOSSLESS && Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            throw FrameKitException(EditorErrorCode.UNSUPPORTED_FORMAT, "Lossless WEBP needs API 30")
        }
        val metadata = source.metadata
        val outputSize = ImageRenderPlanFactory.outputSize(project, metadata, config.maxOutputPixels, config.maxWidth, config.maxHeight)
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
        val strategy = renderStrategy(source, decodePlan, outputSize, colorBytes)
        checkStorage(outputSize, config.format)

        val warnings = buildList { if (!source.isSrgb) add(ExportWarning.COLOR_SPACE_CONVERTED_TO_SRGB) }.toMutableList()
        var partial: File? = null
        try {
            coroutineContext.ensureActive()
            onStage(ImageExportStage.RENDERING)
            if (source.hasGainMap) warnings += ExportWarning.HDR_GAIN_MAP_DROPPED
            val background = if (config.format.supportsAlpha) null else config.jpegBackgroundArgb
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
                        coroutineContext.ensureActive()
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
            } catch (error: OutOfMemoryError) {
                rendered.recycle()
                throw FrameKitException(EditorErrorCode.INSUFFICIENT_MEMORY, "Color effects did not fit in memory", error)
            }
            try {
                PrivacyRenderer.apply(rendered, project.privacyMasks)
            } catch (error: OutOfMemoryError) {
                rendered.recycle()
                throw FrameKitException(EditorErrorCode.INSUFFICIENT_MEMORY, "Privacy masks did not fit in memory", error)
            }
            if (project.overlays.isNotEmpty() || project.drawing.isNotEmpty()) {
                overlayRenderer.draw(Canvas(rendered), outputSize, project.overlays, project.drawing)
            }

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
                    warnings = warnings.toList(),
                )
            }
        } finally {
            partial?.delete()
        }
    }

    /** Removes partial files that an interrupted export left behind more than a day ago. */
    public fun deleteStalePartials() {
        outputStore.deleteStalePartials()
    }

    /**
     * Decides which part of the source to decode and how much to subsample it.
     *
     * Only the bounding box of the crop is decoded, so exporting a small crop from a 200 MP photo needs
     * memory for the crop rather than for the whole picture.
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
     * Decodes the crop region in one piece when it fits the memory budget, otherwise in horizontal
     * bands so only the output and one band are in memory together.
     *
     * @throws FrameKitException with `INSUFFICIENT_MEMORY` when even the output bitmap does not fit.
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
