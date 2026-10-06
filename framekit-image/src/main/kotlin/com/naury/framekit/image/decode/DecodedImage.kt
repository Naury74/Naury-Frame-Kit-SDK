package com.naury.framekit.image.decode

import android.graphics.Bitmap
import com.naury.framekit.core.model.PixelRect
import com.naury.framekit.core.model.PixelSize

/**
 * Upright software bitmap of a source, possibly subsampled.
 *
 * The bitmap belongs to whoever requested the decode. It is not hardware-backed, so CPU Canvas
 * renderers can read it. Call [recycle] when it is no longer drawn.
 *
 * @property uprightSize full-resolution upright size of the source. Render plans are expressed in
 *   this size.
 * @property uprightRegion part of the upright source that [bitmap] covers. It is the whole image
 *   unless the bitmap came from a region decode; the renderer maps the bitmap onto this rectangle.
 */
public class DecodedImage(
    public val bitmap: Bitmap,
    public val uprightSize: PixelSize,
    public val hasGainMap: Boolean,
    public val uprightRegion: PixelRect = PixelRect.of(uprightSize),
) {
    public fun recycle() {
        if (!bitmap.isRecycled) bitmap.recycle()
    }
}
