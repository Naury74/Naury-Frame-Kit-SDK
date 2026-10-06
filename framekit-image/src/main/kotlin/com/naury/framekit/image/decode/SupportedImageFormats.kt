package com.naury.framekit.image.decode

/** FrameKit이 모든 기기에서 지원하는 입력 포맷이다. */
public object SupportedImageFormats {
    public val mimeTypes: Set<String> = setOf("image/jpeg", "image/png", "image/webp")

    public fun isSupported(mimeType: String): Boolean = mimeType.lowercase() in mimeTypes
}
