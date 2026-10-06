package com.naury.framekit.image.decode

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.graphics.createBitmap
import com.naury.framekit.android.result.EditorErrorCode
import com.naury.framekit.android.result.FrameKitException
import com.naury.framekit.android.source.SourceResolver
import com.naury.framekit.core.geometry.ExifOrientation
import com.naury.framekit.core.model.PixelSize
import com.naury.framekit.image.render.toAndroidMatrix
import java.io.IOException
import java.nio.ByteBuffer

/**
 * Decodes upright, sRGB, software bitmaps.
 *
 * API 28+ uses [ImageDecoder], which applies the EXIF orientation itself. API 26 and 27 use
 * [BitmapFactory], which ignores EXIF; this class then applies the same orientation matrix the
 * core model defines, so mirrored orientations are handled identically on both paths.
 */
public class BitmapDecoder(private val resolver: SourceResolver) {

    /**
     * @param sampleSize power-of-two subsampling factor, see `SampleSize`.
     * @throws FrameKitException with `DECODE_FAILED`, `INSUFFICIENT_MEMORY` or a source error.
     */
    public fun decode(info: ImageSourceInfo, sampleSize: Int): DecodedImage {
        require(sampleSize >= 1 && Integer.bitCount(sampleSize) == 1) { "Sample size must be a power of two" }
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                decodeWithImageDecoder(info, sampleSize)
            } else {
                decodeWithBitmapFactory(info, sampleSize)
            }
        } catch (error: OutOfMemoryError) {
            throw FrameKitException(EditorErrorCode.INSUFFICIENT_MEMORY, "Bitmap allocation failed", error)
        } catch (error: IOException) {
            throw FrameKitException(EditorErrorCode.DECODE_FAILED, "Image could not be decoded", error)
        }
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun decodeWithImageDecoder(info: ImageSourceInfo, sampleSize: Int): DecodedImage {
        val bytes = resolver.openInputStream(info.metadata.id).use { it.readBytes() }
        val source = ImageDecoder.createSource(ByteBuffer.wrap(bytes))
        val bitmap = try {
            ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.setTargetSampleSize(sampleSize)
                decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
            }
        } catch (error: ImageDecoder.DecodeException) {
            throw FrameKitException(EditorErrorCode.DECODE_FAILED, "Image could not be decoded", error)
        }
        val hasGainMap = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && bitmap.hasGainmap()
        return DecodedImage(bitmap.toArgb8888(), info.metadata.uprightSize, hasGainMap)
    }

    private fun decodeWithBitmapFactory(info: ImageSourceInfo, sampleSize: Int): DecodedImage {
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
        }
        val encoded = resolver.openInputStream(info.metadata.id).use { BitmapFactory.decodeStream(it, null, options) }
            ?: throw FrameKitException(EditorErrorCode.DECODE_FAILED, "Image could not be decoded")
        val upright = applyOrientation(encoded, info.orientation)
        return DecodedImage(upright, info.metadata.uprightSize, hasGainMap = false)
    }

    private fun Bitmap.toArgb8888(): Bitmap {
        if (config == Bitmap.Config.ARGB_8888) return this
        // RGB_565·F16 등으로 디코딩되면 Canvas 합성과 encoder 입력을 하나로 맞추기 위해 변환한다.
        val converted = copy(Bitmap.Config.ARGB_8888, false)
        recycle()
        return converted
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
