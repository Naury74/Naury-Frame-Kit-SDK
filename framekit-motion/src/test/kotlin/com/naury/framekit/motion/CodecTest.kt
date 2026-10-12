package com.naury.framekit.motion

import android.graphics.Bitmap
import android.graphics.Color
import androidx.core.graphics.createBitmap
import com.google.common.truth.Truth.assertThat
import com.naury.framekit.motion.codec.AnimatedImageFormats
import com.naury.framekit.motion.codec.GifDecoder
import com.naury.framekit.motion.codec.GifEncoder
import com.naury.framekit.motion.codec.WebpAnimationDecoder
import com.naury.framekit.motion.codec.WebpAnimationEncoder
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CodecTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `gif round trip keeps frames timing and colors`() {
        val out = ByteArrayOutputStream()
        GifEncoder(out, 40, 30, loopCount = 0).use { encoder ->
            encoder.addFrame(solid(40, 30, Color.RED), 100)
            encoder.addFrame(gradient(40, 30), 250)
            encoder.addFrame(solid(40, 30, Color.BLUE), 1000)
        }
        val bytes = out.toByteArray()
        assertThat(AnimatedImageFormats.isGif(bytes)).isTrue()

        val decoded = GifDecoder.decode(bytes, maxLongEdge = 1000, maxFrames = 100, maxBytes = 100_000_000)
        assertThat(decoded.frames).hasSize(3)
        assertThat(decoded.durationsMs).containsExactly(100L, 250L, 1000L).inOrder()
        assertThat(decoded.loopCount).isEqualTo(0)
        assertThat(decoded.width).isEqualTo(40)
        assertClose(decoded.frames[0].getPixel(5, 5), Color.RED)
        assertClose(decoded.frames[2].getPixel(39, 29), Color.BLUE)
        // 그라데이션도 256색 안에서 원래 색과 가깝게 남는다.
        val original = gradient(40, 30)
        for ((x, y) in listOf(0 to 0, 20 to 15, 39 to 29)) assertClose(decoded.frames[1].getPixel(x, y), original.getPixel(x, y), tolerance = 24)
    }

    @Test
    fun `gif keeps transparency and long animations survive table resets`() {
        val out = ByteArrayOutputStream()
        val noisy = createBitmap(200, 200).apply {
            val random = java.util.Random(7)
            for (y in 0 until 200) for (x in 0 until 200) setPixel(x, y, if (x < 20) Color.TRANSPARENT else 0xFF000000.toInt() or random.nextInt(0xFFFFFF))
        }
        GifEncoder(out, 200, 200).use { it.addFrame(noisy, 50) }
        val decoded = GifDecoder.decode(out.toByteArray(), 1000, 10, 100_000_000)
        assertThat(Color.alpha(decoded.frames[0].getPixel(5, 100))).isEqualTo(0)
        assertThat(Color.alpha(decoded.frames[0].getPixel(150, 150))).isEqualTo(255)
        // 잡음 그림은 LZW 표가 여러 번 가득 차므로, 끝까지 맞게 풀리면 표 초기화가 맞게 된 것이다.
        assertClose(decoded.frames[0].getPixel(199, 199), noisy.getPixel(199, 199), tolerance = 60)
    }

    @Test
    fun `animated webp round trip keeps frames and timing`() {
        val file = folder.newFile("out.webp")
        WebpAnimationEncoder(file, 32, 24, quality = 90).use { encoder ->
            encoder.addFrame(solid(32, 24, Color.GREEN), 120)
            encoder.addFrame(solid(32, 24, Color.MAGENTA), 300)
        }
        val bytes = file.readBytes()
        assertThat(AnimatedImageFormats.isAnimatedWebp(bytes)).isTrue()
        val decoded = WebpAnimationDecoder.decode(bytes, 1000, 10, 100_000_000)
        assertThat(decoded.frames).hasSize(2)
        assertThat(decoded.durationsMs).containsExactly(120L, 300L).inOrder()
        assertClose(decoded.frames[0].getPixel(10, 10), Color.GREEN, tolerance = 20)
        assertClose(decoded.frames[1].getPixel(10, 10), Color.MAGENTA, tolerance = 20)
    }

    @Test
    fun `decoders shrink frames to the requested size and frame budget`() {
        val out = ByteArrayOutputStream()
        GifEncoder(out, 400, 200).use { encoder -> repeat(5) { encoder.addFrame(solid(400, 200, Color.WHITE), 40) } }
        val decoded = GifDecoder.decode(out.toByteArray(), maxLongEdge = 100, maxFrames = 3, maxBytes = 100_000_000)
        assertThat(decoded.frames).hasSize(3)
        assertThat(decoded.width).isEqualTo(100)
        assertThat(decoded.height).isEqualTo(50)
    }

    private fun solid(width: Int, height: Int, color: Int): Bitmap = createBitmap(width, height).apply { eraseColor(color) }

    private fun gradient(width: Int, height: Int): Bitmap = createBitmap(width, height).apply {
        for (y in 0 until height) for (x in 0 until width) setPixel(x, y, Color.rgb(x * 255 / width, y * 255 / height, 128))
    }

    private fun assertClose(actual: Int, expected: Int, tolerance: Int = 8) {
        assertThat(abs(Color.red(actual) - Color.red(expected))).isAtMost(tolerance)
        assertThat(abs(Color.green(actual) - Color.green(expected))).isAtMost(tolerance)
        assertThat(abs(Color.blue(actual) - Color.blue(expected))).isAtMost(tolerance)
    }
}
