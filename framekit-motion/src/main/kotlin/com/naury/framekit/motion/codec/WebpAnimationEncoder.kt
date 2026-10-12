package com.naury.framekit.motion.codec

import android.graphics.Bitmap
import android.os.Build
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * 프레임을 하나씩 받아 움직이는 WebP로 쓴다.
 *
 * 프레임마다 Android 내장 WebP 압축으로 정지 WebP를 만든 뒤 그 안의 이미지 데이터(VP8/VP8L, 알파)를 꺼내 애니메이션 컨테이너
 * (VP8X·ANIM·ANMF)로 묶는다. 네이티브 라이브러리 없이 모든 지원 버전에서 동작한다.
 *
 * @param file 결과 파일. 이미 있으면 덮어쓴다.
 * @param quality `0..100`. [lossless]면 압축 노력 정도로 쓴다.
 * @param loopCount 반복 횟수. `0`은 무한 반복.
 */
public class WebpAnimationEncoder(
    file: File,
    private val width: Int,
    private val height: Int,
    private val quality: Int = 80,
    private val lossless: Boolean = false,
    loopCount: Int = 0,
) : Closeable {

    private val output = RandomAccessFile(file, "rw")
    private var hasAlpha = false
    private var closed = false

    init {
        require(width in 1..MAX_SIZE && height in 1..MAX_SIZE) { "WebP size must be 1..$MAX_SIZE" }
        output.setLength(0)
        output.write("RIFF".toByteArray(Charsets.US_ASCII))
        writeU32(0) // 전체 크기는 닫을 때 채운다.
        output.write("WEBP".toByteArray(Charsets.US_ASCII))
        output.write("VP8X".toByteArray(Charsets.US_ASCII))
        writeU32(10)
        output.write(FLAG_ANIMATION) // 알파 표시는 닫을 때 필요하면 더한다.
        output.write(ByteArray(3))
        writeU24(width - 1)
        writeU24(height - 1)
        output.write("ANIM".toByteArray(Charsets.US_ASCII))
        writeU32(6)
        writeU32(0) // 배경색(투명).
        writeU16(loopCount.coerceIn(0, 0xFFFF))
    }

    /**
     * @param bitmap [width]×[height] 크기의 프레임.
     * @param durationMs 이 프레임을 보여 줄 시간(밀리초, 최대 약 4.6시간).
     */
    public fun addFrame(bitmap: Bitmap, durationMs: Long) {
        check(!closed) { "Encoder is closed" }
        require(bitmap.width == width && bitmap.height == height) { "Frame size must be ${width}x$height" }
        val chunks = imageChunks(compress(bitmap))
        if (chunks.any { it.fourCc == "ALPH" || it.fourCc == "VP8L" && bitmap.hasAlpha() }) hasAlpha = true
        val payload = ByteArrayOutputStream()
        writeU24(payload, 0) // 프레임 위치 X/2
        writeU24(payload, 0) // Y/2
        writeU24(payload, width - 1)
        writeU24(payload, height - 1)
        writeU24(payload, durationMs.coerceIn(1, 0xFFFFFF).toInt())
        // 앞 프레임과 섞지 않고(전체 화면을 덮어씀) 처리 방식은 그대로 둠.
        payload.write(0x02)
        chunks.forEach { chunk ->
            payload.write(chunk.fourCc.toByteArray(Charsets.US_ASCII))
            writeU32(payload, chunk.data.size)
            payload.write(chunk.data)
            if (chunk.data.size % 2 == 1) payload.write(0)
        }
        val bytes = payload.toByteArray()
        output.write("ANMF".toByteArray(Charsets.US_ASCII))
        writeU32(bytes.size)
        output.write(bytes)
        if (bytes.size % 2 == 1) output.write(0)
    }

    override fun close() {
        if (closed) return
        closed = true
        try {
            val size = output.length()
            output.seek(4)
            writeU32((size - 8).toInt())
            if (hasAlpha) {
                output.seek(20)
                output.write(FLAG_ANIMATION or FLAG_ALPHA)
            }
        } finally {
            output.close()
        }
    }

    private fun compress(bitmap: Bitmap): ByteArray {
        val stream = ByteArrayOutputStream()
        val format = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> if (lossless) Bitmap.CompressFormat.WEBP_LOSSLESS else Bitmap.CompressFormat.WEBP_LOSSY
            else -> @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP
        }
        // R 미만의 WEBP는 품질 100일 때 무손실이다.
        val q = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R && lossless) 100 else quality.coerceIn(0, if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) 99 else 100)
        if (!bitmap.compress(format, q, stream)) throw IOException("WebP frame could not be encoded")
        return stream.toByteArray()
    }

    private fun writeU32(value: Int) {
        output.write(value and 0xFF)
        output.write((value shr 8) and 0xFF)
        output.write((value shr 16) and 0xFF)
        output.write((value ushr 24) and 0xFF)
    }

    private fun writeU24(value: Int) {
        output.write(value and 0xFF)
        output.write((value shr 8) and 0xFF)
        output.write((value shr 16) and 0xFF)
    }

    private fun writeU16(value: Int) {
        output.write(value and 0xFF)
        output.write((value shr 8) and 0xFF)
    }

    internal class Chunk(val fourCc: String, val data: ByteArray)

    internal companion object {
        const val MAX_SIZE = 16_383
        const val FLAG_ANIMATION = 0x02
        const val FLAG_ALPHA = 0x10

        /** 정지 WebP에서 이미지 데이터 chunk(ALPH, VP8, VP8L)만 꺼낸다. */
        fun imageChunks(webp: ByteArray): List<Chunk> {
            if (webp.size < 20 || String(webp, 0, 4, Charsets.US_ASCII) != "RIFF" || String(webp, 8, 4, Charsets.US_ASCII) != "WEBP") {
                throw IOException("Encoder did not produce WebP")
            }
            val chunks = mutableListOf<Chunk>()
            var position = 12
            while (position + 8 <= webp.size) {
                val fourCc = String(webp, position, 4, Charsets.US_ASCII)
                val size = readU32(webp, position + 4)
                val start = position + 8
                if (size < 0 || start + size > webp.size) break
                if (fourCc == "ALPH" || fourCc == "VP8 " || fourCc == "VP8L") chunks += Chunk(fourCc, webp.copyOfRange(start, start + size))
                position = start + size + (size and 1)
            }
            if (chunks.none { it.fourCc == "VP8 " || it.fourCc == "VP8L" }) throw IOException("WebP has no image data")
            return chunks
        }

        fun readU32(bytes: ByteArray, offset: Int): Int =
            (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
                ((bytes[offset + 2].toInt() and 0xFF) shl 16) or ((bytes[offset + 3].toInt() and 0xFF) shl 24)

        fun writeU32(out: ByteArrayOutputStream, value: Int) {
            out.write(value and 0xFF)
            out.write((value shr 8) and 0xFF)
            out.write((value shr 16) and 0xFF)
            out.write((value ushr 24) and 0xFF)
        }

        fun writeU24(out: ByteArrayOutputStream, value: Int) {
            out.write(value and 0xFF)
            out.write((value shr 8) and 0xFF)
            out.write((value shr 16) and 0xFF)
        }
    }
}
