package com.naury.framekit.motion.codec

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import com.naury.framekit.motion.codec.WebpAnimationEncoder.Companion.readU32
import com.naury.framekit.motion.codec.WebpAnimationEncoder.Companion.writeU24
import com.naury.framekit.motion.codec.WebpAnimationEncoder.Companion.writeU32
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * 움직이는 WebP를 프레임으로 푼다. 프레임마다 데이터를 정지 WebP로 다시 감싸 Android 디코더로 풀고, 섞기·처리 방식대로 합성한다.
 */
public object WebpAnimationDecoder {

    /**
     * @param bytes WebP 파일 전체.
     * @param maxLongEdge 프레임 긴 변의 최대 크기(px).
     * @param maxFrames 읽을 최대 프레임 수.
     * @param maxBytes 프레임 비트맵 전체의 최대 메모리(바이트). 넘으면 더 작게 줄인다.
     * @throws IOException 움직이는 WebP가 아니거나 프레임을 하나도 풀지 못했을 때.
     */
    public fun decode(bytes: ByteArray, maxLongEdge: Int, maxFrames: Int, maxBytes: Long): AnimatedFrames {
        if (!AnimatedImageFormats.isAnimatedWebp(bytes)) throw IOException("Not an animated WebP")
        val canvasWidth = readU24(bytes, 24) + 1
        val canvasHeight = readU24(bytes, 27) + 1
        var loopCount = 0
        val frameInfos = mutableListOf<IntArray>()
        var position = 12
        while (position + 8 <= bytes.size) {
            val fourCc = String(bytes, position, 4, Charsets.US_ASCII)
            val size = readU32(bytes, position + 4)
            val start = position + 8
            if (size < 0 || start + size > bytes.size) break
            when (fourCc) {
                "ANIM" -> if (size >= 6) loopCount = (bytes[start + 4].toInt() and 0xFF) or ((bytes[start + 5].toInt() and 0xFF) shl 8)
                "ANMF" -> frameInfos += intArrayOf(start, size)
            }
            position = start + size + (size and 1)
        }
        if (frameInfos.isEmpty()) throw IOException("Animated WebP has no frames")

        var scale = minOf(1.0, maxLongEdge.toDouble() / maxOf(canvasWidth, canvasHeight))
        val count = frameInfos.size.coerceAtMost(maxFrames)
        val perFrame = maxBytes / count.coerceAtLeast(1)
        val frameBytes = canvasWidth * canvasHeight * 4.0 * scale * scale
        if (frameBytes > perFrame && perFrame > 0) scale *= kotlin.math.sqrt(perFrame / frameBytes)
        val targetWidth = maxOf(1, (canvasWidth * scale).toInt())
        val targetHeight = maxOf(1, (canvasHeight * scale).toInt())

        val canvasBitmap = createBitmap(canvasWidth, canvasHeight)
        val canvas = Canvas(canvasBitmap)
        val overwrite = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC) }
        val blend = Paint()
        val clear = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }
        val frames = mutableListOf<Bitmap>()
        val durations = mutableListOf<Long>()
        var pendingDispose: IntArray? = null
        try {
            for (info in frameInfos.take(count)) {
                val start = info[0]
                val x = readU24(bytes, start) * 2
                val y = readU24(bytes, start + 3) * 2
                val width = readU24(bytes, start + 6) + 1
                val height = readU24(bytes, start + 9) + 1
                val duration = readU24(bytes, start + 12).toLong()
                val flags = bytes[start + 15].toInt()
                val image = decodeFrame(bytes, start + 16, start + info[1], width, height) ?: continue
                pendingDispose?.let { canvas.drawRect(it[0].toFloat(), it[1].toFloat(), (it[0] + it[2]).toFloat(), (it[1] + it[3]).toFloat(), clear) }
                pendingDispose = null
                val noBlend = flags and 0x02 != 0
                canvas.drawBitmap(image, x.toFloat(), y.toFloat(), if (noBlend) overwrite else blend)
                image.recycle()
                frames += if (targetWidth == canvasWidth && targetHeight == canvasHeight) canvasBitmap.copy(Bitmap.Config.ARGB_8888, false) else canvasBitmap.scale(targetWidth, targetHeight)
                durations += if (duration <= 10) 100 else duration
                if (flags and 0x01 != 0) pendingDispose = intArrayOf(x, y, width, height)
            }
        } finally {
            canvasBitmap.recycle()
        }
        if (frames.isEmpty()) throw IOException("No WebP frame could be decoded")
        return AnimatedFrames(frames, durations, loopCount)
    }

    // ANMF 안의 ALPH·VP8·VP8L chunk를 정지 WebP 파일로 다시 감싸 푼다.
    private fun decodeFrame(bytes: ByteArray, from: Int, to: Int, width: Int, height: Int): Bitmap? {
        var alpha: ByteArray? = null
        var image: Pair<String, ByteArray>? = null
        var position = from
        while (position + 8 <= to) {
            val fourCc = String(bytes, position, 4, Charsets.US_ASCII)
            val size = readU32(bytes, position + 4)
            val start = position + 8
            if (size < 0 || start + size > to) break
            when (fourCc) {
                "ALPH" -> alpha = bytes.copyOfRange(start, start + size)
                "VP8 ", "VP8L" -> image = fourCc to bytes.copyOfRange(start, start + size)
            }
            position = start + size + (size and 1)
        }
        val (kind, data) = image ?: return null
        val body = ByteArrayOutputStream()
        body.write("WEBP".toByteArray(Charsets.US_ASCII))
        if (alpha != null && kind == "VP8 ") {
            body.write("VP8X".toByteArray(Charsets.US_ASCII))
            writeU32(body, 10)
            body.write(WebpAnimationEncoder.FLAG_ALPHA)
            body.write(ByteArray(3))
            writeU24(body, width - 1)
            writeU24(body, height - 1)
            writeChunk(body, "ALPH", alpha)
        }
        writeChunk(body, kind, data)
        val content = body.toByteArray()
        val file = ByteArrayOutputStream(content.size + 8)
        file.write("RIFF".toByteArray(Charsets.US_ASCII))
        writeU32(file, content.size)
        file.write(content)
        val encoded = file.toByteArray()
        val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
        return BitmapFactory.decodeByteArray(encoded, 0, encoded.size, options)
    }

    private fun writeChunk(out: ByteArrayOutputStream, fourCc: String, data: ByteArray) {
        out.write(fourCc.toByteArray(Charsets.US_ASCII))
        writeU32(out, data.size)
        out.write(data)
        if (data.size % 2 == 1) out.write(0)
    }

    private fun readU24(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8) or ((bytes[offset + 2].toInt() and 0xFF) shl 16)
}
