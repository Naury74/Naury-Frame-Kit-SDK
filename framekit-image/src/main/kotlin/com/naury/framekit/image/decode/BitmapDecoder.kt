package com.naury.framekit.image.decode

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.Rect
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.graphics.createBitmap
import com.naury.framekit.android.result.EditorErrorCode
import com.naury.framekit.android.result.FrameKitException
import com.naury.framekit.android.source.SourceLocation
import com.naury.framekit.android.source.SourceResolver
import com.naury.framekit.core.geometry.ExifOrientation
import com.naury.framekit.core.model.PixelRect
import com.naury.framekit.core.model.PixelSize
import com.naury.framekit.image.render.toAndroidMatrix
import java.io.IOException
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Decodes upright, sRGB, software bitmaps without reading the whole file into memory.
 *
 * API 28+ hands the file or Uri directly to [ImageDecoder], which applies the EXIF orientation itself.
 * API 26/27, and sources whose location is unknown, stream through [BitmapFactory]; this class then
 * applies the orientation matrix the core model defines, so mirrored orientations behave the same on
 * every path.
 *
 * @param contentResolver lets [ImageDecoder] open `content://` sources directly. Without it those
 *   sources take the streaming path.
 */
public class BitmapDecoder(
    private val resolver: SourceResolver,
    private val contentResolver: ContentResolver? = null,
) {

    /**
     * Decodes the whole image subsampled by [sampleSize].
     *
     * @param sampleSize power-of-two subsampling factor, see [SampleSize].
     * @throws FrameKitException with `DECODE_FAILED`, `INSUFFICIENT_MEMORY` or a source error.
     */
    public fun decode(info: ImageSourceInfo, sampleSize: Int): DecodedImage {
        requireSampleSize(sampleSize)
        return guarded {
            val input = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) imageDecoderSource(resolver.location(info.metadata.id)) else null
            if (input != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                decodeWithImageDecoder(info, sampleSize, input)
            } else {
                decodeWithBitmapFactory(info, sampleSize)
            }
        }
    }

    /**
     * Decodes only [uprightRegion] of the image, subsampled by [sampleSize]. Memory use is proportional
     * to the region instead of the whole picture, which keeps exports of small crops from very large
     * photos within budget.
     *
     * @param uprightRegion area in upright source pixels; it is clamped to the image.
     * @return `null` when the format does not support region decoding; use [decode] instead.
     * @throws FrameKitException with `DECODE_FAILED`, `INSUFFICIENT_MEMORY` or a source error.
     */
    public fun decodeRegion(info: ImageSourceInfo, uprightRegion: PixelRect, sampleSize: Int): DecodedImage? {
        requireSampleSize(sampleSize)
        val upright = info.metadata.uprightSize
        val region = PixelRect(
            uprightRegion.left.coerceIn(0, upright.width),
            uprightRegion.top.coerceIn(0, upright.height),
            uprightRegion.right.coerceIn(0, upright.width),
            uprightRegion.bottom.coerceIn(0, upright.height),
        )
        require(!region.isEmpty) { "Region is empty" }
        val encodedRect = encodedRect(info, region)
        return guarded {
            val decoder = openRegionDecoder(info) ?: return@guarded null
            try {
                val options = BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                    inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
                }
                val encoded = decoder.decodeRegion(encodedRect, options)
                    ?: throw FrameKitException(EditorErrorCode.DECODE_FAILED, "Region could not be decoded")
                // 경계가 정수로 맞춰지며 요청보다 약간 넓어진 실제 영역을 upright 좌표로 되돌려 renderer에 넘긴다.
                val actual = uprightRect(info, encodedRect)
                DecodedImage(applyOrientation(encoded, info.orientation), upright, info.hasGainMap, actual)
            } finally {
                decoder.recycle()
            }
        }
    }

    private fun imageDecoderSource(location: SourceLocation?): ImageDecoderInput? = when (location) {
        is SourceLocation.LocalFile -> ImageDecoderInput.FromFile(location)
        is SourceLocation.Content -> contentResolver?.let { ImageDecoderInput.FromContent(it, location) }
        null -> null
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun decodeWithImageDecoder(info: ImageSourceInfo, sampleSize: Int, input: ImageDecoderInput): DecodedImage {
        val source = when (input) {
            is ImageDecoderInput.FromFile -> ImageDecoder.createSource(input.location.file)
            is ImageDecoderInput.FromContent -> ImageDecoder.createSource(input.resolver, input.location.uri)
        }
        val bitmap = try {
            ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.setTargetSampleSize(sampleSize)
                decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
            }
        } catch (error: ImageDecoder.DecodeException) {
            throw FrameKitException(EditorErrorCode.DECODE_FAILED, "Image could not be decoded", error)
        } catch (error: SecurityException) {
            throw FrameKitException(EditorErrorCode.PERMISSION_DENIED, "No read access", error)
        }
        return DecodedImage(bitmap.toArgb8888(), info.metadata.uprightSize, info.hasGainMap)
    }

    private fun decodeWithBitmapFactory(info: ImageSourceInfo, sampleSize: Int): DecodedImage {
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
        }
        val encoded = resolver.openInputStream(info.metadata.id).use { BitmapFactory.decodeStream(it, null, options) }
            ?: throw FrameKitException(EditorErrorCode.DECODE_FAILED, "Image could not be decoded")
        return DecodedImage(applyOrientation(encoded, info.orientation), info.metadata.uprightSize, info.hasGainMap)
    }

    private fun openRegionDecoder(info: ImageSourceInfo): BitmapRegionDecoder? = try {
        resolver.openInputStream(info.metadata.id).use { stream ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                BitmapRegionDecoder.newInstance(stream)
            } else {
                @Suppress("DEPRECATION")
                BitmapRegionDecoder.newInstance(stream, false)
            }
        }
    } catch (_: IOException) {
        // 영역 디코딩을 지원하지 않는 형식이다. 호출 측이 전체 디코딩으로 넘어간다.
        null
    }

    private fun encodedRect(info: ImageSourceInfo, upright: PixelRect): Rect {
        val toEncoded = info.orientation.encodedToUpright(info.encodedSize.width.toDouble(), info.encodedSize.height.toDouble()).inverted()
        val corners = listOf(
            toEncoded.map(upright.left.toDouble(), upright.top.toDouble()),
            toEncoded.map(upright.right.toDouble(), upright.bottom.toDouble()),
        )
        return Rect(
            floor(corners.minOf { it.x }).toInt().coerceIn(0, info.encodedSize.width),
            floor(corners.minOf { it.y }).toInt().coerceIn(0, info.encodedSize.height),
            ceil(corners.maxOf { it.x }).toInt().coerceIn(0, info.encodedSize.width),
            ceil(corners.maxOf { it.y }).toInt().coerceIn(0, info.encodedSize.height),
        )
    }

    private fun uprightRect(info: ImageSourceInfo, encoded: Rect): PixelRect {
        val toUpright = info.orientation.encodedToUpright(info.encodedSize.width.toDouble(), info.encodedSize.height.toDouble())
        val a = toUpright.map(encoded.left.toDouble(), encoded.top.toDouble())
        val b = toUpright.map(encoded.right.toDouble(), encoded.bottom.toDouble())
        return PixelRect(
            minOf(a.x, b.x).toInt(),
            minOf(a.y, b.y).toInt(),
            maxOf(a.x, b.x).toInt(),
            maxOf(a.y, b.y).toInt(),
        )
    }

    private inline fun <T> guarded(block: () -> T): T = try {
        block()
    } catch (error: OutOfMemoryError) {
        throw FrameKitException(EditorErrorCode.INSUFFICIENT_MEMORY, "Bitmap allocation failed", error)
    } catch (error: IOException) {
        throw FrameKitException(EditorErrorCode.DECODE_FAILED, "Image could not be decoded", error)
    }

    private fun requireSampleSize(sampleSize: Int) {
        require(sampleSize >= 1 && Integer.bitCount(sampleSize) == 1) { "Sample size must be a power of two" }
    }

    private fun Bitmap.toArgb8888(): Bitmap {
        if (config == Bitmap.Config.ARGB_8888) return this
        // RGB_565·F16 등으로 디코딩되면 Canvas 합성과 encoder 입력을 하나로 맞추기 위해 변환한다.
        val converted = copy(Bitmap.Config.ARGB_8888, false)
        recycle()
        return converted
    }

    private sealed interface ImageDecoderInput {
        data class FromFile(val location: SourceLocation.LocalFile) : ImageDecoderInput
        data class FromContent(val resolver: ContentResolver, val location: SourceLocation.Content) : ImageDecoderInput
    }

    internal companion object {
        /** Returns an upright copy of [encoded] and recycles the original when they differ. */
        fun applyOrientation(encoded: Bitmap, orientation: ExifOrientation): Bitmap {
            if (orientation == ExifOrientation.NORMAL) return encoded
            val matrix = orientation.encodedToUpright(encoded.width.toDouble(), encoded.height.toDouble()).toAndroidMatrix()
            val upright = orientation.uprightSize(PixelSize(encoded.width, encoded.height))
            val result = createBitmap(upright.width, upright.height)
            Canvas(result).drawBitmap(encoded, matrix, Paint(Paint.FILTER_BITMAP_FLAG))
            encoded.recycle()
            return result
        }
    }
}
