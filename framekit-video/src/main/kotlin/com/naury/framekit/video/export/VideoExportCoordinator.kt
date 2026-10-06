// Media3의 composition·effect·Transformer API는 @UnstableApi다. FrameKit은 이 모듈 안의 어댑터에서만 쓰고
// 버전을 고정(1.11.1)해 API 변경을 빌드 단계에서 확인한다.
@file:OptIn(UnstableApi::class)

package com.naury.framekit.video.export

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.media3.common.MimeTypes
import androidx.media3.transformer.Composition
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.TransformationRequest
import androidx.media3.transformer.Transformer
import com.naury.framekit.android.output.AppFileOutputStore
import com.naury.framekit.android.output.OutputTarget
import com.naury.framekit.android.result.EditedMedia
import com.naury.framekit.android.result.EditorErrorCode
import com.naury.framekit.android.result.ExportWarning
import com.naury.framekit.android.result.FrameKitException
import com.naury.framekit.android.source.SourceLocation
import com.naury.framekit.core.model.MediaType
import com.naury.framekit.core.model.SourceId
import com.naury.framekit.core.validation.ValidationResult
import com.naury.framekit.core.validation.VideoProjectValidator
import com.naury.framekit.core.video.TimelineTimeMapper
import com.naury.framekit.core.video.VideoProject
import com.naury.framekit.video.VideoPlanFactory
import com.naury.framekit.video.media3.Media3CompositionFactory
import com.naury.framekit.video.source.VideoSourceInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Progress of a video export. */
public sealed interface VideoExportProgress {
    public data object Preparing : VideoExportProgress

    /** @property percent `0..100`, or `null` when Media3 cannot estimate progress. */
    public data class Encoding(val percent: Int?) : VideoExportProgress
    public data object Finalizing : VideoExportProgress
}

/**
 * Exports a [VideoProject] to MP4 (H.264/AAC) with Media3 Transformer.
 *
 * Transformer is created, started, polled and cancelled on the main looper; the encoding itself runs
 * on Media3's own threads. Cancelling the coroutine cancels the Transformer, waits for it to stop and
 * deletes the partial file. The output is checked (video track and duration) before it is published.
 */
