package com.naury.framekit.image.export

import androidx.exifinterface.media.ExifInterface
import com.naury.framekit.android.source.SourceResolver
import com.naury.framekit.core.model.PixelSize
import com.naury.framekit.core.model.SourceId
import java.io.File
import java.io.IOException

/** Writes source EXIF into a freshly encoded JPEG or WEBP, either the [MetadataPolicy.SAFE] subset or all known tags. */
internal class SafeExifWriter(private val resolver: SourceResolver) {

    fun write(source: SourceId, output: File, outputSize: PixelSize, includeAll: Boolean = false) {
        val sourceExif = try {
            resolver.openInputStream(source).use(::ExifInterface)
        } catch (_: IOException) {
            null
        }
        val target = ExifInterface(output)
        sourceExif?.let { exif ->
            (if (includeAll) SAFE_TAGS + EXTENDED_TAGS else SAFE_TAGS).forEach { tag -> exif.getAttribute(tag)?.let { target.setAttribute(tag, it) } }
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

        // ALL 정책에서만 추가로 복사한다. 원본 썸네일과 크기·방향 태그는 여기서도 제외한다.
        val EXTENDED_TAGS = listOf(
            ExifInterface.TAG_DATETIME,
            ExifInterface.TAG_OFFSET_TIME,
            ExifInterface.TAG_ARTIST,
            ExifInterface.TAG_COPYRIGHT,
            ExifInterface.TAG_IMAGE_DESCRIPTION,
            ExifInterface.TAG_USER_COMMENT,
            ExifInterface.TAG_SOFTWARE,
            ExifInterface.TAG_CAMERA_OWNER_NAME,
            ExifInterface.TAG_BODY_SERIAL_NUMBER,
            ExifInterface.TAG_LENS_MAKE,
            ExifInterface.TAG_LENS_MODEL,
            ExifInterface.TAG_LENS_SERIAL_NUMBER,
            ExifInterface.TAG_LENS_SPECIFICATION,
            ExifInterface.TAG_METERING_MODE,
            ExifInterface.TAG_EXPOSURE_PROGRAM,
            ExifInterface.TAG_SCENE_CAPTURE_TYPE,
            ExifInterface.TAG_GPS_VERSION_ID,
            ExifInterface.TAG_GPS_LATITUDE,
            ExifInterface.TAG_GPS_LATITUDE_REF,
            ExifInterface.TAG_GPS_LONGITUDE,
            ExifInterface.TAG_GPS_LONGITUDE_REF,
            ExifInterface.TAG_GPS_ALTITUDE,
            ExifInterface.TAG_GPS_ALTITUDE_REF,
            ExifInterface.TAG_GPS_TIMESTAMP,
            ExifInterface.TAG_GPS_DATESTAMP,
            ExifInterface.TAG_GPS_PROCESSING_METHOD,
            ExifInterface.TAG_GPS_IMG_DIRECTION,
            ExifInterface.TAG_GPS_IMG_DIRECTION_REF,
        )
    }
}
