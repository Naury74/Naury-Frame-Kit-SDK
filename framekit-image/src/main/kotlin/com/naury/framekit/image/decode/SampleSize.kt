package com.naury.framekit.image.decode

import com.naury.framekit.core.model.PixelSize

/** Chooses a power-of-two subsampling factor for decoding. */
public object SampleSize {

    /**
     * Largest power of two that keeps the decoded long edge at or above [minimumLongEdge].
     * Returns 1 when the source is already smaller, so images are never decoded larger than stored.
     */
    public fun forMinimumLongEdge(size: PixelSize, minimumLongEdge: Int): Int {
        val longEdge = maxOf(size.width, size.height)
        var sample = 1
        while (longEdge / (sample * 2) >= minimumLongEdge) sample *= 2
        return sample
    }

    /** Largest power of two whose decoded size still covers [minimum] in both dimensions. */
    public fun forMinimumSize(size: PixelSize, minimum: PixelSize): Int {
        var sample = 1
        while (size.width / (sample * 2) >= minimum.width && size.height / (sample * 2) >= minimum.height) sample *= 2
        return sample
    }
}
