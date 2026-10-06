package com.naury.framekit.core.model

/**
 * Immutable description of a registered source after orientation normalization.
 *
 * @property uprightSize display size after the EXIF or container rotation has been applied.
 *   Every normalized coordinate in the S space refers to this size.
 * @property mimeType MIME type detected from the content, not from the file name.
 * @property durationUs media duration in microseconds, `null` for still images.
 * @property hasAudio whether the source contains an audio track. Always `false` for images.
 */
public data class SourceMetadata(
    val id: SourceId,
    val mediaType: MediaType,
    val mimeType: String,
    val uprightSize: PixelSize,
    val durationUs: Long? = null,
    val hasAudio: Boolean = false,
)
