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

/** Reads size, format and orientation of an image without decoding its pixels. */
public class ImageMetadataReader(private val resolver: SourceResolver) {

    /**
     * @throws FrameKitException with `DECODE_FAILED` for empty or damaged headers and
     *   `UNSUPPORTED_FORMAT` for formats outside [SupportedImageFormats] or animated images.
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

        val orientation = readOrientation(id)
        val encoded = PixelSize(options.outWidth, options.outHeight)
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
        )
    }

    private fun readOrientation(id: SourceId): ExifOrientation = try {
        resolver.openInputStream(id).use { stream ->
            val value = ExifInterface(stream).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            ExifOrientation.fromExifValue(value)
        }
    } catch (_: IOException) {
        // EXIF가 깨져 있어도 픽셀은 읽을 수 있으므로 방향 정보만 기본값으로 둔다.
        ExifOrientation.NORMAL
    }
}
