package com.naury.framekit.image.decode

import java.io.InputStream

/** Reads the RIFF header of a WEBP file to tell animated files from still images. */
internal object WebpHeader {
    private const val HEADER_SIZE = 21
    private const val ANIMATION_FLAG = 0x02

    /** `true` when the stream starts with an extended WEBP header whose animation flag is set. */
    fun isAnimated(stream: InputStream): Boolean {
        val header = ByteArray(HEADER_SIZE)
        var read = 0
        while (read < HEADER_SIZE) {
            val count = stream.read(header, read, HEADER_SIZE - read)
            if (count < 0) return false
            read += count
        }
        val riff = String(header, 0, 4, Charsets.US_ASCII)
        val webp = String(header, 8, 4, Charsets.US_ASCII)
        val chunk = String(header, 12, 4, Charsets.US_ASCII)
        return riff == "RIFF" && webp == "WEBP" && chunk == "VP8X" && header[20].toInt() and ANIMATION_FLAG != 0
    }
}
