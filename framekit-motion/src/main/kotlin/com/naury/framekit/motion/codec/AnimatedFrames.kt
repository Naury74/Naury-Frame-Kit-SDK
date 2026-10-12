package com.naury.framekit.motion.codec

import android.graphics.Bitmap

/**
 * 움직이는 이미지에서 풀어낸 프레임들. 프레임은 모두 같은 크기의 ARGB_8888이며 앞 프레임과 합성이 끝난 화면 그대로다.
 *
 * @property durationsMs 프레임마다 보여 주는 시간(밀리초). [frames]와 길이가 같다.
 * @property loopCount 반복 횟수. `0`은 무한 반복.
 */
public class AnimatedFrames(
    public val frames: List<Bitmap>,
    public val durationsMs: List<Long>,
    public val loopCount: Int,
) {
    init {
        require(frames.isNotEmpty() && frames.size == durationsMs.size) { "Frames and durations must match" }
    }

    public val width: Int get() = frames.first().width
    public val height: Int get() = frames.first().height

    /** 전체 길이(밀리초). */
    public val totalMs: Long get() = durationsMs.sum()

    public fun recycle() {
        frames.forEach { if (!it.isRecycled) it.recycle() }
    }
}

/** 움직이는 이미지 형식 판별. */
public object AnimatedImageFormats {

    /** 파일 앞부분([header], 30바이트 이상 권장)이 GIF면 `true`. 프레임이 하나뿐인 GIF도 포함한다. */
    public fun isGif(header: ByteArray): Boolean =
        header.size >= 6 && header[0] == 'G'.code.toByte() && header[1] == 'I'.code.toByte() && header[2] == 'F'.code.toByte()

    /** 파일 앞부분이 움직이는 WebP(VP8X의 애니메이션 표시)면 `true`. */
    public fun isAnimatedWebp(header: ByteArray): Boolean {
        if (header.size < 21) return false
        val riff = String(header, 0, 4, Charsets.US_ASCII) == "RIFF" && String(header, 8, 4, Charsets.US_ASCII) == "WEBP"
        return riff && String(header, 12, 4, Charsets.US_ASCII) == "VP8X" && (header[20].toInt() and 0x02) != 0
    }
}
