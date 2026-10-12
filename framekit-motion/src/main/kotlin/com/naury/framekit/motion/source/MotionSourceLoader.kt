package com.naury.framekit.motion.source

import android.content.ContentResolver
import android.graphics.Bitmap
import androidx.core.graphics.scale
import com.naury.framekit.android.result.EditorErrorCode
import com.naury.framekit.android.result.FrameKitException
import com.naury.framekit.android.source.SourceResolver
import com.naury.framekit.core.model.SourceId
import com.naury.framekit.image.decode.BitmapDecoder
import com.naury.framekit.image.decode.ImageMetadataReader
import com.naury.framekit.image.decode.SampleSize
import com.naury.framekit.motion.codec.AnimatedFrames
import com.naury.framekit.motion.codec.AnimatedImageFormats
import com.naury.framekit.motion.codec.AnimatedImageProbe
import com.naury.framekit.motion.codec.GifDecoder
import com.naury.framekit.motion.codec.WebpAnimationDecoder
import java.io.IOException

/** 원본 하나의 종류. */
public enum class MotionSourceType {
    STILL,
    GIF,
    ANIMATED_WEBP,
}

/**
 * 움직이는 이미지 편집기의 원본을 읽는다. 사진은 정방향 비트맵으로, GIF·움직이는 WebP는 프레임으로 푼다.
 * 모든 메서드는 디스크를 읽으므로 메인 스레드에서 부르지 않는다.
 */
public class MotionSourceLoader(private val resolver: SourceResolver, private val contentResolver: ContentResolver?) {

    /** 원본 앞부분을 읽어 종류를 알아낸다. 프레임이 하나뿐인 GIF는 사진으로 본다. */
    public fun probe(id: SourceId): MotionSourceType {
        val header = ByteArray(HEADER_BYTES)
        val read = resolver.openInputStream(id).use { stream -> stream.readNBytesCompat(header) }
        val bytes = header.copyOf(read)
        return when {
            AnimatedImageFormats.isAnimatedWebp(bytes) -> MotionSourceType.ANIMATED_WEBP
            AnimatedImageFormats.isGif(bytes) -> if (gifFrameCountAtLeastTwo(id)) MotionSourceType.GIF else MotionSourceType.STILL
            else -> MotionSourceType.STILL
        }
    }

    /**
     * 사진을 긴 변 [longEdge](px) 이하로 풀어 정방향 비트맵으로 돌려준다.
     *
     * @throws FrameKitException 읽을 수 없거나 지원하지 않는 형식일 때.
     */
    public fun decodeStill(id: SourceId, longEdge: Int): Bitmap {
        val info = ImageMetadataReader(resolver).read(id)
        val decoded = BitmapDecoder(resolver, contentResolver).decode(info, SampleSize.forMinimumLongEdge(info.metadata.uprightSize, longEdge))
        val bitmap = decoded.bitmap
        val long = maxOf(bitmap.width, bitmap.height)
        if (long <= longEdge) return bitmap.ensureMutableArgb()
        val ratio = longEdge.toDouble() / long
        return bitmap.scale(maxOf(1, (bitmap.width * ratio).toInt()), maxOf(1, (bitmap.height * ratio).toInt())).also { if (it !== bitmap) bitmap.recycle() }
    }

    /** 사진의 정방향 너비/높이 비율. */
    public fun stillAspect(id: SourceId): Double {
        val size = ImageMetadataReader(resolver).read(id).metadata.uprightSize
        return size.width.toDouble() / size.height
    }

    /**
     * GIF·움직이는 WebP를 프레임으로 푼다.
     *
     * @param maxLongEdge 프레임 긴 변의 최대 크기(px).
     * @param maxFrames 최대 프레임 수.
     * @param maxBytes 프레임 전체의 최대 메모리(바이트).
     * @throws FrameKitException 파일이 너무 크면 `FILE_TOO_LARGE`, 풀 수 없으면 `UNSUPPORTED_FORMAT`.
     */
    public fun decodeAnimation(id: SourceId, maxLongEdge: Int, maxFrames: Int, maxBytes: Long): AnimatedFrames {
        val bytes = resolver.openInputStream(id).use { stream ->
            val buffer = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(64 * 1024)
            while (true) {
                val n = stream.read(chunk)
                if (n < 0) break
                buffer.write(chunk, 0, n)
                if (buffer.size() > MAX_ANIMATION_FILE_BYTES) throw FrameKitException(EditorErrorCode.SOURCE_TOO_LARGE, "Animated image is too large")
            }
            buffer.toByteArray()
        }
        return try {
            if (AnimatedImageFormats.isAnimatedWebp(bytes)) {
                WebpAnimationDecoder.decode(bytes, maxLongEdge, maxFrames, maxBytes)
            } else {
                GifDecoder.decode(bytes, maxLongEdge, maxFrames, maxBytes)
            }
        } catch (error: IOException) {
            throw FrameKitException(EditorErrorCode.UNSUPPORTED_FORMAT, "Animated image could not be decoded", error)
        } catch (error: OutOfMemoryError) {
            throw FrameKitException(EditorErrorCode.INSUFFICIENT_MEMORY, "Animated image is too large for memory", error)
        }
    }

    // 두 번째 이미지 설명자가 있는지만 빠르게 본다.
    private fun gifFrameCountAtLeastTwo(id: SourceId): Boolean = AnimatedImageProbe.isAnimated(resolver.openInputStream(id))

    private fun Bitmap.ensureMutableArgb(): Bitmap =
        if (isMutable && config == Bitmap.Config.ARGB_8888) this else copy(Bitmap.Config.ARGB_8888, true).also { recycle() }

    private fun java.io.InputStream.readNBytesCompat(buffer: ByteArray): Int {
        var total = 0
        while (total < buffer.size) {
            val n = read(buffer, total, buffer.size - total)
            if (n < 0) break
            total += n
        }
        return total
    }

    public companion object {
        private const val HEADER_BYTES = 32

        /** 움직이는 원본 파일의 최대 크기(바이트). */
        public const val MAX_ANIMATION_FILE_BYTES: Long = 100L * 1024 * 1024
    }
}
