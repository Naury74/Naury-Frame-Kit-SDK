package com.naury.framekit.core.pdf

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fwrite

/**
 * [path] 파일에 PDF 바이트를 쓴다. 파일이 있으면 덮어쓴다.
 *
 * @throws IllegalArgumentException 파일을 열 수 없을 때(폴더가 없거나 권한이 없음).
 */
@OptIn(ExperimentalForeignApi::class)
public class PdfFileOutput @Throws(IllegalArgumentException::class) constructor(path: String) : PdfOutput {
    private val file = fopen(path, "wb") ?: throw IllegalArgumentException("Cannot open $path")
    private var closed = false

    // @Throws는 PdfOutput에서 물려받는다. 다시 적으면 iOS 공통 소스 컴파일이 다른 필터로 본다.
    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        check(!closed) { "Output is closed" }
        require(offset >= 0 && length >= 0 && offset + length <= bytes.size) { "Range out of bounds" }
        if (length == 0) return
        val written = bytes.usePinned { fwrite(it.addressOf(offset), 1u, length.convert(), file) }
        check(written.toInt() == length) { "Write failed" }
    }

    override fun close() {
        if (closed) return
        closed = true
        check(fclose(file) == 0) { "Close failed" }
    }
}
