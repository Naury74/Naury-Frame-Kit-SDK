package com.naury.framekit.android.result

import android.net.Uri
import android.os.Parcelable
import com.naury.framekit.core.model.MediaType
import kotlinx.parcelize.Parcelize

/** Non-fatal differences between the request and the produced file. */
public enum class ExportWarning {
    /** The source used a wide-gamut or other non-sRGB color space and was converted to sRGB. */
    COLOR_SPACE_CONVERTED_TO_SRGB,

    /** The source carried an Ultra HDR gain map that the output does not preserve. */
    HDR_GAIN_MAP_DROPPED,

    /** The encoder could not produce the requested format and a supported fallback was used. */
    ENCODER_FALLBACK_APPLIED,

    /** The source frame rate was higher than the configured maximum, so frames were dropped. */
    FRAME_RATE_REDUCED,

    /** HDR video was converted to SDR. */
    HDR_CONVERTED_TO_SDR,
}

/**
 * Successfully exported file.
 *
 * @property uri `content://` Uri readable by the host process. Share it with other apps through an
 *   Intent with `FLAG_GRANT_READ_URI_PERMISSION`.
 * @property width encoded width in pixels.
 * @property height encoded height in pixels.
 * @property durationMs duration of a video in milliseconds, `null` for images.
 * @property fileSize size of the file in bytes.
 */
@Parcelize
public data class EditedMedia(
    val uri: Uri,
    val mediaType: MediaType,
    val width: Int,
    val height: Int,
    val durationMs: Long?,
    val mimeType: String,
    val fileSize: Long,
    val warnings: List<ExportWarning> = emptyList(),
) : Parcelable
