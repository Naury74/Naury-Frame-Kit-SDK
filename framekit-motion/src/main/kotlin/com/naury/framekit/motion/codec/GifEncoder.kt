package com.naury.framekit.motion.codec

import android.graphics.Bitmap
import java.io.Closeable
import java.io.OutputStream

/**
 * 프레임을 하나씩 받아 GIF로 쓴다. 프레임마다 256색 팔레트를 따로 만들고(median cut), 프레임 사이에 무늬가 흔들리지 않도록
 * 위치에 고정된 순서 디더링을 쓴다. 반투명 픽셀은 투명(알파 128 미만)과 불투명으로 나눈다.
 *
 * @param loopCount 반복 횟수. `0`은 무한 반복.
 */
public class GifEncoder(private val output: OutputStream, private val width: Int, private val height: Int, loopCount: Int = 0) : Closeable {

    private var closed = false
    // GIF 지연은 1/100초 단위라 남는 시간을 다음 프레임으로 넘겨 전체 길이가 어긋나지 않게 한다.
    private var carryMs = 0.0

    init {
        require(width in 1..MAX_SIZE && height in 1..MAX_SIZE) { "GIF size must be 1..$MAX_SIZE" }
        output.write("GIF89a".toByteArray(Charsets.US_ASCII))
        writeShort(width)
        writeShort(height)
        output.write(0x70) // 전역 색표 없음, 색 해상도 8비트.
        output.write(0)
        output.write(0)
        // 반복 횟수(NETSCAPE2.0 확장).
        output.write(byteArrayOf(0x21, 0xFF.toByte(), 0x0B))
        output.write("NETSCAPE2.0".toByteArray(Charsets.US_ASCII))
        output.write(byteArrayOf(3, 1))
        writeShort(loopCount.coerceIn(0, 0xFFFF))
        output.write(0)
    }

    /**
     * @param bitmap [width]×[height] 크기의 프레임.
     * @param durationMs 이 프레임을 보여 줄 시간(밀리초). 20ms보다 짧으면 20ms로 쓴다(그보다 짧으면 브라우저가 느리게 튼다).
     */
    public fun addFrame(bitmap: Bitmap, durationMs: Long) {
        check(!closed) { "Encoder is closed" }
        require(bitmap.width == width && bitmap.height == height) { "Frame size must be ${width}x$height" }
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val hasTransparency = pixels.any { (it ushr 24) < ALPHA_THRESHOLD }
        val palette = Quantizer.palette(pixels, if (hasTransparency) 255 else 256)
        val indices = Quantizer.map(pixels, width, palette, transparentIndex = if (hasTransparency) palette.size else -1)

        val exact = durationMs / 10.0 + carryMs
        val centis = maxOf(MIN_DELAY_CS, Math.round(exact).toInt()).coerceAtMost(0xFFFF)
        carryMs = exact - centis

        // 그래픽 제어 확장: 지연, 투명 색, 처리 방식(투명이 있으면 배경으로 지움).
        output.write(byteArrayOf(0x21, 0xF9.toByte(), 4))
        output.write(if (hasTransparency) (2 shl 2) or 1 else (1 shl 2))
        writeShort(centis)
        output.write(if (hasTransparency) palette.size else 0)
        output.write(0)

        // 이미지 설명자와 256색 지역 색표.
        output.write(0x2C)
        writeShort(0)
        writeShort(0)
        writeShort(width)
        writeShort(height)
        output.write(0x80 or 7)
        val table = ByteArray(256 * 3)
        palette.forEachIndexed { i, color ->
            table[i * 3] = (color shr 16).toByte()
            table[i * 3 + 1] = (color shr 8).toByte()
            table[i * 3 + 2] = color.toByte()
        }
        output.write(table)
        LzwEncoder.encode(indices, 8, output)
    }

    override fun close() {
        if (closed) return
        closed = true
        output.write(0x3B)
        output.flush()
    }

    private fun writeShort(value: Int) {
        output.write(value and 0xFF)
        output.write((value shr 8) and 0xFF)
    }

    internal object Quantizer {

