package com.naury.framekit.motion.codec

import android.graphics.Bitmap
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import java.io.IOException

/**
 * GIF를 프레임으로 푼다. 프레임마다 앞 프레임의 처리 방식(disposal)을 반영해 합성한 전체 화면을 돌려준다.
 *
 * 브라우저와 같이 0~10ms 지연은 100ms로 본다.
 */
public object GifDecoder {

    /**
     * @param bytes GIF 파일 전체.
     * @param maxLongEdge 프레임 긴 변의 최대 크기(px). 원본이 더 크면 줄인다.
     * @param maxFrames 읽을 최대 프레임 수. 넘는 프레임은 버린다.
     * @param maxBytes 프레임 비트맵 전체의 최대 메모리(바이트). 넘으면 더 작게 줄인다.
     * @throws IOException 형식이 잘못되었거나 프레임이 하나도 없을 때.
     */
    public fun decode(bytes: ByteArray, maxLongEdge: Int, maxFrames: Int, maxBytes: Long): AnimatedFrames {
        val reader = Reader(bytes)
        if (bytes.size < 13 || !AnimatedImageFormats.isGif(bytes)) throw IOException("Not a GIF")
        reader.position = 6
        val width = reader.u16()
        val height = reader.u16()
        if (width <= 0 || height <= 0) throw IOException("Invalid GIF size")
        val packed = reader.u8()
        reader.u8() // 배경색 번호. 브라우저처럼 투명으로 다룬다.
        reader.u8()
        val globalTable = if (packed and 0x80 != 0) reader.colorTable(2 shl (packed and 0x07)) else null

        // 메모리 예산은 실제로 읽을 프레임 수로 나눠, 프레임이 적은 GIF를 불필요하게 줄이지 않는다.
        val target = targetSize(width, height, maxLongEdge, countFrames(bytes).coerceIn(1, maxFrames), maxBytes)
        val canvas = IntArray(width * height)
        var previous: IntArray? = null
        val frames = mutableListOf<Bitmap>()
        val durations = mutableListOf<Long>()
        var loopCount = 1
        var delayMs = 100L
        var disposal = 0
        var transparent = -1

        try {
            loop@ while (reader.position < bytes.size && frames.size < maxFrames) {
                when (reader.u8()) {
                    0x21 -> when (reader.u8()) {
                        0xF9 -> {
                            reader.u8()
                            val flags = reader.u8()
                            val delay = reader.u16() * 10L
                            val index = reader.u8()
                            reader.u8()
                            disposal = (flags shr 2) and 0x07
                            transparent = if (flags and 0x01 != 0) index else -1
                            delayMs = if (delay <= 10) 100 else delay
                        }
                        0xFF -> {
                            val size = reader.u8()
                            val app = String(bytes, reader.position, size.coerceAtMost(bytes.size - reader.position), Charsets.US_ASCII)
                            reader.position += size
                            if (app.startsWith("NETSCAPE") || app.startsWith("ANIMEXTS")) {
                                var block = reader.u8()
                                while (block > 0) {
                                    if (block >= 3 && reader.peek() == 1) {
                                        reader.u8()
                                        loopCount = reader.u16()
                                        reader.position += block - 3
                                    } else {
                                        reader.position += block
                                    }
                                    block = reader.u8()
                                }
                            } else {
                                reader.skipBlocks()
                            }
                        }
                        else -> reader.skipBlocks()
                    }
                    0x2C -> {
                        val left = reader.u16()
                        val top = reader.u16()
                        val frameWidth = reader.u16()
                        val frameHeight = reader.u16()
                        val framePacked = reader.u8()
                        val table = if (framePacked and 0x80 != 0) reader.colorTable(2 shl (framePacked and 0x07)) else globalTable
                            ?: throw IOException("GIF frame has no color table")
                        val interlaced = framePacked and 0x40 != 0
                        val indices = Lzw.decode(reader, frameWidth * frameHeight)
                        if (disposal == 3) previous = canvas.copyOf()
                        draw(canvas, width, height, indices, table, transparent, left, top, frameWidth, frameHeight, interlaced)
                        frames += toBitmap(canvas, width, height, target)
                        durations += delayMs
                        // 다음 프레임을 그리기 전에 이번 프레임의 처리 방식대로 화면을 정리한다.
                        when (disposal) {
                            2 -> clear(canvas, width, height, left, top, frameWidth, frameHeight)
                            3 -> previous?.copyInto(canvas)
                        }
                        disposal = 0
                        transparent = -1
                        delayMs = 100
                    }
                    0x3B -> break@loop
                    else -> break@loop
                }
            }
        } catch (error: IndexOutOfBoundsException) {
            // 끝이 잘린 GIF는 읽은 프레임까지만 쓴다.
            if (frames.isEmpty()) throw IOException("Truncated GIF", error)
        }
        if (frames.isEmpty()) {
            throw IOException("GIF has no frames")
        }
        return AnimatedFrames(frames, durations, if (loopCount == 0) 0 else loopCount)
    }

