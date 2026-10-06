package com.naury.framekit.image.decode

import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import androidx.exifinterface.media.ExifInterface
import com.naury.framekit.android.result.EditorErrorCode
import com.naury.framekit.android.result.FrameKitException
import com.naury.framekit.android.source.SourceResolver
import com.naury.framekit.core.geometry.ExifOrientation
import com.naury.framekit.core.model.MediaType
import com.naury.framekit.core.model.PixelSize
import com.naury.framekit.core.model.SourceId
import com.naury.framekit.core.model.SourceMetadata
import java.io.IOException

/** 픽셀을 디코딩하지 않고 이미지의 크기·포맷·방향을 읽는다. */
public class ImageMetadataReader(private val resolver: SourceResolver) {

    /**
     * @throws FrameKitException header가 비었거나 손상되면 `DECODE_FAILED`,
     *   [SupportedImageFormats] 밖의 포맷이나 애니메이션 이미지면 `UNSUPPORTED_FORMAT`,
     *   [MAX_SOURCE_PIXELS]를 넘으면 `SOURCE_TOO_LARGE`.
     */
    public fun read(id: SourceId): ImageSourceInfo {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        try {
            resolver.openInputStream(id).use { BitmapFactory.decodeStream(it, null, options) }
        } catch (error: IOException) {
            throw FrameKitException(EditorErrorCode.SOURCE_UNAVAILABLE, "Could not read header", error)
        }
        val mimeType = options.outMimeType?.lowercase()
        if (options.outWidth <= 0 || options.outHeight <= 0 || mimeType == null) {
            throw FrameKitException(EditorErrorCode.DECODE_FAILED, "Image header is missing or damaged")
        }
        if (!SupportedImageFormats.isSupported(mimeType)) {
            throw FrameKitException(EditorErrorCode.UNSUPPORTED_FORMAT, "Unsupported image type")
        }
        if (mimeType == "image/webp" && resolver.openInputStream(id).use(WebpHeader::isAnimated)) {
            throw FrameKitException(EditorErrorCode.UNSUPPORTED_FORMAT, "Animated WEBP is not supported")
        }

        val encoded = PixelSize(options.outWidth, options.outHeight)
        if (encoded.pixelCount > MAX_SOURCE_PIXELS) {
            throw FrameKitException(EditorErrorCode.SOURCE_TOO_LARGE, "Image has ${encoded.pixelCount} pixels")
        }
        val exif = readExif(id)
        val orientation = exif.orientation
        val colorSpace = options.outColorSpace
        return ImageSourceInfo(
            metadata = SourceMetadata(
                id = id,
                mediaType = MediaType.IMAGE,
                mimeType = mimeType,
                uprightSize = orientation.uprightSize(encoded),
            ),
            encodedSize = encoded,
            orientation = orientation,
            isSrgb = colorSpace == null || colorSpace == ColorSpace.get(ColorSpace.Named.SRGB),
            hasGainMap = exif.hasGainMap,
        )
    }

    private fun readExif(id: SourceId): ExifSummary = try {
        resolver.openInputStream(id).use { stream ->
            val exif = ExifInterface(stream)
            val value = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            // Ultra HDR은 XMP의 hdrgm 네임스페이스로 gain map을 선언한다. 디코딩 경로와 관계없이 같은 판단을 하기 위해 header에서 확인한다.
            val xmp = exif.getAttribute(ExifInterface.TAG_XMP).orEmpty()
            ExifSummary(ExifOrientation.fromExifValue(value), xmp.contains("hdrgm:"))
        }
    } catch (_: IOException) {
        // EXIF가 깨져 있어도 픽셀은 읽을 수 있으므로 방향 정보만 기본값으로 둔다.
        ExifSummary(ExifOrientation.NORMAL, hasGainMap = false)
    }

    private data class ExifSummary(val orientation: ExifOrientation, val hasGainMap: Boolean)

    public companion object {
        /**
         * 허용하는 최대 원본 크기로 250 MP다. 현재 200 MP 폰 카메라는 수용하며, 더 크거나 위조된 header는
         * 픽셀을 할당하기 전에 `SOURCE_TOO_LARGE`로 거부한다.
         */
        public const val MAX_SOURCE_PIXELS: Long = 250_000_000L
    }
}