        /** 이미지에서 고른 [maxColors]개 이하의 대표 색(RGB). 투명 픽셀은 빼고 고른다. */
        fun palette(pixels: IntArray, maxColors: Int): IntArray {
            val step = maxOf(1, pixels.size / SAMPLE_TARGET)
            val samples = IntArray((pixels.size + step - 1) / step)
            var count = 0
            var i = 0
            while (i < pixels.size) {
                val p = pixels[i]
                if ((p ushr 24) >= ALPHA_THRESHOLD) samples[count++] = p and 0xFFFFFF
                i += step
            }
            if (count == 0) return intArrayOf(0)
            // median cut: 색 범위가 가장 넓은 상자를 가장 넓은 축의 중간값에서 나누기를 반복한다.
            val boxes = mutableListOf(Box(samples, 0, count))
            while (boxes.size < maxColors) {
                val box = boxes.filter { it.count > 1 }.maxByOrNull { it.range } ?: break
                if (box.range == 0) break
                boxes.remove(box)
                boxes.addAll(box.split())
            }
            return IntArray(boxes.size) { boxes[it].average() }
        }

        /**
         * 픽셀을 팔레트 번호로 바꾼다. 4×4 순서 디더링으로 띠무늬를 줄이고, 5비트 RGB 표로 가까운 색 찾기를 빠르게 한다.
         */
        fun map(pixels: IntArray, width: Int, palette: IntArray, transparentIndex: Int): ByteArray {
            val cache = IntArray(32 * 32 * 32) { -1 }
            val out = ByteArray(pixels.size)
            for (index in pixels.indices) {
                val p = pixels[index]
                if (transparentIndex >= 0 && (p ushr 24) < ALPHA_THRESHOLD) {
                    out[index] = transparentIndex.toByte()
                    continue
                }
                val x = index % width
                val y = index / width
                val d = BAYER[(y and 3) * 4 + (x and 3)]
                val r = (((p shr 16) and 0xFF) + d).coerceIn(0, 255)
                val g = (((p shr 8) and 0xFF) + d).coerceIn(0, 255)
                val b = ((p and 0xFF) + d).coerceIn(0, 255)
                val key = ((r shr 3) shl 10) or ((g shr 3) shl 5) or (b shr 3)
                var found = cache[key]
                if (found < 0) {
                    found = nearest(palette, (r and 0xF8) or 4, (g and 0xF8) or 4, (b and 0xF8) or 4)
                    cache[key] = found
                }
                out[index] = found.toByte()
            }
            return out
        }

        private fun nearest(palette: IntArray, r: Int, g: Int, b: Int): Int {
            var best = 0
            var bestDistance = Int.MAX_VALUE
            for (i in palette.indices) {
                val c = palette[i]
                val dr = ((c shr 16) and 0xFF) - r
                val dg = ((c shr 8) and 0xFF) - g
                val db = (c and 0xFF) - b
                // 사람 눈이 초록에 더 민감한 것을 반영한 가중치.
                val distance = 2 * dr * dr + 4 * dg * dg + 3 * db * db
                if (distance < bestDistance) {
                    bestDistance = distance
                    best = i
                }
            }
            return best
        }

        private class Box(val colors: IntArray, val from: Int, val to: Int) {
            val count get() = to - from
            private var axis = 0
            var range = 0
                private set

            init {
                var minR = 255; var maxR = 0; var minG = 255; var maxG = 0; var minB = 255; var maxB = 0
                for (i in from until to) {
                    val c = colors[i]
                    val r = (c shr 16) and 0xFF
                    val g = (c shr 8) and 0xFF
                    val b = c and 0xFF
                    if (r < minR) minR = r
                    if (r > maxR) maxR = r
                    if (g < minG) minG = g
                    if (g > maxG) maxG = g
                    if (b < minB) minB = b
                    if (b > maxB) maxB = b
                }
                val ranges = intArrayOf(maxR - minR, maxG - minG, maxB - minB)
                axis = ranges.indices.maxBy { ranges[it] }
                range = ranges[axis] * count.coerceAtMost(1 shl 16).let { kotlin.math.sqrt(it.toDouble()).toInt().coerceAtLeast(1) }
            }

