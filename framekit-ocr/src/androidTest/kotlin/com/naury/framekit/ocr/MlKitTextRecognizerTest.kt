package com.naury.framekit.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.naury.framekit.android.result.EditorErrorCode
import com.naury.framekit.android.result.FrameKitException
import com.naury.framekit.android.result.FrameKitResult
import com.naury.framekit.image.export.ImageExportConfig
import com.naury.framekit.image.export.ImageFormat
import com.naury.framekit.image.headless.ImageProcessor
import com.naury.framekit.image.ocr.TextRecognizers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** 실제 ML Kit 모델로 한국어·영어 글자를 인식하고, PDF에 글자 레이어가 들어가는지 확인한다. Play 서비스가 필요하다. */
@RunWith(AndroidJUnit4::class)
class MlKitTextRecognizerTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun document(): Bitmap = Bitmap.createBitmap(1200, 900, Bitmap.Config.ARGB_8888).apply {
        val canvas = Canvas(this)
        canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 64f; typeface = Typeface.DEFAULT_BOLD }
        canvas.drawText("견적서 ESTIMATE", 80f, 160f, paint)
        paint.textSize = 52f
        canvas.drawText("금액 1,200,000원", 80f, 320f, paint)
        canvas.drawText("Invoice No. 2026-1008", 80f, 460f, paint)
    }

    // 모델을 아직 내려받는 중이면 건너뛴다(기기·네트워크 상태에 따라 다르다).
    private suspend fun recognizeOrSkip(image: Bitmap) = try {
        MlKitTextRecognizer(context).recognize(image)
    } catch (error: FrameKitException) {
        assumeTrue("모델 다운로드 중", error.code != EditorErrorCode.UNSUPPORTED_OPERATION)
        throw error
    }

    @Test
    fun koreanAndLatinLinesAreRecognizedWithBoxes() = runBlocking<Unit> {
        val lines = recognizeOrSkip(document())
        val text = lines.joinToString("\n") { it.text }
        assertThat(text).contains("ESTIMATE")
        assertThat(text).contains("견적서")
        assertThat(text.replace(" ", "")).contains("2026-1008")
        val first = lines.first { it.text.contains("ESTIMATE") }
        // 글자를 그린 위치(왼쪽 80px, 기준선 160px) 근처의 상자다.
        assertThat(first.left).isLessThan(140f)
        assertThat(first.bottom).isGreaterThan(120f)
        assertThat(first.top).isLessThan(160f)
    }

    @Test
    fun registeredRecognizerAddsATextLayerToPdfExports() = runBlocking<Unit> {
        assertThat(TextRecognizers.find(context)).isInstanceOf(MlKitTextRecognizer::class.java)
        recognizeOrSkip(document())
        val processor = ImageProcessor(context)
        val scope = MainScope()
        try {
            val source = processor.open(document())
            val result = processor.startPdfExport(listOf(processor.newProject(source) to source), ImageExportConfig(format = ImageFormat.PDF), scope).awaitResult()
            val success = result as FrameKitResult.Success
            assertThat(success.output.warnings).isEmpty()
            val bytes = context.contentResolver.openInputStream(success.output.uri)!!.use { it.readBytes() }
            val pdf = String(bytes, Charsets.ISO_8859_1)
            assertThat(pdf).contains("/ToUnicode")
            assertThat(pdf).contains("3 Tr")
            // "ESTIMATE"의 앞 두 글자 E(0045)·S(0053) 코드가 글자 레이어에 들어 있다.
            assertThat(pdf).contains("<00450053")
            processor.deleteOutput(success.output.uri)
        } finally {
            scope.cancel()
            processor.close()
        }
    }
}
