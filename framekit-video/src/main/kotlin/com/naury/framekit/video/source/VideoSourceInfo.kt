package com.naury.framekit.video.source

import com.naury.framekit.core.model.PixelSize
import com.naury.framekit.core.model.SourceMetadata

/**
 * What FrameKit learned about a video source before playing it.
 *
 * @property metadata upright size (container rotation applied), duration in microseconds and
 *   whether an audio track exists.
 * @property encodedSize stored frame size before rotation.
 * @property rotationDegrees container rotation, `0`, `90`, `180` or `270`.
 * @property frameRate frame rate declared by the container, or `null` when unknown.
 * @property isHdr `true` for HDR transfer functions; FrameKit exports SDR by default.
 */
public data class VideoSourceInfo(
    val metadata: SourceMetadata,
    val encodedSize: PixelSize,
    val rotationDegrees: Int,
    val videoMimeType: String,
    val audioMimeType: String?,
    val frameRate: Float?,
    val isHdr: Boolean,
)
