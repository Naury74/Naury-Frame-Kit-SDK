package com.naury.framekit.image.document

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.naury.framekit.android.result.FrameKitException
import com.naury.framekit.core.document.DocumentQuad
import com.naury.framekit.core.geometry.PointN
import com.naury.framekit.image.decode.ImageMetadataReader
import com.naury.framekit.image.testing.FileSourceResolver
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DocumentRectifierTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()

    // 어두운 바탕 위에 비스듬한 흰 종이를 그리고, 종이의 왼쪽 위 1/4에는 빨간 표시를 둔다.
    private val corners = listOf(PointN(0.25, 0.15), PointN(0.80, 0.20), PointN(0.85, 0.85), PointN(0.15, 0.80))

    private fun photo(width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(40, 40, 40))
        val paper = Path().apply {
            moveTo((corners[0].x * width).toFloat(), (corners[0].y * height).toFloat())
            corners.drop(1).forEach { lineTo((it.x * width).toFloat(), (it.y * height).toFloat()) }
            close()
        }
        canvas.drawPath(paper, Paint().apply { color = Color.WHITE; isAntiAlias = true })
        // 종이 왼쪽 위 근처(모서리에서 안쪽)에 빨간 점.
        val mark = PointN(corners[0].x + 0.06, corners[0].y + 0.08)
        canvas.drawCircle((mark.x * width).toFloat(), (mark.y * height).toFloat(), width * 0.03f, Paint().apply { color = Color.RED })
        return bitmap
    }

    @Test
    fun `detected corners are flattened into an upright page`() {
        val image = photo(600, 800)
        val quad = DocumentRectifier.detect(image)!!
        assertThat(abs(quad.topLeft.x - 0.25)).isLessThan(0.03)
        assertThat(abs(quad.bottomRight.y - 0.85)).isLessThan(0.03)

        val sourceFile = File(context.cacheDir, "doc.png").apply { outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        val resolver = FileSourceResolver("doc" to sourceFile)
        val info = ImageMetadataReader(resolver).read(com.naury.framekit.core.model.SourceId("doc"))
        val output = File(context.cacheDir, "flat.jpg").apply { delete() }
        val size = DocumentRectifier(resolver, memoryBudgetBytes = 256L * 1024 * 1024).rectify(info, quad, output)

        val flat = BitmapFactory.decodeFile(output.absolutePath)
        assertThat(flat.width).isEqualTo(size.width)
        // 마주 보는 변 중 긴 쪽 길이로 펴진다(아래 변 약 422px, 오른쪽 변 약 523px).
        assertThat(abs(size.width - 422)).isLessThan(20)
        assertThat(abs(size.height - 523)).isLessThan(20)
        // 펴진 쪽의 네 모서리 안쪽은 종이(흰색)이고, 빨간 표시는 왼쪽 위에 있다.
        listOf(0.05 to 0.05, 0.95 to 0.05, 0.95 to 0.95, 0.05 to 0.95).forEach { (fx, fy) ->
            val c = flat.getPixel((fx * flat.width).toInt(), (fy * flat.height).toInt())
            assertThat(Color.red(c)).isGreaterThan(200)
            assertThat(Color.blue(c)).isGreaterThan(200)
        }
        val red = flat.getPixel((0.11 * flat.width).toInt(), (0.12 * flat.height).toInt())
        assertThat(Color.red(red)).isGreaterThan(180)
        assertThat(Color.green(red)).isLessThan(90)
    }

    @Test
    fun `twisted corners are rejected and flat images find nothing`() {
        val sourceFile = File(context.cacheDir, "flat-src.png").apply { outputStream().use { photo(100, 100).compress(Bitmap.CompressFormat.PNG, 100, it) } }
        val resolver = FileSourceResolver("doc" to sourceFile)
        val info = ImageMetadataReader(resolver).read(com.naury.framekit.core.model.SourceId("doc"))
        val twisted = DocumentQuad(PointN(0.1, 0.1), PointN(0.9, 0.9), PointN(0.9, 0.1), PointN(0.1, 0.9))
        assertThrows(FrameKitException::class.java) {
            DocumentRectifier(resolver, memoryBudgetBytes = 64L * 1024 * 1024).rectify(info, twisted, File(context.cacheDir, "x.jpg"))
        }
        val gray = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GRAY) }
        assertThat(DocumentRectifier.detect(gray)).isNull()
    }
}
