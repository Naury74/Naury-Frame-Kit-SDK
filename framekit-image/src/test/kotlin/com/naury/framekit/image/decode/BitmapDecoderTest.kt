package com.naury.framekit.image.decode

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.naury.framekit.android.result.EditorErrorCode
import com.naury.framekit.android.result.FrameKitException
import com.naury.framekit.core.geometry.ExifOrientation
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

    private fun assertAllOrientationsUpright() {
        val upright = TestImages.quadrants(64, 32)
        ExifOrientation.entries.forEach { orientation ->
            val file = File(context.cacheDir, "orientation_${orientation.exifValue}.jpg")
            TestImages.writeOrientedJpeg(upright, orientation, file)
            val resolver = FileSourceResolver("s" to file)

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
}
