package com.naury.framekit.image.decode

import android.graphics.Bitmap
import com.naury.framekit.core.model.PixelSize

/**
 * Upright software bitmap of a source, possibly subsampled.
 *
 * The bitmap belongs to whoever requested the decode. It is not hardware-backed, so CPU Canvas
 * renderers can read it. Call [recycle] when it is no longer drawn.
 *
 * @property uprightSize full-resolution upright size of the source. Render plans are expressed in
 *   this size; the renderer scales by `bitmap.width / uprightSize.width`.
 */
public class DecodedImage(
    public val bitmap: Bitmap,
    public val uprightSize: PixelSize,
    public val hasGainMap: Boolean,
) {
    public fun recycle() {
        if (!bitmap.isRecycled) bitmap.recycle()
    }
}