            fun split(): List<Box> {
                val shift = when (axis) { 0 -> 16; 1 -> 8; else -> 0 }
                val sorted = colors.copyOfRange(from, to).sortedBy { (it shr shift) and 0xFF }
                sorted.forEachIndexed { i, c -> colors[from + i] = c }
                val middle = from + count / 2
                return listOf(Box(colors, from, middle), Box(colors, middle, to))
            }

            fun average(): Int {
                var r = 0L; var g = 0L; var b = 0L
                for (i in from until to) {
                    val c = colors[i]
                    r += (c shr 16) and 0xFF
                    g += (c shr 8) and 0xFF
                    b += c and 0xFF
                }
                val n = count.coerceAtLeast(1)
                return ((r / n).toInt() shl 16) or ((g / n).toInt() shl 8) or (b / n).toInt()
            }
        }

        private const val SAMPLE_TARGET = 40_000
        private val BAYER = intArrayOf(-8, 0, -6, 2, 4, -4, 6, -2, -5, 3, -7, 1, 7, -1, 5, -3)
    }

    /** GIF LZW 압축. */
    private object LzwEncoder {
        fun encode(indices: ByteArray, minCodeSize: Int, out: OutputStream) {
            out.write(minCodeSize)
            val clear = 1 shl minCodeSize
            val end = clear + 1
            val blocks = BlockWriter(out)
            var codeSize = minCodeSize + 1
            var next = clear + 2
            // (이전 코드 << 8 | 다음 바이트) → 새 코드. 열린 주소 해시.
            val keys = IntArray(HASH_SIZE) { -1 }
            val values = IntArray(HASH_SIZE)
            blocks.writeCode(clear, codeSize)
            if (indices.isEmpty()) {
                blocks.writeCode(end, codeSize)
                blocks.finish()
                return
            }
            var prefix = indices[0].toInt() and 0xFF
            for (i in 1 until indices.size) {
                val c = indices[i].toInt() and 0xFF
                val key = (prefix shl 8) or c
                var slot = (key * 0x9E3779B1.toInt() ushr 19) and (HASH_SIZE - 1)
                var found = -1
                while (keys[slot] != -1) {
                    if (keys[slot] == key) {
                        found = values[slot]
                        break
                    }
                    slot = (slot + 1) and (HASH_SIZE - 1)
                }
                if (found >= 0) {
                    prefix = found
                    continue
                }
                blocks.writeCode(prefix, codeSize)
                if (next < MAX_CODES) {
                    keys[slot] = key
                    values[slot] = next
                    if (next == 1 shl codeSize && codeSize < 12) codeSize++
                    next++
                } else {
                    // 표가 가득 차면 처음부터 다시 쌓는다.
                    blocks.writeCode(clear, codeSize)
                    keys.fill(-1)
                    codeSize = minCodeSize + 1
                    next = clear + 2
                }
                prefix = c
            }
            blocks.writeCode(prefix, codeSize)
            // 디코더는 마지막 코드를 받으면서 표에 하나를 더 넣으므로, 그때 코드 길이가 늘어나는 경우를 맞춰 준다.
            if (next == 1 shl codeSize && codeSize < 12) codeSize++
            blocks.writeCode(end, codeSize)
            blocks.finish()
        }

        private class BlockWriter(val out: OutputStream) {
            private val block = ByteArray(255)
            private var length = 0
            private var buffer = 0
            private var bits = 0

            fun writeCode(code: Int, size: Int) {
                buffer = buffer or (code shl bits)
                bits += size
                while (bits >= 8) {
                    push(buffer and 0xFF)
                    buffer = buffer ushr 8
                    bits -= 8
                }
            }

            private fun push(byte: Int) {
                block[length++] = byte.toByte()
                if (length == 255) flushBlock()
            }

            private fun flushBlock() {
                if (length == 0) return
                out.write(length)
                out.write(block, 0, length)
                length = 0
            }

            fun finish() {
                if (bits > 0) push(buffer and 0xFF)
                buffer = 0
                bits = 0
                flushBlock()
                out.write(0)
            }
        }

        const val MAX_CODES = 4096
        const val HASH_SIZE = 8192
    }

    public companion object {
        public const val MAX_SIZE: Int = 0xFFFF
        private const val MIN_DELAY_CS = 2
        private const val ALPHA_THRESHOLD = 128
    }
}
