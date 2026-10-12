package com.naury.framekit.motion.codec

import java.io.BufferedInputStream
import java.io.InputStream

/** 파일 전체를 메모리에 올리지 않고 움직이는 이미지인지 알아낸다. */
public object AnimatedImageProbe {

    /**
     * @param stream 처음부터 읽는 새 스트림. 이 함수가 닫는다.
     * @return 프레임이 둘 이상인 GIF이거나 움직이는 WebP면 `true`. 읽을 수 없으면 `false`.
     */
    public fun isAnimated(stream: InputStream): Boolean = try {
        BufferedInputStream(stream).use { input ->
            input.mark(HEADER_BYTES)
            val header = ByteArray(HEADER_BYTES)
            val read = readFully(input, header)
            val bytes = header.copyOf(read)
            when {
                AnimatedImageFormats.isAnimatedWebp(bytes) -> true
                AnimatedImageFormats.isGif(bytes) -> {
                    input.reset()
                    gifHasTwoFrames(input)
                }
                else -> false
            }
        }
    } catch (_: Exception) {
        false
    }

    // GIF 블록을 건너뛰며 이미지 설명자가 두 번 나오는지 본다.
    private fun gifHasTwoFrames(input: InputStream): Boolean {
        skip(input, 10)
        val packed = input.read()
        if (packed < 0) return false
        skip(input, 2)
        if (packed and 0x80 != 0) skip(input, 3L * (2 shl (packed and 0x07)))
        var images = 0
        while (true) {
            when (input.read()) {
                0x21 -> {
                    if (input.read() < 0) return false
                    skipBlocks(input)
                }
                0x2C -> {
                    images++
                    if (images >= 2) return true
                    skip(input, 8)
                    val flags = input.read()
                    if (flags < 0) return false
                    if (flags and 0x80 != 0) skip(input, 3L * (2 shl (flags and 0x07)))
                    input.read()
                    skipBlocks(input)
                }
                else -> return false
            }
        }
    }

    private fun skipBlocks(input: InputStream) {
        while (true) {
            val size = input.read()
            if (size <= 0) return
            skip(input, size.toLong())
        }
    }

    private fun skip(input: InputStream, count: Long) {
        var remaining = count
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped <= 0) {
                if (input.read() < 0) return
                remaining--
            } else {
                remaining -= skipped
            }
        }
    }

    private fun readFully(input: InputStream, buffer: ByteArray): Int {
        var total = 0
        while (total < buffer.size) {
            val n = input.read(buffer, total, buffer.size - total)
            if (n < 0) break
            total += n
        }
        return total
    }

    private const val HEADER_BYTES = 32
}