public class VideoExportCoordinator(
    context: Context,
    private val outputStore: AppFileOutputStore = AppFileOutputStore(context),
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val appContext = context.applicationContext

    /**
     * @param minClipOutputDurationUs shortest allowed clip, as in the editor config.
     * @throws FrameKitException with `INVALID_PROJECT`, `INVALID_CONFIGURATION`, `INSUFFICIENT_STORAGE`,
     *   `DECODE_FAILED`, `UNSUPPORTED_FORMAT`, `ENCODE_FAILED`, `SOURCE_UNAVAILABLE` or
     *   `OUTPUT_WRITE_FAILED`.
     */
    public suspend fun export(
        project: VideoProject,
        sources: Map<SourceId, VideoSourceInfo>,
        locations: Map<SourceId, SourceLocation>,
        config: VideoExportConfig = VideoExportConfig(),
        target: OutputTarget = OutputTarget.AppFile,
        minClipOutputDurationUs: Long = 0L,
        onProgress: (VideoExportProgress) -> Unit = {},
    ): EditedMedia {
        onProgress(VideoExportProgress.Preparing)
        val configCheck = config.validate()
        if (configCheck is ValidationResult.Invalid) {
            throw FrameKitException(EditorErrorCode.INVALID_CONFIGURATION, configCheck.issues.joinToString { it.path })
        }
        val projectCheck = VideoProjectValidator.validate(project, sources.mapValues { it.value.metadata }, minClipOutputDurationUs)
        if (projectCheck is ValidationResult.Invalid) {
            throw FrameKitException(EditorErrorCode.INVALID_PROJECT, projectCheck.issues.joinToString { "${it.path}: ${it.code}" })
        }
        val plan = VideoPlanFactory.create(project, sources, locations, config.maxShortSide)
        val durationUs = TimelineTimeMapper.durationUs(project.timeline)
        checkStorage(plan.canvasSize.width, plan.canvasSize.height, durationUs, config.maxFrameRate)
        val composition = Media3CompositionFactory.create(plan, config.maxFrameRate)

        val warnings = mutableListOf<ExportWarning>()
        if (plan.clips.any { it.source.isHdr }) warnings += ExportWarning.HDR_CONVERTED_TO_SDR
        if (plan.clips.any { (it.source.frameRate ?: 0f) * it.clip.speed > config.maxFrameRate + 0.5f }) warnings += ExportWarning.FRAME_RATE_REDUCED

        val partial = withContext(ioDispatcher) { outputStore.createPartial(EXTENSION) }
        var published = false
        try {
            withContext(mainDispatcher) { runTransformer(composition, partial, config, warnings, onProgress) }
            onProgress(VideoExportProgress.Finalizing)
            return withContext(ioDispatcher + NonCancellable) {
                val output = verify(partial)
                val file = outputStore.publish(partial, EXTENSION)
                published = true
                EditedMedia(
                    uri = outputStore.uriFor(file),
                    mediaType = MediaType.VIDEO,
                    width = output.width,
                    height = output.height,
                    durationMs = output.durationUs / 1000,
                    mimeType = MimeTypes.VIDEO_MP4,
                    fileSize = file.length(),
                    warnings = warnings.distinct(),
                )
            }
        } finally {
            if (!published) withContext(NonCancellable) { partial.delete() }
        }
    }

    private suspend fun runTransformer(
        composition: Composition,
        output: File,
        config: VideoExportConfig,
        warnings: MutableList<ExportWarning>,
        onProgress: (VideoExportProgress) -> Unit,
    ): ExportResult = coroutineScope {
        val transformerHolder = arrayOfNulls<Transformer>(1)
        val poller = launch {
            val holder = ProgressHolder()
            while (isActive) {
                val transformer = transformerHolder[0]
                if (transformer != null) {
                    val state = transformer.getProgress(holder)
                    onProgress(VideoExportProgress.Encoding(if (state == Transformer.PROGRESS_STATE_AVAILABLE) holder.progress else null))
                }
                delay(PROGRESS_INTERVAL_MS)
            }
        }
        try {
            suspendCancellableCoroutine<ExportResult> { continuation ->
                val transformer = Transformer.Builder(appContext)
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .setAudioMimeType(MimeTypes.AUDIO_AAC)
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            if (continuation.isActive) continuation.resume(exportResult)
                        }

                        override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                            if (continuation.isActive) continuation.resumeWithException(VideoExportErrors.map(exportException))
                        }

                        override fun onFallbackApplied(
                            composition: Composition,
                            originalTransformationRequest: TransformationRequest,
                            fallbackTransformationRequest: TransformationRequest,
                        ) {
                            warnings += ExportWarning.ENCODER_FALLBACK_APPLIED
                            if (!config.allowFallback && continuation.isActive) {
                                transformerHolder[0]?.cancel()
                                continuation.resumeWithException(FrameKitException(EditorErrorCode.UNSUPPORTED_OPERATION, "Encoder fallback not allowed"))
                            }
                        }
                    })
                    .build()
                transformerHolder[0] = transformer
                transformer.start(composition, output.absolutePath)
            }
        } catch (cancelled: CancellationException) {
            // 취소는 main looper에서 Transformer를 멈춘 뒤에 전파한다. 그래야 호출 측이 partial 파일을 지울 때
            // muxer가 이미 닫혀 있다.
            withContext(NonCancellable) { transformerHolder[0]?.cancel() }
            throw cancelled
        } finally {
            poller.cancel()
        }
    }

    private class OutputInfo(val width: Int, val height: Int, val durationUs: Long)

    // 세로 영상은 가로로 인코딩하고 회전 메타데이터를 붙이므로, 호스트에는 회전을 반영한 크기를 돌려준다.
    private fun verify(file: File): OutputInfo {
        if (file.length() <= 0L) throw FrameKitException(EditorErrorCode.ENCODE_FAILED, "Output is empty")
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            var video: MediaFormat? = null
            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                if (format.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) video = format
            }
            // AAC는 인코더 지연·패딩 때문에 영상보다 수십 ms 길 수 있다. 결과 길이는 영상 트랙 기준이다.
            val duration = video?.takeIf { it.containsKey(MediaFormat.KEY_DURATION) }?.getLong(MediaFormat.KEY_DURATION) ?: 0L
            if (video == null || duration <= 0L) throw FrameKitException(EditorErrorCode.ENCODE_FAILED, "Output has no playable video")
            val rotation = if (video.containsKey(MediaFormat.KEY_ROTATION)) Math.floorMod(video.getInteger(MediaFormat.KEY_ROTATION), 360) else 0
            val width = video.getInteger(MediaFormat.KEY_WIDTH)
            val height = video.getInteger(MediaFormat.KEY_HEIGHT)
            return if (rotation == 90 || rotation == 270) OutputInfo(height, width, duration) else OutputInfo(width, height, duration)
        } finally {
            extractor.release()
        }
    }

    // 비트레이트를 넉넉히 잡은 추정치(화소 × fps × 0.15 bit)와 여유 공간을 비교한다.
    private fun checkStorage(width: Int, height: Int, durationUs: Long, frameRate: Int) {
        val bitsPerSecond = width.toLong() * height * frameRate * BITS_PER_PIXEL_FRAME + AUDIO_BITS_PER_SECOND
        val estimate = (bitsPerSecond / 8.0 * durationUs / 1_000_000).toLong() + STORAGE_MARGIN_BYTES
        if (outputStore.allocatableBytes() < estimate) throw FrameKitException(EditorErrorCode.INSUFFICIENT_STORAGE, "Not enough space")
    }

    /** Removes partial files of interrupted exports; see [AppFileOutputStore.deleteStalePartials]. */
    public fun deleteStalePartials() {
        outputStore.deleteStalePartials()
    }

    private companion object {
        const val EXTENSION = "mp4"
        const val PROGRESS_INTERVAL_MS = 200L
        const val BITS_PER_PIXEL_FRAME = 0.15
        const val AUDIO_BITS_PER_SECOND = 192_000L
        const val STORAGE_MARGIN_BYTES = 5L * 1024 * 1024
    }
}

