package com.naury.framekit.image.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.naury.framekit.android.output.AppFileOutputStore
import com.naury.framekit.android.output.OutputTarget
import com.naury.framekit.android.result.EditedMedia
import com.naury.framekit.android.result.EditorErrorCode
import com.naury.framekit.android.result.ExportWarning
import com.naury.framekit.android.result.FrameKitException
import com.naury.framekit.android.source.SourceResolver
import com.naury.framekit.core.geometry.ExifOrientation
import com.naury.framekit.core.geometry.GeometryFrame
import com.naury.framekit.core.model.ImageProject
import com.naury.framekit.core.model.MediaType
import com.naury.framekit.core.model.PixelSize
import com.naury.framekit.core.validation.ValidationResult
import com.naury.framekit.image.decode.BitmapDecoder
import com.naury.framekit.image.decode.ImageSourceInfo
import com.naury.framekit.image.decode.SampleSize
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
) {
    public constructor(context: Context, resolver: SourceResolver) : this(
        resolver = resolver,
        outputStore = AppFileOutputStore(context),
        memoryBudgetBytes = ImageMemoryBudget.bytes(context),
    )

    private val decoder = BitmapDecoder(resolver)
    private val exifWriter = SafeExifWriter(resolver)

    /**
     * Runs the export on the coordinator's dispatcher.
     *
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
        onStage: (ImageExportStage) -> Unit = {},
    ): EditedMedia = withContext(dispatcher) {
        onStage(ImageExportStage.PREPARING)
        val validation = config.validate()
        if (validation is ValidationResult.Invalid) {
            throw FrameKitException(EditorErrorCode.INVALID_CONFIGURATION, validation.issues.joinToString { it.path })
        }
        val metadata = source.metadata
        val outputSize = ImageRenderPlanFactory.outputSize(project, metadata, config.maxOutputPixels, config.maxWidth, config.maxHeight)
        val plan = ImageRenderPlanFactory.create(project, metadata, outputSize)
        val sampleSize = decodeSampleSize(source, project, outputSize)
        checkMemory(source, sampleSize, outputSize)
        checkStorage(outputSize, config.format)

        val warnings = buildList { if (!source.isSrgb) add(ExportWarning.COLOR_SPACE_CONVERTED_TO_SRGB) }.toMutableList()
        var partial: File? = null
        try {
            coroutineContext.ensureActive()
            onStage(ImageExportStage.RENDERING)
            val decoded = decoder.decode(source, sampleSize)
            if (decoded.hasGainMap) warnings += ExportWarning.HDR_GAIN_MAP_DROPPED
            val background = if (config.format == ImageFormat.JPEG) config.jpegBackgroundArgb else null
            val rendered = try {
                CanvasGeometryRenderer.render(decoded, plan, background)
            } catch (error: OutOfMemoryError) {
                throw FrameKitException(EditorErrorCode.INSUFFICIENT_MEMORY, "Output bitmap allocation failed", error)
            } finally {
                decoded.recycle()
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
            if (config.format == ImageFormat.JPEG && config.metadataPolicy == MetadataPolicy.SAFE) {
                writeMetadata(project, partial, outputSize)
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

    private fun decodeSampleSize(source: ImageSourceInfo, project: ImageProject, outputSize: PixelSize): Int {
        val frame = GeometryFrame(source.metadata.uprightSize, project.geometry)
        val cropSize = frame.cropPixelSize(project.geometry.crop)
        val scale = minOf(1.0, outputSize.width / cropSize.width, outputSize.height / cropSize.height)
        val upright = source.metadata.uprightSize
        val needed = PixelSize(
            ceil(upright.width * scale).toInt().coerceAtLeast(1),
            ceil(upright.height * scale).toInt().coerceAtLeast(1),
        )
        // 디코더는 저장된(encoded) 축 기준으로 축소하므로 비율이 같은 upright 크기로 계산해도 결과가 같다.
        return SampleSize.forMinimumSize(upright, needed)
    }

    private fun checkMemory(source: ImageSourceInfo, sampleSize: Int, outputSize: PixelSize) {
        val upright = source.metadata.uprightSize
        val decodedBytes = ImageMemoryBudget.argbBytes(upright.width / sampleSize, upright.height / sampleSize)
        // API 26/27 경로는 EXIF 회전을 위해 디코딩 사본을 하나 더 만든다.
        val orientationCopy = if (source.orientation != ExifOrientation.NORMAL) decodedBytes else 0L
        val required = decodedBytes + orientationCopy + ImageMemoryBudget.argbBytes(outputSize.width, outputSize.height)
        if (required > memoryBudgetBytes) {
            throw FrameKitException(EditorErrorCode.INSUFFICIENT_MEMORY, "Export needs $required bytes")
        }
    }

    private fun checkStorage(outputSize: PixelSize, format: ImageFormat) {
        // PNG는 압축되지 않는 최악의 경우를, JPEG는 고품질 평균보다 넉넉한 픽셀당 1바이트를 잡는다.
        val bytesPerPixel = if (format == ImageFormat.PNG) 4L else 1L
        val estimate = outputSize.pixelCount * bytesPerPixel + STORAGE_MARGIN_BYTES
        if (outputStore.allocatableBytes() < estimate) {
            throw FrameKitException(EditorErrorCode.INSUFFICIENT_STORAGE, "Not enough free space")
        }
    }

    private fun encode(bitmap: Bitmap, config: ImageExportConfig, file: File) {
        val compressFormat = when (config.format) {
            ImageFormat.JPEG -> Bitmap.CompressFormat.JPEG
            ImageFormat.PNG -> Bitmap.CompressFormat.PNG
        }
        val success = try {
            file.outputStream().buffered().use { bitmap.compress(compressFormat, config.quality, it) }
        } catch (error: IOException) {
            throw FrameKitException(storageAwareCode(), "Writing output failed", error)
        }
        if (!success) throw FrameKitException(EditorErrorCode.ENCODE_FAILED, "Encoder rejected the bitmap")
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

    private fun writeMetadata(project: ImageProject, file: File, outputSize: PixelSize) {
        try {
            exifWriter.write(project.source, file, outputSize)
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
    }
}
