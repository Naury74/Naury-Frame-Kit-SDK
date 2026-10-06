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
import java.io.FileNotFoundException
import java.io.IOException
import kotlin.math.ceil
import kotlin.math.floor

/**
 * 파일 전체를 메모리에 읽지 않고 정방향 sRGB software bitmap으로 디코딩한다.
 *
 * API 28+에서는 파일이나 Uri를 [ImageDecoder]에 직접 넘기며, [ImageDecoder]가 EXIF 방향을 직접 적용한다.
 * API 26/27과 위치를 알 수 없는 원본은 [BitmapFactory]로 스트리밍하고, 이 클래스가 core 모델이 정의한
 * 방향 행렬을 적용하므로 반전(mirror) 방향도 모든 경로에서 같게 동작한다.
 *
 * @param contentResolver [ImageDecoder]가 `content://` 원본을 직접 열 수 있게 한다. 없으면 해당
 *   원본은 스트리밍 경로를 탄다.
 */
public class BitmapDecoder(
    private val resolver: SourceResolver,
    private val contentResolver: ContentResolver? = null,
) {

    /**
     * 이미지 전체를 [sampleSize]로 subsampling해 디코딩한다.
     *
     * @param sampleSize 2의 거듭제곱 subsampling 배율. [SampleSize] 참고.
     * @throws FrameKitException `DECODE_FAILED`, `INSUFFICIENT_MEMORY` 또는 원본 오류.
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
     * 이미지 중 [uprightRegion]만 [sampleSize]로 subsampling해 디코딩한다. 메모리 사용량이 사진 전체가 아니라
     * 영역 크기에 비례하므로, 아주 큰 사진에서 작게 crop한 결과도 예산 안에서 내보낼 수 있다.
     *
     * @param uprightRegion 정방향 원본 px 기준 영역. 이미지 범위로 clamp된다.
     * @return 포맷이 영역 디코딩을 지원하지 않으면 `null`. 이때는 [decode]를 쓴다.
     * @throws FrameKitException `DECODE_FAILED`, `INSUFFICIENT_MEMORY` 또는 원본 오류.
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
    } catch (error: FileNotFoundException) {
        // 편집 중 원본이 삭제되었거나 다른 앱이 준 권한이 사라진 경우다. 손상된 파일과 구분해 알린다.
        throw FrameKitException(EditorErrorCode.SOURCE_UNAVAILABLE, "Source is no longer available", error)
    } catch (error: SecurityException) {
        throw FrameKitException(EditorErrorCode.SOURCE_UNAVAILABLE, "Source permission was revoked", error)
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
        /** [encoded]의 정방향 사본을 반환하며, 원본과 다르면 원본을 recycle한다. */
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
