package com.naury.framekit.core.pdf

import com.google.common.truth.Truth.assertThat
import com.naury.framekit.core.model.PixelSize
import org.junit.Assert.assertThrows
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

class PdfWriterTest {

    private fun jpeg(width: Int, height: Int): ByteArray = ByteArrayOutputStream().also {
        ImageIO.write(BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "jpg", it)
    }.toByteArray()

    @Test
    fun `portrait photo fills an A4 page inside the margins without upscaling`() {
        val layout = PdfLayout.layout(PixelSize(3000, 4000), PdfPageSize.A4, PdfOrientation.AUTO, marginMm = 10.0, dpi = 200, maxPixels = 16_000_000)

        assertThat(layout.pageWidthPt).isWithin(0.01).of(595.28)
        assertThat(layout.pageHeightPt).isWithin(0.01).of(841.89)
        // 그림 영역 190×277mm에 3:4 사진을 맞추면 너비가 190mm, 200dpi면 1496px이다.
        assertThat(layout.imageWidthPt).isWithin(0.1).of(PdfLayout.mmToPt(190.0))
        assertThat(layout.pixels.width).isEqualTo(1496)
        assertThat(layout.imageXPt).isWithin(0.01).of(PdfLayout.mmToPt(10.0))
    }

    @Test
    fun `landscape photo turns the page and small photos are not enlarged`() {
        val layout = PdfLayout.layout(PixelSize(800, 600), PdfPageSize.A4, PdfOrientation.AUTO, 10.0, 300, 16_000_000)

        assertThat(layout.pageWidthPt).isGreaterThan(layout.pageHeightPt)
        assertThat(layout.pixels).isEqualTo(PixelSize(800, 600))
    }

    @Test
    fun `fit image pages follow the photo size at the chosen dpi`() {
        val layout = PdfLayout.layout(PixelSize(1440, 720), PdfPageSize.FIT_IMAGE, PdfOrientation.AUTO, 0.0, 144, 16_000_000)

        assertThat(layout.pageWidthPt).isWithin(0.01).of(720.0)
        assertThat(layout.pageHeightPt).isWithin(0.01).of(360.0)
    }

    @Test
    fun `written document has valid offsets and every page`() {
        val bytes = ByteArrayOutputStream()
        val layout = PdfLayout.layout(PixelSize(40, 30), PdfPageSize.A5, PdfOrientation.AUTO, 5.0, 150, 1_000_000)
        PdfWriter(bytes).use { writer ->
            writer.addPage(jpeg(40, 30), 40, 30, layout)
            writer.addPage(jpeg(40, 30), 40, 30, layout)
        }
        val data = bytes.toByteArray()
        val text = String(data, Charsets.ISO_8859_1)

        assertThat(text).startsWith("%PDF-1.4")
        assertThat(text.trimEnd()).endsWith("%%EOF")
        assertThat(text).contains("/Count 2")
        // xref의 각 위치는 "N 0 obj"를 가리켜야 한다.
        val xrefStart = text.substringAfterLast("startxref\n").substringBefore("\n").toInt()
        val rows = text.substring(xrefStart).lines().drop(2).takeWhile { it.matches(Regex("\\d{10} \\d{5} [nf] ?")) }
        rows.forEachIndexed { index, row ->
            if (row.contains(" n")) {
                val offset = row.substring(0, 10).toInt()
                assertThat(text.substring(offset)).startsWith("${index} 0 obj")
            }
        }
        assertThat(rows.count { it.contains(" n") }).isEqualTo(1 + 1 + 2 * 3 + 1)
    }

    @Test
    fun `recognized text becomes a searchable invisible layer`() {
        val bytes = ByteArrayOutputStream()
        val layout = PdfLayout.layout(PixelSize(800, 600), PdfPageSize.A4, PdfOrientation.AUTO, 10.0, 150, 16_000_000)
        PdfWriter(bytes).use { writer ->
            writer.addPage(
                jpeg(800, 600), 800, 600, layout,
                text = listOf(
                    PdfTextLine("영수증 합계 12,000원", 40f, 60f, 500f, 40f),
                    PdfTextLine("Invoice No. 42", 40f, 140f, 360f, 36f),
                    // 공백·제어 문자만 있는 줄과 크기가 없는 줄은 쓰지 않는다.
                    PdfTextLine("  ", 40f, 220f, 100f, 20f),
                    PdfTextLine("빈 상자", 40f, 260f, 0f, 20f),
                ),
            )
            writer.addPage(jpeg(800, 600), 800, 600, layout)
        }
        Loader.loadPDF(bytes.toByteArray()).use { document ->
            assertThat(document.numberOfPages).isEqualTo(2)
            val stripper = PDFTextStripper().apply { startPage = 1; endPage = 1 }
            val text = stripper.getText(document)
            assertThat(text).contains("영수증 합계 12,000원")
            assertThat(text).contains("Invoice No. 42")
            assertThat(text).doesNotContain("빈 상자")
            val second = PDFTextStripper().apply { startPage = 2; endPage = 2 }.getText(document)
            assertThat(second.trim()).isEmpty()
        }
    }

    @Test
    fun `empty documents and non jpeg data are rejected`() {
        assertThrows(IllegalStateException::class.java) { PdfWriter(ByteArrayOutputStream()).close() }
        val layout = PdfLayout.layout(PixelSize(10, 10), PdfPageSize.A4, PdfOrientation.AUTO, 0.0, 72, 100)
        assertThrows(IllegalArgumentException::class.java) { PdfWriter(ByteArrayOutputStream()).addPage(byteArrayOf(1, 2, 3), 10, 10, layout) }
    }
}
