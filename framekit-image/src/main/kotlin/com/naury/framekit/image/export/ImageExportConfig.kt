package com.naury.framekit.image.export

import android.os.Parcelable
import com.naury.framekit.core.validation.ValidationCode
import com.naury.framekit.core.validation.ValidationIssue
import com.naury.framekit.core.validation.ValidationResult
import kotlinx.parcelize.Parcelize

/** Encoded image format. */
public enum class ImageFormat(public val mimeType: String, public val extension: String) {
    JPEG("image/jpeg", "jpg"),

    /** Lossless. [ImageExportConfig.quality] does not apply. */
    PNG("image/png", "png"),

    /** Lossy WEBP with alpha; uses [ImageExportConfig.quality]. */
    WEBP_LOSSY("image/webp", "webp"),

    /**
     * Lossless WEBP with alpha. Requires Android 11 (API 30); older devices return
     * `UNSUPPORTED_FORMAT` instead of silently writing a lossy file.
     */
    WEBP_LOSSLESS("image/webp", "webp"),
    ;

    /** `true` for formats that keep transparency. */
    public val supportsAlpha: Boolean get() = this != JPEG
}

/** Which source metadata is written to the output. */
public enum class MetadataPolicy {
    /**
     * Copies capture time and a fixed list of camera settings. Location, owner, serial numbers,
     * comments and the embedded thumbnail are dropped. Orientation is written as normal and the
     * dimensions are the new ones.
     */
    SAFE,

    /** Writes no source metadata. */
    NONE,

    /**
     * Copies all known tags including location and camera serial. Only use when the user explicitly
     * asked to keep them. Orientation, dimensions and the thumbnail are still rewritten so the output
     * never shows the unedited picture.
     */
    ALL,
}

/**
 * Output settings for image export.
 *
 * @property quality JPEG and lossy WEBP quality `0..100`. Ignored for PNG and lossless WEBP.
 * @property maxWidth optional upper bound of the output width in pixels.
 * @property maxHeight optional upper bound of the output height in pixels.
 * @property maxOutputPixels upper bound of `width × height`. The default 16 MP keeps a full export
 *   within the memory of mid-range devices. The output is never larger than the crop.
 * @property jpegBackgroundArgb color that replaces transparency in JPEG output.
 * @property metadataPolicy source metadata written to JPEG and WEBP outputs; PNG output carries none.
 */
@Parcelize
public data class ImageExportConfig(
    val format: ImageFormat = ImageFormat.JPEG,
    val quality: Int = 92,
    val maxWidth: Int? = null,
    val maxHeight: Int? = null,
    val maxOutputPixels: Long = DEFAULT_MAX_OUTPUT_PIXELS,
    val metadataPolicy: MetadataPolicy = MetadataPolicy.SAFE,
    val jpegBackgroundArgb: Int = 0xFF000000.toInt(),
) : Parcelable {

    public fun validate(): ValidationResult {
        val issues = mutableListOf<ValidationIssue>()
        if (quality !in 0..100) issues += ValidationIssue(ValidationCode.OUT_OF_RANGE, "export.quality", "Expected 0..100")
        if (maxWidth != null && maxWidth <= 0) issues += ValidationIssue(ValidationCode.INVALID_SIZE, "export.maxWidth", "Must be positive")
        if (maxHeight != null && maxHeight <= 0) issues += ValidationIssue(ValidationCode.INVALID_SIZE, "export.maxHeight", "Must be positive")
        if (maxOutputPixels <= 0) issues += ValidationIssue(ValidationCode.INVALID_SIZE, "export.maxOutputPixels", "Must be positive")
        return ValidationResult.of(issues)
    }

    public companion object {
        public const val DEFAULT_MAX_OUTPUT_PIXELS: Long = 16_000_000L
    }
}
