package com.naury.framekit.image.decode

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.naury.framekit.android.result.EditorErrorCode
import com.naury.framekit.android.result.FrameKitException
import com.naury.framekit.core.geometry.ExifOrientation
import com.naury.framekit.core.model.PixelRect
import com.naury.framekit.core.model.PixelSize
import com.naury.framekit.core.model.SourceId
import com.naury.framekit.image.testing.FileSourceResolver
import com.naury.framekit.image.testing.TestImages
import com.naury.framekit.image.testing.colorNear
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** G01 end to end: stored pixels plus EXIF tag must decode to the same upright picture. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BitmapDecoderTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun `G01 ImageDecoder path decodes every EXIF orientation upright`() = assertAllOrientationsUpright()

    @Test
    @Config(sdk = [27])
    fun `G01 BitmapFactory path decodes every EXIF orientation upright`() = assertAllOrientationsUpright()

    @Test
    fun `G01 streaming path without a file location decodes every orientation upright`() =
        assertAllOrientationsUpright(exposeLocation = false)

    @Test
    fun `region decode returns the requested upright area for every orientation`() {
        val upright = TestImages.quadrants(64, 32)
        ExifOrientation.entries.forEach { orientation ->
            val file = File(context.cacheDir, "region_${orientation.exifValue}.jpg")
            TestImages.writeOrientedJpeg(upright, orientation, file)
            val resolver = FileSourceResolver("s" to file)
            val info = ImageMetadataReader(resolver).read(SourceId("s"))

            val decoded = checkNotNull(BitmapDecoder(resolver).decodeRegion(info, PixelRect(32, 0, 64, 32), sampleSize = 1))

            assertWithMessage("region for $orientation").that(decoded.uprightRegion).isEqualTo(PixelRect(32, 0, 64, 32))
            assertWithMessage("size for $orientation").that(PixelSize(decoded.bitmap.width, decoded.bitmap.height)).isEqualTo(PixelSize(32, 32))
            assertWithMessage("top for $orientation").that(decoded.bitmap.colorNear(16, 6, TestImages.TOP_RIGHT)).isTrue()
            assertWithMessage("bottom for $orientation").that(decoded.bitmap.colorNear(16, 26, TestImages.BOTTOM_RIGHT)).isTrue()
        }
    }

    @Test
    fun `header declaring an enormous image is rejected before decoding`() {
        val file = File(context.cacheDir, "huge.png").apply { writeBytes(pngHeaderOnly(30_000, 30_000)) }

        val error = assertThrows(FrameKitException::class.java) {
            ImageMetadataReader(FileSourceResolver("s" to file)).read(SourceId("s"))
        }

        assertThat(error.code).isEqualTo(EditorErrorCode.SOURCE_TOO_LARGE)
    }

    @Test
    fun `sample size reduces the decoded bitmap but keeps the full upright size`() {
        val file = File(context.cacheDir, "large.jpg")
        TestImages.writeOrientedJpeg(TestImages.quadrants(400, 200), ExifOrientation.ROTATE_90, file)
        val resolver = FileSourceResolver("s" to file)
        val info = ImageMetadataReader(resolver).read(SourceId("s"))

        val decoded = BitmapDecoder(resolver).decode(info, sampleSize = 4)

        assertThat(decoded.uprightSize).isEqualTo(PixelSize(400, 200))
        assertThat(decoded.bitmap.width).isEqualTo(100)
        assertThat(decoded.bitmap.height).isEqualTo(50)
    }

    @Test
    fun `empty file is a decode failure`() {
        val file = File(context.cacheDir, "empty.jpg").apply { writeBytes(ByteArray(0)) }

        val error = assertThrows(FrameKitException::class.java) {
            ImageMetadataReader(FileSourceResolver("s" to file)).read(SourceId("s"))
        }

        assertThat(error.code).isEqualTo(EditorErrorCode.DECODE_FAILED)
    }

    @Test
    fun `damaged header is a decode failure`() {
        val file = File(context.cacheDir, "broken.jpg").apply { writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3)) }

        val error = assertThrows(FrameKitException::class.java) {
            ImageMetadataReader(FileSourceResolver("s" to file)).read(SourceId("s"))
        }

        assertThat(error.code).isEqualTo(EditorErrorCode.DECODE_FAILED)
    }

    @Test
    fun `sample size never upscales and halves while the long edge stays above target`() {
        assertThat(SampleSize.forMinimumLongEdge(PixelSize(1000, 800), 2048)).isEqualTo(1)
        assertThat(SampleSize.forMinimumLongEdge(PixelSize(8000, 6000), 2048)).isEqualTo(2)
        assertThat(SampleSize.forMinimumSize(PixelSize(8000, 6000), PixelSize(1900, 1400))).isEqualTo(4)
    }

    private fun assertAllOrientationsUpright(exposeLocation: Boolean = true) {
        val upright = TestImages.quadrants(64, 32)
        ExifOrientation.entries.forEach { orientation ->
            val file = File(context.cacheDir, "orientation_${orientation.exifValue}.jpg")
            TestImages.writeOrientedJpeg(upright, orientation, file)
            val resolver = FileSourceResolver(mapOf("s" to file), exposeLocation)

            val info = ImageMetadataReader(resolver).read(SourceId("s"))
            val bitmap = BitmapDecoder(resolver).decode(info, sampleSize = 1).bitmap

            assertWithMessage("orientation $orientation").that(info.orientation).isEqualTo(orientation)
            assertWithMessage("size for $orientation").that(PixelSize(bitmap.width, bitmap.height)).isEqualTo(PixelSize(64, 32))
            assertWithMessage("top left for $orientation").that(bitmap.colorNear(8, 8, TestImages.TOP_LEFT)).isTrue()
            assertWithMessage("top right for $orientation").that(bitmap.colorNear(56, 8, TestImages.TOP_RIGHT)).isTrue()
            assertWithMessage("bottom left for $orientation").that(bitmap.colorNear(8, 24, TestImages.BOTTOM_LEFT)).isTrue()
            assertWithMessage("bottom right for $orientation").that(bitmap.colorNear(56, 24, TestImages.BOTTOM_RIGHT)).isTrue()
        }
    }

    // IHDR만 있는 PNG. 픽셀 데이터 없이 크기만 선언한 위조 header를 흉내 낸다.
    private fun pngHeaderOnly(width: Int, height: Int): ByteArray {
        fun chunk(type: String, data: ByteArray): ByteArray {
            val crc = java.util.zip.CRC32().apply { update(type.toByteArray()); update(data) }.value
            return java.nio.ByteBuffer.allocate(12 + data.size).putInt(data.size).put(type.toByteArray()).put(data).putInt(crc.toInt()).array()
        }
        val ihdr = java.nio.ByteBuffer.allocate(13).putInt(width).putInt(height).put(8).put(2).put(0).put(0).put(0).array()
        val signature = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        val idat = java.io.ByteArrayOutputStream().also { out ->
            java.util.zip.DeflaterOutputStream(out).use { it.write(ByteArray(64)) }
        }.toByteArray()
        return signature + chunk("IHDR", ihdr) + chunk("IDAT", idat) + chunk("IEND", ByteArray(0))
    }
}
