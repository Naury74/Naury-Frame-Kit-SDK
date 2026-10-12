package com.naury.framekit.motion.export

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.naury.framekit.video.media3.AudioMixTrack
import com.naury.framekit.video.media3.AudioTrackMixer
import com.naury.framekit.android.output.AppFileOutputStore
import com.naury.framekit.android.result.EditedMedia
import com.naury.framekit.android.result.EditorErrorCode
import com.naury.framekit.android.result.FrameKitException
import com.naury.framekit.core.model.MediaType
import com.naury.framekit.image.effect.ColorEffectRenderer
import com.naury.framekit.motion.codec.GifEncoder
import com.naury.framekit.motion.codec.Mp4FrameEncoder
import com.naury.framekit.motion.codec.WebpAnimationEncoder
import com.naury.framekit.motion.model.MotionProject
import com.naury.framekit.motion.render.MotionMedia
import com.naury.framekit.motion.render.MotionRenderer
import com.naury.framekit.motion.render.MotionTimeline
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import kotlin.coroutines.coroutineContext

/** 저장할 때 실제로 쓰는 값. */
public data class MotionExportSettings(
    val format: MotionFormat,
    val longEdge: Int,
    val frameRate: Int,
    val webpQuality: Int = 80,
    val loopCount: Int = 0,
)

/**
 * 움직이는 이미지를 MP4·GIF·움직이는 WebP로 저장한다. 프레임을 하나씩 그려 바로 인코더에 넘기므로 전체 프레임을 메모리에 두지 않는다.
 *
 * 영상 인코더(EGL)가 스레드에 묶이므로 저장은 전용 스레드 하나에서 처음부터 끝까지 돈다. 색 보정 렌더러도 그 스레드에서 만들어 쓰고 버린다.
 *
 * @param context MP4에 소리를 섞을 때 쓴다. `null`이면 소리 없이 저장한다.
 * @param colorRendererFactory 저장 스레드에서 부르는 색 보정 렌더러 생성기.
 */
