package com.naury.framekit.image.export

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.naury.framekit.android.output.AppFileOutputStore
import com.naury.framekit.android.result.EditorErrorCode
import com.naury.framekit.android.result.FrameKitException
import com.naury.framekit.core.geometry.ExifOrientation
import com.naury.framekit.core.geometry.GeometryEdit
import com.naury.framekit.core.geometry.GeometryOperations
import com.naury.framekit.core.geometry.RectN
import com.naury.framekit.core.model.ImageProject
import com.naury.framekit.core.model.ProjectId
import com.naury.framekit.core.model.SourceId
import com.naury.framekit.image.decode.ImageMetadataReader
import com.naury.framekit.image.decode.ImageSourceInfo
import com.naury.framekit.image.testing.FileSourceResolver
import com.naury.framekit.image.testing.TestImages
import com.naury.framekit.image.testing.colorNear
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImageExportCoordinatorTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val sourceId = SourceId("s")
    private val sourceFile = File(context.cacheDir, "source.jpg")
    private val resolver = FileSourceResolver("s" to sourceFile)
    private var freeBytes = Long.MAX_VALUE
    private val store = AppFileOutputStore(context, availableBytes = { freeBytes })
    private val coordinator = ImageExportCoordinator(resolver, store, memoryBudgetBytes = 512L * 1024 * 1024, dispatcher = Dispatchers.Unconfined)
    private val project = ImageProject(ProjectId("p"), sourceId)

    @Before
    fun clearFileProviderCache() {
        // Robolectric은 테스트마다 data 폴더를 새로 만들지만 FileProvider는 root 경로를 static으로 캐시한다.
        FileProvider::class.java.getDeclaredField("sCache").apply { isAccessible = true }.let {
            (it.get(null) as MutableMap<*, *>).clear()
        }
    }

    @Test
    fun `rotated and cropped JPEG export has upright pixels and new dimensions`() = runBlocking {
        TestImages.writeOrientedJpeg(TestImages.quadrants(400, 200), ExifOrientation.ROTATE_90, sourceFile)
        val geometry = GeometryOperations.rotateClockwise(GeometryEdit(crop = RectN(0.0, 0.0, 0.5, 1.0)))

        val result = coordinator.export(project.copy(geometry = geometry), info(), ImageExportConfig())

        assertThat(result.uri.scheme).isEqualTo("content")
        assertThat(result.mimeType).isEqualTo("image/jpeg")
        assertThat(result.width to result.height).isEqualTo(200 to 200)
        val output = decodeOutput()
        assertThat(output.colorNear(25, 25, TestImages.BOTTOM_LEFT)).isTrue()
        assertThat(output.colorNear(175, 25, TestImages.TOP_LEFT)).isTrue()
        assertThat(result.fileSize).isEqualTo(publishedFiles().single().length())
    }

    @Test
    fun `Q07 transparent PNG becomes the configured background in JPEG`() = runBlocking {
        writePng(TestImages.quadrants(200, 100, hasAlpha = true))

        coordinator.export(project, info(), ImageExportConfig(jpegBackgroundArgb = Color.MAGENTA))

        assertThat(decodeOutput().colorNear(175, 75, Color.MAGENTA, tolerance = 12)).isTrue()
    }

    @Test
    fun `PNG export keeps transparency`() = runBlocking {
        writePng(TestImages.quadrants(200, 100, hasAlpha = true))

        val result = coordinator.export(project, info(), ImageExportConfig(format = ImageFormat.PNG))

        assertThat(result.mimeType).isEqualTo("image/png")
        assertThat(publishedFiles().single().name).endsWith(".png")
        assertThat(Color.alpha(decodeOutput().getPixel(175, 75))).isEqualTo(0)
    }

    @Test
    fun `Q08 SAFE metadata keeps capture time and drops location and orientation`() = runBlocking {
        TestImages.writeOrientedJpeg(TestImages.quadrants(64, 32), ExifOrientation.ROTATE_90, sourceFile)
        ExifInterface(sourceFile).apply {
            setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2026:10:06 09:30:00")
            setLatLong(37.5665, 126.9780)
            setAttribute(ExifInterface.TAG_BODY_SERIAL_NUMBER, "SERIAL-123")
            setAttribute(ExifInterface.TAG_USER_COMMENT, "private note")
            saveAttributes()
        }

        coordinator.export(project, info(), ImageExportConfig())

        val exif = ExifInterface(publishedFiles().single())
        assertThat(exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)).isEqualTo("2026:10:06 09:30:00")
        assertThat(exif.latLong).isNull()
        assertThat(exif.getAttribute(ExifInterface.TAG_BODY_SERIAL_NUMBER)).isNull()
        assertThat(exif.getAttribute(ExifInterface.TAG_USER_COMMENT)).isNull()
        assertThat(exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, 0)).isEqualTo(ExifInterface.ORIENTATION_NORMAL)
        assertThat(exif.hasThumbnail()).isFalse()
        assertThat(exif.getAttributeInt(ExifInterface.TAG_PIXEL_X_DIMENSION, 0)).isEqualTo(64)
    }

    @Test
    fun `max width limits the output without changing aspect`() = runBlocking {
        writePng(TestImages.quadrants(400, 200))

        val result = coordinator.export(project, info(), ImageExportConfig(maxWidth = 100))

        assertThat(result.width to result.height).isEqualTo(100 to 50)
    }

    @Test
    fun `memory budget overflow fails before any file is written`() {
        writePng(TestImages.quadrants(400, 200))
        val tight = ImageExportCoordinator(resolver, store, memoryBudgetBytes = 1024L, dispatcher = Dispatchers.Unconfined)

        val error = assertThrows(FrameKitException::class.java) {
            runBlocking { tight.export(project, info(), ImageExportConfig()) }
        }

        assertThat(error.code).isEqualTo(EditorErrorCode.INSUFFICIENT_MEMORY)
        assertThat(store.directory.listFiles().orEmpty()).isEmpty()
    }

    @Test
    fun `Q09 low storage fails before writing and leaves no partial file`() {
        writePng(TestImages.quadrants(400, 200))
        freeBytes = 10_000L

        val error = assertThrows(FrameKitException::class.java) {
            runBlocking { coordinator.export(project, info(), ImageExportConfig()) }
        }

        assertThat(error.code).isEqualTo(EditorErrorCode.INSUFFICIENT_STORAGE)
        assertThat(store.directory.listFiles().orEmpty()).isEmpty()
    }

    @Test
    fun `invalid quality is an invalid configuration`() {
        writePng(TestImages.quadrants(40, 20))

        val error = assertThrows(FrameKitException::class.java) {
            runBlocking { coordinator.export(project, info(), ImageExportConfig(quality = 101)) }
        }

        assertThat(error.code).isEqualTo(EditorErrorCode.INVALID_CONFIGURATION)
    }

    @Test
    fun `cancellation while encoding removes the partial file and publishes nothing`() {
        writePng(TestImages.quadrants(400, 200))

        assertThrows(CancellationException::class.java) {
            runBlocking {
                lateinit var job: kotlinx.coroutines.Deferred<*>
                job = async {
                    coordinator.export(project, info(), ImageExportConfig()) { stage ->
                        if (stage == ImageExportStage.ENCODING) job.cancel()
                    }
                }
                job.await()
            }
        }

        assertThat(store.directory.listFiles().orEmpty()).isEmpty()
    }

    private fun info(): ImageSourceInfo = ImageMetadataReader(resolver).read(sourceId)

    private fun writePng(bitmap: Bitmap) {
        sourceFile.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun publishedFiles(): List<File> = store.directory.listFiles().orEmpty().filterNot { it.name.startsWith(".") }

    private fun decodeOutput(): Bitmap = BitmapFactory.decodeFile(publishedFiles().single().absolutePath)
}
