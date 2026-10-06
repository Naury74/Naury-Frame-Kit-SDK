package com.naury.framekit.image.decode

/** Input formats that FrameKit supports on every device. */
public object SupportedImageFormats {
    public val mimeTypes: Set<String> = setOf("image/jpeg", "image/png", "image/webp")

    public fun isSupported(mimeType: String): Boolean = mimeType.lowercase() in mimeTypes
}
