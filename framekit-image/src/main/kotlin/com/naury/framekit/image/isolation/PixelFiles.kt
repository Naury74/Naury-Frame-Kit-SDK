package com.naury.framekit.image.isolation

import android.graphics.Bitmap
import androidx.core.graphics.createBitmap
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel

/**
 * 분리 프로세스와 사진·마스크를 파일로 주고받는다. Binder로는 큰 비트맵을 보낼 수 없어서다.
 * 큰 사진도 힙에 복사본을 만들지 않도록 파일을 메모리에 매핑한다.
 */
public object PixelFiles {

    /** [bitmap]의 ARGB_8888 픽셀을 [Bitmap.copyPixelsToBuffer] 순서 그대로 [file]에 쓴다(너비×높이×4바이트). */
    public fun writePixels(bitmap: Bitmap, file: File) {
        val source = if (bitmap.config == Bitmap.Config.ARGB_8888) bitmap else bitmap.copy(Bitmap.Config.ARGB_8888, false)
        try {
            RandomAccessFile(file, "rw").use { raf ->
                source.copyPixelsToBuffer(raf.channel.map(FileChannel.MapMode.READ_WRITE, 0, source.byteCount.toLong()))
            }
        } finally {
            if (source !== bitmap) source.recycle()
        }
    }

    /** [writePixels]로 쓴 파일을 비트맵으로 읽는다. */
    public fun readPixels(file: File, width: Int, height: Int): Bitmap {
        val bitmap = createBitmap(width, height)
        RandomAccessFile(file, "r").use { raf ->
            bitmap.copyPixelsFromBuffer(raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, bitmap.byteCount.toLong()))
        }
        return bitmap
    }

    /** 픽셀마다 0..255 한 바이트인 파일을 alpha에 담은 ARGB 마스크로 읽는다. RGB는 흰색이다. */
    public fun readAlphaMask(file: File, width: Int, height: Int): Bitmap {
        val bitmap = createBitmap(width, height)
        val row = IntArray(width)
        RandomAccessFile(file, "r").use { raf ->
            val bytes = raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, width.toLong() * height)
            for (y in 0 until height) {
                for (x in 0 until width) row[x] = ((bytes.get().toInt() and 0xFF) shl 24) or 0x00FFFFFF
                bitmap.setPixels(row, 0, width, 0, y, width, 1)
            }
        }
        return bitmap
    }
}
