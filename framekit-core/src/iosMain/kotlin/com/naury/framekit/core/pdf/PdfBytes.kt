package com.naury.framekit.core.pdf

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.posix.memcpy

/** [data]를 복사한 바이트 배열. JPEG를 [PdfWriter.addPage]에 넘길 때 쓴다. */
@OptIn(ExperimentalForeignApi::class)
public fun pdfBytes(data: NSData): ByteArray {
    val size = data.length.toInt()
    val bytes = ByteArray(size)
    if (size == 0) return bytes
    bytes.usePinned { memcpy(it.addressOf(0), data.bytes, data.length.convert()) }
    return bytes
}