/** Maps Media3 export errors to FrameKit codes. */
internal object VideoExportErrors {
    fun map(error: ExportException): FrameKitException {
        val code = when (error.errorCode) {
            ExportException.ERROR_CODE_IO_FILE_NOT_FOUND -> EditorErrorCode.SOURCE_UNAVAILABLE
            ExportException.ERROR_CODE_IO_NO_PERMISSION -> EditorErrorCode.PERMISSION_DENIED
            in IO_RANGE -> EditorErrorCode.SOURCE_UNAVAILABLE
            ExportException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED -> EditorErrorCode.UNSUPPORTED_FORMAT
            ExportException.ERROR_CODE_DECODER_INIT_FAILED, ExportException.ERROR_CODE_DECODING_FAILED -> EditorErrorCode.DECODE_FAILED
            ExportException.ERROR_CODE_ENCODING_FORMAT_UNSUPPORTED -> EditorErrorCode.UNSUPPORTED_OPERATION
            ExportException.ERROR_CODE_ENCODER_INIT_FAILED, ExportException.ERROR_CODE_ENCODING_FAILED -> EditorErrorCode.ENCODE_FAILED
            ExportException.ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED -> EditorErrorCode.UNSUPPORTED_OPERATION
            ExportException.ERROR_CODE_MUXING_FAILED, ExportException.ERROR_CODE_MUXING_TIMEOUT -> EditorErrorCode.OUTPUT_WRITE_FAILED
            else -> EditorErrorCode.UNKNOWN
        }
        return FrameKitException(code, "Export failed: ${error.errorCodeName}", error)
    }

    private val IO_RANGE = 2000..2999
}
