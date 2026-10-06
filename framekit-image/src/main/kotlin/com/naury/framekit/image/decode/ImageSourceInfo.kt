package com.naury.framekit.image.decode

import com.naury.framekit.core.geometry.ExifOrientation
import com.naury.framekit.core.model.PixelSize
import com.naury.framekit.core.model.SourceMetadata

/**
 * What the decoder learned about an image without decoding its pixels.
 *
 * @property encodedSize size of the stored pixels before [orientation] is applied.
 * @property isSrgb `false` when the image declares another color space and will be converted.
 * @property hasGainMap `true` for Ultra HDR images whose gain map the editor does not preserve.
 */
public data class ImageSourceInfo(
    val metadata: SourceMetadata,
    val encodedSize: PixelSize,
    val orientation: ExifOrientation,
    val isSrgb: Boolean,
    val hasGainMap: Boolean = false,
)
