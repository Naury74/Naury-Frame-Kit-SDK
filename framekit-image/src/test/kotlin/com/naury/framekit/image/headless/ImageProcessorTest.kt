package com.naury.framekit.image.headless

import com.naury.framekit.core.pdf.PdfPageSize
import com.naury.framekit.image.export.PdfOptions
import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.naury.framekit.android.export.ExportState
import com.naury.framekit.android.input.EditorInput
import com.naury.framekit.android.result.EditorErrorCode
import com.naury.framekit.android.result.FrameKitException
import com.naury.framekit.android.result.FrameKitResult
import com.naury.framekit.core.effect.FilterSelection
import com.naury.framekit.core.geometry.GeometryEdit
import com.naury.framekit.core.geometry.RectN
import com.naury.framekit.image.cutout.BackgroundRemover
import com.naury.framekit.image.effect.CpuColorEffectRenderer
import com.naury.framekit.image.export.ImageExportConfig
import com.naury.framekit.image.export.ImageFormat
import com.naury.framekit.image.testing.TestImages
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Before
import com.naury.framekit.image.ocr.TextRecognizer
import com.naury.framekit.image.ocr.RecognizedLine
import com.naury.framekit.android.result.ExportWarning
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImageProcessorTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val exportDirectory = File(context.filesDir, "framekit/exports")

    @Before
    fun clearFileProviderCache() {
        FileProvider::class.java.getDeclaredField("sCache").apply { isAccessible = true }.let {
            (it.get(null) as MutableMap<*, *>).clear()
        }
    }

    @Test
    fun `bitmap input exports a cropped filtered file without any UI`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val processor = ImageProcessor(context, CpuColorEffectRenderer, ioDispatcher = dispatcher, exportDispatcher = dispatcher)
        val source = processor.open(TestImages.quadrants(200, 100))
        val project = processor.newProject(source).copy(
            geometry = GeometryEdit(crop = RectN(0.0, 0.0, 0.5, 1.0)),
            filter = FilterSelection("mono", 1.0),
        )

        val handle = processor.startExport(project, source, ImageExportConfig(format = ImageFormat.PNG), this)
        val result = handle.awaitResult() as FrameKitResult.Success

        assertThat(result.output.width to result.output.height).isEqualTo(100 to 100)
        assertThat(handle.state.value).isInstanceOf(ExportState.Completed::class.java)
        assertThat(published()).hasSize(1)
        processor.close()
        assertThat(File(context.cacheDir, "framekit/imports").listFiles().orEmpty()).isEmpty()
    }

    @Test
    fun `several photos become one pdf document or one document each`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val processor = ImageProcessor(context, CpuColorEffectRenderer, ioDispatcher = dispatcher, exportDispatcher = dispatcher)
        val first = processor.open(TestImages.quadrants(400, 300))
        val second = processor.open(TestImages.quadrants(300, 400))
        val pages = listOf(processor.newProject(first) to first, processor.newProject(second) to second)

        val combined = processor.startPdfExport(pages, ImageExportConfig(format = ImageFormat.PDF), this).awaitResult() as FrameKitResult.Success
        assertThat(combined.outputs).hasSize(1)
        assertThat(combined.output.pageCount).isEqualTo(2)
        assertThat(combined.output.mimeType).isEqualTo("application/pdf")
        // 첫 사진이 가로로 길어 A4 가로(842×595pt) 쪽이 된다.
        assertThat(combined.output.width to combined.output.height).isEqualTo(842 to 595)
        val bytes = published().single().readBytes()
        assertThat(String(bytes, 0, 5, Charsets.US_ASCII)).isEqualTo("%PDF-")
        assertThat(String(bytes, Charsets.ISO_8859_1)).contains("/Count 2")

        published().forEach(File::delete)
        val separate = ImageExportConfig(format = ImageFormat.PDF, pdf = PdfOptions(combinePages = false, pageSize = PdfPageSize.FIT_IMAGE, marginMm = 0.0))
        val each = processor.startPdfExport(pages, separate, this).awaitResult() as FrameKitResult.Success
        assertThat(each.outputs.map { it.pageCount }).containsExactly(1, 1)
        assertThat(published()).hasSize(2)
        processor.close()
    }

    @Test
    fun `recognized text is added to the pdf and recognition failures only warn`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        var seen: Pair<Int, Int>? = null
        val recognizer = object : TextRecognizer {
            override suspend fun recognize(image: Bitmap): List<RecognizedLine> {
                seen = image.width to image.height
                return listOf(RecognizedLine("검색되는 글자", 10f, 10f, 300f, 40f))
            }
        }
        val processor = ImageProcessor(context, CpuColorEffectRenderer, dispatcher, dispatcher, textRecognizer = recognizer)
        val source = processor.open(TestImages.quadrants(400, 300))
        val result = processor.startPdfExport(listOf(processor.newProject(source) to source), scope = this).awaitResult() as FrameKitResult.Success
        // 인식기는 쪽에 넣는 이미지 그대로를 받는다.
        assertThat(seen).isEqualTo(400 to 300)
        assertThat(result.output.warnings).isEmpty()
        assertThat(result.output.recognizedText).isEqualTo("검색되는 글자")
        val pdf = String(published().single().readBytes(), Charsets.ISO_8859_1)
        assertThat(pdf).contains("/ToUnicode")
        assertThat(pdf).contains("3 Tr")
        published().forEach(File::delete)
        processor.close()

        val failing = object : TextRecognizer {
            override suspend fun recognize(image: Bitmap): List<RecognizedLine> = throw FrameKitException(EditorErrorCode.UNSUPPORTED_OPERATION, "model downloading")
        }
        val second = ImageProcessor(context, CpuColorEffectRenderer, dispatcher, dispatcher, textRecognizer = failing)
        val page = second.open(TestImages.quadrants(200, 100))
        val skipped = second.startPdfExport(listOf(second.newProject(page) to page), scope = this).awaitResult() as FrameKitResult.Success
        assertThat(skipped.output.warnings).containsExactly(ExportWarning.TEXT_RECOGNITION_SKIPPED)
        assertThat(String(published().single().readBytes(), Charsets.ISO_8859_1)).doesNotContain("/ToUnicode")
        second.close()
    }

    @Test
    fun `pdf export rejects an empty page list and bad options before starting`() = runTest {
        val processor = ImageProcessor(context, CpuColorEffectRenderer)
        val source = processor.open(TestImages.quadrants(40, 40))

        assertThrows(FrameKitException::class.java) { processor.startPdfExport(emptyList(), scope = this) }
        assertThrows(FrameKitException::class.java) {
            processor.startPdfExport(listOf(processor.newProject(source) to source), ImageExportConfig(pdf = PdfOptions(dpi = 10)), this)
        }
        processor.close()
    }

    @Test
    fun `background removal works headless with an injected remover`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val remover = object : BackgroundRemover {
            override suspend fun subjectMask(image: Bitmap): Bitmap = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888).apply {
                for (y in 0 until height) for (x in 0 until width / 2) setPixel(x, y, Color.WHITE)
            }
        }
        val processor = ImageProcessor(context, CpuColorEffectRenderer, dispatcher, dispatcher, remover)
        val source = processor.open(TestImages.quadrants(200, 100))

        val project = processor.removeBackground(processor.newProject(source), source)
        processor.startExport(project, source, ImageExportConfig(format = ImageFormat.PNG), this).awaitResult()

        val output = BitmapFactory.decodeFile(published().single().absolutePath)
        assertThat(Color.alpha(output.getPixel(150, 50))).isEqualTo(0)
        processor.close()
    }

    @Test
    fun `background removal without the optional module is unsupported`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val processor = ImageProcessor(context, CpuColorEffectRenderer, dispatcher, dispatcher)
        val source = processor.open(TestImages.quadrants(40, 20))

        val error = runCatching { processor.removeBackground(processor.newProject(source), source) }.exceptionOrNull() as FrameKitException

        assertThat(processor.canRemoveBackground).isFalse()
        assertThat(error.code).isEqualTo(EditorErrorCode.UNSUPPORTED_OPERATION)
    }

    @Test
    fun `cancelling a running export leaves no file`() = runTest {
        val processor = ImageProcessor(
            context,
            CpuColorEffectRenderer,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
            exportDispatcher = StandardTestDispatcher(testScheduler),
        )
        val source = processor.open(TestImages.quadrants(200, 100))

        val handle = processor.startExport(processor.newProject(source), source, ImageExportConfig(), this)
        handle.cancel()

        assertThat(handle.awaitResult()).isEqualTo(FrameKitResult.Cancelled)
        assertThat(handle.state.value).isEqualTo(ExportState.Cancelled)
        assertThat(published()).isEmpty()
    }

    @Test
    fun `invalid project is rejected before the export starts`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val processor = ImageProcessor(context, CpuColorEffectRenderer, ioDispatcher = dispatcher, exportDispatcher = dispatcher)
        val source = processor.open(TestImages.quadrants(40, 20))
        val broken = processor.newProject(source).copy(geometry = GeometryEdit(straightenDegrees = Double.NaN))

        val error = assertThrows(FrameKitException::class.java) { processor.startExport(broken, source, ImageExportConfig(), this) }

        assertThat(error.code).isEqualTo(EditorErrorCode.INVALID_PROJECT)
    }

    @Test
    fun `picker input needs the editor`() = runTest {
        val processor = ImageProcessor(context, CpuColorEffectRenderer, ioDispatcher = UnconfinedTestDispatcher(testScheduler))

        val error = runCatching { processor.open(EditorInput.Pick()) }.exceptionOrNull() as FrameKitException

        assertThat(error.code).isEqualTo(EditorErrorCode.INVALID_CONFIGURATION)
    }

    private fun published(): List<File> = exportDirectory.listFiles().orEmpty().filterNot { it.name.startsWith(".") }
}