    /** 이미지 설명자 수(프레임 수). 형식이 깨졌으면 그때까지 센 수. */
    internal fun countFrames(bytes: ByteArray): Int {
        if (bytes.size < 13) return 0
        var position = 13
        val packed = bytes[10].toInt() and 0xFF
        if (packed and 0x80 != 0) position += 3 * (2 shl (packed and 0x07))
        var images = 0
        while (position < bytes.size) {
            when (bytes[position++].toInt() and 0xFF) {
                0x21 -> {
                    position++
                    position = skipSubBlocks(bytes, position)
                }
                0x2C -> {
                    images++
                    if (position + 9 > bytes.size) return images
                    val flags = bytes[position + 8].toInt() and 0xFF
                    position += 9
                    if (flags and 0x80 != 0) position += 3 * (2 shl (flags and 0x07))
                    position++
                    position = skipSubBlocks(bytes, position)
                }
                else -> return images
            }
        }
        return images
    }

    private fun skipSubBlocks(bytes: ByteArray, start: Int): Int {
        var position = start
        while (position < bytes.size) {
            val size = bytes[position++].toInt() and 0xFF
            if (size == 0) break
            position += size
        }
        return position
    }

    private fun targetSize(width: Int, height: Int, maxLongEdge: Int, maxFrames: Int, maxBytes: Long): Pair<Int, Int> {
        var scale = minOf(1.0, maxLongEdge.toDouble() / maxOf(width, height))
        val perFrameBudget = maxBytes / maxFrames.coerceAtLeast(1)
        val bytes = width * height * 4.0 * scale * scale
        if (bytes > perFrameBudget && perFrameBudget > 0) scale *= kotlin.math.sqrt(perFrameBudget / bytes)
        return maxOf(1, (width * scale).toInt()) to maxOf(1, (height * scale).toInt())
    }

    private fun toBitmap(canvas: IntArray, width: Int, height: Int, target: Pair<Int, Int>): Bitmap {
        val full = createBitmap(width, height).apply { setPixels(canvas, 0, width, 0, 0, width, height) }
        if (target.first == width && target.second == height) return full
        return full.scale(target.first, target.second).also { if (it !== full) full.recycle() }
    }

    private fun draw(
        canvas: IntArray,
        width: Int,
        height: Int,
        indices: ByteArray,
        table: IntArray,
        transparent: Int,
        left: Int,
        top: Int,
        frameWidth: Int,
        frameHeight: Int,
        interlaced: Boolean,
    ) {
        for (row in 0 until frameHeight) {
            val y = top + if (interlaced) interlacedRow(row, frameHeight) else row
            if (y !in 0 until height) continue
            for (column in 0 until frameWidth) {
                val x = left + column
                if (x !in 0 until width) continue
                val index = indices.getOrElse(row * frameWidth + column) { 0 }.toInt() and 0xFF
                if (index == transparent || index >= table.size) continue
                canvas[y * width + x] = table[index]
            }
        }
    }

