package com.naury.framekit.video.source

import android.content.Context
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import com.naury.framekit.android.result.EditorErrorCode
import com.naury.framekit.android.result.FrameKitException
import com.naury.framekit.android.source.SourceLocation
import com.naury.framekit.android.source.SourceResolver
import com.naury.framekit.core.model.MediaType
import com.naury.framekit.core.model.PixelSize
import com.naury.framekit.core.model.SourceId
import com.naury.framekit.core.model.SourceMetadata
import java.io.FileNotFoundException
import java.io.IOException

/**
 * [MediaExtractor]로 영상의 트랙, 길이, 회전, 코덱 지원 여부를 읽는다.
 *
 * 길이는 컨테이너 타임스탬프(마이크로초)에서 가져온다. 프레임 수와 frame rate로 추정하지 않으므로
 * 가변 frame rate 파일도 길이가 정확하다.
 */
public class VideoMetadataReader(context: Context, private val resolver: SourceResolver) {

    private val appContext = context.applicationContext

    /**
     * @throws FrameKitException 영상 트랙이 없으면 `INVALID_SOURCE`, 영상 코덱용 디코더가 없으면
     *   `UNSUPPORTED_FORMAT`, 읽을 수 없는 파일이면 `PERMISSION_DENIED`, `SOURCE_UNAVAILABLE` 또는
     *   `DECODE_FAILED`.
     */
    public fun read(id: SourceId): VideoSourceInfo {
        val extractor = MediaExtractor()
        try {
            when (val location = resolver.location(id)) {
                is SourceLocation.Content -> extractor.setDataSource(appContext, location.uri, null)
                is SourceLocation.LocalFile -> extractor.setDataSource(location.file.absolutePath)
                null -> throw FrameKitException(EditorErrorCode.INVALID_SOURCE, "Video sources need a file or Uri")
            }
            var video: MediaFormat? = null
            var audio: MediaFormat? = null
            var durationUs = 0L
            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (format.containsKey(MediaFormat.KEY_DURATION)) durationUs = maxOf(durationUs, format.getLong(MediaFormat.KEY_DURATION))
                if (video == null && mime.startsWith("video/")) video = format
                if (audio == null && mime.startsWith("audio/")) audio = format
            }
            val videoFormat = video ?: throw FrameKitException(EditorErrorCode.INVALID_SOURCE, "No video track")
            if (MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(withoutFrameRate(videoFormat)) == null) {
                throw FrameKitException(EditorErrorCode.UNSUPPORTED_FORMAT, "No decoder for the video codec")
            }
            val width = videoFormat.getInteger(MediaFormat.KEY_WIDTH)
            val height = videoFormat.getInteger(MediaFormat.KEY_HEIGHT)
            if (width <= 0 || height <= 0 || durationUs <= 0) throw FrameKitException(EditorErrorCode.DECODE_FAILED, "Invalid video header")
            val rotation = if (videoFormat.containsKey(MediaFormat.KEY_ROTATION)) Math.floorMod(videoFormat.getInteger(MediaFormat.KEY_ROTATION), 360) else 0
            val encoded = PixelSize(width, height)
            val upright = if (rotation == 90 || rotation == 270) encoded.transposed() else encoded
            return VideoSourceInfo(
                metadata = SourceMetadata(id, MediaType.VIDEO, "video/mp4", upright, durationUs, hasAudio = audio != null),
                encodedSize = encoded,
                rotationDegrees = rotation,
                videoMimeType = checkNotNull(videoFormat.getString(MediaFormat.KEY_MIME)),
                audioMimeType = audio?.getString(MediaFormat.KEY_MIME),
                frameRate = frameRateOf(videoFormat),
                isHdr = isHdr(videoFormat),
            )
        } catch (error: SecurityException) {
            throw FrameKitException(EditorErrorCode.PERMISSION_DENIED, "No read access", error)
        } catch (error: FileNotFoundException) {
            throw FrameKitException(EditorErrorCode.SOURCE_UNAVAILABLE, "Video not found", error)
        } catch (error: IOException) {
            throw FrameKitException(EditorErrorCode.DECODE_FAILED, "Video could not be read", error)
        } catch (error: IllegalArgumentException) {
            throw FrameKitException(EditorErrorCode.DECODE_FAILED, "Video header is damaged", error)
        } finally {
            extractor.release()
        }
    }

    private fun frameRateOf(format: MediaFormat): Float? = when {
        !format.containsKey(MediaFormat.KEY_FRAME_RATE) -> null
        else -> runCatching { format.getInteger(MediaFormat.KEY_FRAME_RATE).toFloat() }
            .recoverCatching { format.getFloat(MediaFormat.KEY_FRAME_RATE) }
            .getOrNull()
    }

    private fun isHdr(format: MediaFormat): Boolean = format.containsKey(MediaFormat.KEY_COLOR_TRANSFER) &&
        format.getInteger(MediaFormat.KEY_COLOR_TRANSFER).let { it == MediaFormat.COLOR_TRANSFER_ST2084 || it == MediaFormat.COLOR_TRANSFER_HLG }

    // 일부 기기는 frame rate가 들어간 format으로 디코더를 찾으면 null을 돌려준다.
    private fun withoutFrameRate(format: MediaFormat): MediaFormat = MediaFormat().apply {
        setString(MediaFormat.KEY_MIME, format.getString(MediaFormat.KEY_MIME))
        setInteger(MediaFormat.KEY_WIDTH, format.getInteger(MediaFormat.KEY_WIDTH))
        setInteger(MediaFormat.KEY_HEIGHT, format.getInteger(MediaFormat.KEY_HEIGHT))
        if (format.containsKey(MediaFormat.KEY_PROFILE)) setInteger(MediaFormat.KEY_PROFILE, format.getInteger(MediaFormat.KEY_PROFILE))
    }
}