public class MotionExporter(
    private val outputStore: AppFileOutputStore,
    private val context: Context? = null,
    private val colorRendererFactory: () -> ColorEffectRenderer,
) {

    /**
     * @param media 저장 크기에 맞춰 사진을 푸는 원본. 미리보기용과 따로 둔다.
     * @param audioUri 소리 원본 id를 읽을 수 있는 Uri로 바꾼다. MP4이고 [MotionProject.audio]가 있으면 소리를 섞는다(생성자에 context가 있어야 한다).
     * @param onProgress `0..1` 진행률. 저장 스레드에서 불린다.
     * @throws FrameKitException 인코더를 만들 수 없으면 `ENCODE_FAILED`, 메모리가 모자라면 `INSUFFICIENT_MEMORY`,
     *   파일을 쓰지 못하면 `OUTPUT_WRITE_FAILED`.
     */
    public suspend fun export(
        project: MotionProject,
        media: MotionMedia,
        settings: MotionExportSettings,
        onProgress: (Float) -> Unit,
        audioUri: (String) -> Uri? = { null },
    ): EditedMedia {
        val tracks = if (settings.format == MotionFormat.MP4 && context != null) {
            project.audio.mapNotNull { audio -> audioUri(audio.sourceId)?.let { AudioMixTrack(it, audio.toAudioClip(), audio.sourceDurationMs * 1000) } }
        } else {
            emptyList()
        }
        // 소리를 섞으면 영상 만들기가 전체의 80%, 섞기가 나머지다.
        val videoShare = if (tracks.isEmpty()) 1f else VIDEO_SHARE
        val executor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "framekit-motion-export") }
        val dispatcher = executor.asCoroutineDispatcher()
        val video = try {
            withContext(dispatcher) { exportOnThread(project, media, settings, keep = tracks.isNotEmpty()) { onProgress(it * videoShare) } }
        } finally {
            dispatcher.close()
        }
        if (tracks.isEmpty()) return video.media
        val partial = outputStore.createPartial(settings.format.extension)
        try {
            AudioTrackMixer.mix(checkNotNull(context), video.file, (video.media.durationMs ?: 0L) * 1000, tracks, partial) { onProgress(videoShare + it * (1 - videoShare)) }
            val published = try {
                outputStore.publish(partial, settings.format.extension)
            } catch (error: IOException) {
                throw FrameKitException(EditorErrorCode.OUTPUT_WRITE_FAILED, "Output could not be published", error)
            }
            return video.media.copy(uri = outputStore.uriFor(published), fileSize = published.length())
        } finally {
            video.file.delete()
            if (partial.exists()) partial.delete()
        }
    }

    private class VideoOutput(val media: EditedMedia, val file: File)

    // keep이면 결과를 공개하지 않고 임시 파일로 돌려준다(소리를 섞은 뒤 공개한다).
    private suspend fun exportOnThread(project: MotionProject, media: MotionMedia, settings: MotionExportSettings, keep: Boolean, onProgress: (Float) -> Unit): VideoOutput {
        val colorRenderer = colorRendererFactory()
        val renderer = MotionRenderer(colorRenderer)
        var size = renderer.outputSize(project, media, settings.longEdge)
        val frames = MotionTimeline.outputFrames(project, media, settings.frameRate)
        val totalMs = frames.sumOf { it.durationMs }
        val partial = outputStore.createPartial(settings.format.extension)
        var frame: Bitmap? = null
        try {
            when (settings.format) {
                MotionFormat.GIF -> partial.outputStream().buffered().use { stream ->
                    GifEncoder(stream, size.width, size.height, settings.loopCount).use { encoder ->
                        frames.forEachIndexed { index, output ->
                            coroutineContext.ensureActive()
                            frame = renderer.render(project, media, output.timeMs, size.width, size.height, frame)
                            encoder.addFrame(checkNotNull(frame), output.durationMs)
                            onProgress((index + 1f) / frames.size)
                        }
                    }
                }
                MotionFormat.WEBP -> WebpAnimationEncoder(partial, size.width, size.height, settings.webpQuality, lossless = false, loopCount = settings.loopCount).use { encoder ->
                    frames.forEachIndexed { index, output ->
                        coroutineContext.ensureActive()
                        frame = renderer.render(project, media, output.timeMs, size.width, size.height, frame)
                        encoder.addFrame(checkNotNull(frame), output.durationMs)
                        onProgress((index + 1f) / frames.size)
                    }
                }
                MotionFormat.MP4 -> {
                    val encoder = createMp4Encoder(partial, size.width, size.height, settings.frameRate)
                    size = com.naury.framekit.core.model.PixelSize(encoder.width, encoder.height)
                    try {
                        frames.forEachIndexed { index, output ->
                            coroutineContext.ensureActive()
                            frame = renderer.render(project, media, output.timeMs, size.width, size.height, frame)
                            encoder.addFrame(checkNotNull(frame), output.timeMs * 1000)
                            onProgress((index + 1f) / frames.size)
                        }
                        encoder.finish(frames.last().durationMs * 1000)
                    } finally {
                        encoder.close()
                    }
                }
            }
            if (keep) {
                val temp = outputStore.createPartial(settings.format.extension)
                if (!partial.renameTo(temp)) throw FrameKitException(EditorErrorCode.OUTPUT_WRITE_FAILED, "Temporary video could not be kept")
                return VideoOutput(
                    EditedMedia(Uri.fromFile(temp), MediaType.VIDEO, size.width, size.height, totalMs, settings.format.mimeType, temp.length()),
                    temp,
                )
            }
            val published = try {
                outputStore.publish(partial, settings.format.extension)
            } catch (error: IOException) {
                throw FrameKitException(EditorErrorCode.OUTPUT_WRITE_FAILED, "Output could not be published", error)
            }
            return VideoOutput(EditedMedia(
                uri = outputStore.uriFor(published),
                mediaType = if (settings.format == MotionFormat.MP4) MediaType.VIDEO else MediaType.ANIMATED_IMAGE,
                width = size.width,
                height = size.height,
                durationMs = totalMs,
                mimeType = settings.format.mimeType,
                fileSize = published.length(),
            ), published)
        } catch (error: OutOfMemoryError) {
            throw FrameKitException(EditorErrorCode.INSUFFICIENT_MEMORY, "Not enough memory to export", error)
        } catch (error: IOException) {
            throw FrameKitException(EditorErrorCode.ENCODE_FAILED, "Motion export failed", error)
        } finally {
            frame?.recycle()
            colorRenderer.release()
            if (partial.exists()) partial.delete()
        }
    }

    private companion object {
        const val VIDEO_SHARE = 0.8f
    }

    // 일부 인코더는 16의 배수 크기만 받아, 실패하면 16의 배수로 줄여 다시 시도한다.
    private fun createMp4Encoder(file: File, width: Int, height: Int, frameRate: Int): Mp4FrameEncoder = try {
        Mp4FrameEncoder(file, width, height, frameRate)
    } catch (first: IOException) {
        val alignedWidth = (width / 16 * 16).coerceAtLeast(16)
        val alignedHeight = (height / 16 * 16).coerceAtLeast(16)
        if (alignedWidth == width && alignedHeight == height) throw first
        Mp4FrameEncoder(file, alignedWidth, alignedHeight, frameRate)
    }
}