    // 4단계 interlace 순서로 저장된 줄 번호를 실제 줄 번호로 바꾼다.
    private fun interlacedRow(row: Int, height: Int): Int {
        val pass1 = (height + 7) / 8
        val pass2 = (height + 3) / 8
        val pass3 = (height + 1) / 4
        return when {
            row < pass1 -> row * 8
            row < pass1 + pass2 -> (row - pass1) * 8 + 4
            row < pass1 + pass2 + pass3 -> (row - pass1 - pass2) * 4 + 2
            else -> (row - pass1 - pass2 - pass3) * 2 + 1
        }
    }

    private fun clear(canvas: IntArray, width: Int, height: Int, left: Int, top: Int, frameWidth: Int, frameHeight: Int) {
        for (y in top.coerceAtLeast(0) until (top + frameHeight).coerceAtMost(height)) {
            for (x in left.coerceAtLeast(0) until (left + frameWidth).coerceAtMost(width)) canvas[y * width + x] = 0
        }
    }

    internal class Reader(val bytes: ByteArray) {
        var position = 0

        fun u8(): Int = bytes[position++].toInt() and 0xFF

        fun peek(): Int = bytes[position].toInt() and 0xFF

        fun u16(): Int = u8() or (u8() shl 8)

        fun colorTable(size: Int): IntArray = IntArray(size) { 0xFF shl 24 or (u8() shl 16) or (u8() shl 8) or u8() }

        fun skipBlocks() {
            var size = u8()
            while (size > 0) {
                position += size
                size = u8()
            }
        }
    }

    /** GIF LZW 압축 풀기. */
    private object Lzw {
        fun decode(reader: Reader, pixelCount: Int): ByteArray {
            val minCodeSize = reader.u8()
            // 데이터 하위 블록을 하나로 모은다.
            val data = java.io.ByteArrayOutputStream()
            var size = reader.u8()
            while (size > 0) {
                data.write(reader.bytes, reader.position, size.coerceAtMost(reader.bytes.size - reader.position))
                reader.position += size
                size = reader.u8()
            }
            val input = data.toByteArray()
            val output = ByteArray(pixelCount)
            if (minCodeSize !in 1..11) return output
            val clear = 1 shl minCodeSize
            val end = clear + 1
            val prefix = IntArray(MAX_CODES)
            val suffix = ByteArray(MAX_CODES)
            val stack = ByteArray(MAX_CODES + 1)
            for (i in 0 until clear) suffix[i] = i.toByte()
            var codeSize = minCodeSize + 1
            var next = clear + 2
            var old = -1
            var first = 0
            var bits = 0
            var buffer = 0
            var position = 0
            var outIndex = 0
            while (outIndex < pixelCount) {
                while (bits < codeSize) {
                    if (position >= input.size) return output
                    buffer = buffer or ((input[position++].toInt() and 0xFF) shl bits)
                    bits += 8
                }
                val code = buffer and ((1 shl codeSize) - 1)
                buffer = buffer ushr codeSize
                bits -= codeSize
                when {
                    code == clear -> {
                        codeSize = minCodeSize + 1
                        next = clear + 2
                        old = -1
                    }
                    code == end -> return output
                    old == -1 -> {
                        output[outIndex++] = suffix[code]
                        old = code
                        first = code
                    }
                    else -> {
                        var current = code
                        var top = 0
                        if (code >= next) {
                            stack[top++] = first.toByte()
                            current = old
                        }
                        while (current >= clear) {
                            if (top >= stack.size || current >= MAX_CODES) return output
                            stack[top++] = suffix[current]
                            current = prefix[current]
                        }
                        first = suffix[current].toInt() and 0xFF
                        stack[top++] = first.toByte()
                        if (next < MAX_CODES) {
                            prefix[next] = old
                            suffix[next] = first.toByte()
                            next++
                            if (next == 1 shl codeSize && codeSize < 12) codeSize++
                        }
                        old = code
                        while (top > 0 && outIndex < pixelCount) output[outIndex++] = stack[--top]
                    }
                }
            }
            return output
        }

        const val MAX_CODES = 4096
    }
}
