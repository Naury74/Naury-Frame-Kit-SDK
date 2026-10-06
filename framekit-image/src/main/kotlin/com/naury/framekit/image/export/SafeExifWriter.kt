package com.naury.framekit.image.export

import androidx.exifinterface.media.ExifInterface
import com.naury.framekit.android.source.SourceResolver
import com.naury.framekit.core.model.PixelSize
import com.naury.framekit.core.model.SourceId
import java.io.File
import java.io.IOException

/** Writes the [MetadataPolicy.SAFE] subset of the source EXIF into a freshly encoded JPEG. */
internal class SafeExifWriter(private val resolver: SourceResolver) {

    fun write(source: SourceId, output: File, outputSize: PixelSize) {
        val sourceExif = try {
            resolver.openInputStream(source).use(::ExifInterface)
        } catch (_: IOException) {
            null
        }
        val target = ExifInterface(output)
        sourceExif?.let { exif ->
            SAFE_TAGS.forEach { tag -> exif.getAttribute(tag)?.let { target.setAttribute(tag, it) } }
        }
        target.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
        target.setAttribute(ExifInterface.TAG_PIXEL_X_DIMENSION, outputSize.width.toString())
        target.setAttribute(ExifInterface.TAG_PIXEL_Y_DIMENSION, outputSize.height.toString())
        target.saveAttributes()
    }

    companion object {
        // 촬영 시각과 촬영 설정만 남긴다. 위치·소유자·일련번호·주석·원본 썸네일은 복사하지 않는다.
        val SAFE_TAGS = listOf(
            ExifInterface.TAG_DATETIME_ORIGINAL,
            ExifInterface.TAG_DATETIME_DIGITIZED,
            ExifInterface.TAG_OFFSET_TIME_ORIGINAL,
            ExifInterface.TAG_OFFSET_TIME_DIGITIZED,
            ExifInterface.TAG_SUBSEC_TIME_ORIGINAL,
            ExifInterface.TAG_MAKE,
            ExifInterface.TAG_MODEL,
            ExifInterface.TAG_EXPOSURE_TIME,
            ExifInterface.TAG_F_NUMBER,
            ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
            ExifInterface.TAG_FOCAL_LENGTH,
            ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
            ExifInterface.TAG_FLASH,
            ExifInterface.TAG_WHITE_BALANCE,
            ExifInterface.TAG_EXPOSURE_BIAS_VALUE,
        )
    }
}
