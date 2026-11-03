package com.naury.framekit.core.pdf

import com.naury.framekit.core.model.PixelSize
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PdfWriterCommonTest {

    private class Buffer : PdfOutput {
        val bytes = ArrayList<Byte>()
        var closed = false

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            for (i in offset until offset + length) this.bytes += bytes[i]
        }

        override fun close() {
            closed = true
        }
    }

    @Test
    fun writesCompletePdfWithTextLayer() {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0, 0, 0xFF.toByte(), 0xD9.toByte())
        val layout = PdfLayout.layout(PixelSize(100, 50), PdfPageSize.A4, PdfOrientation.AUTO, 10.0, 200, 16_000_000)
        val buffer = Buffer()
        PdfWriter(buffer).use { it.addPage(jpeg, 100, 50, layout, text = listOf(PdfTextLine("가나", 1f, 2f, 30f, 10f))) }
        val text = buffer.bytes.toByteArray().decodeToString()
        assertTrue(buffer.closed)
        assertTrue(text.startsWith("%PDF-1.4"))
        assertTrue("<AC00B098> Tj" in text)
        assertTrue(text.trimEnd().endsWith("%%EOF"))
    }

    @Test
    fun skipsBrokenTextBoxesAndRejectsBrokenLayout() {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0, 0)
        val layout = PdfLayout.layout(PixelSize(100, 50), PdfPageSize.A4, PdfOrientation.AUTO, 10.0, 200, 16_000_000)
        val buffer = Buffer()
        PdfWriter(buffer).use { writer ->
            writer.addPage(jpeg, 100, 50, layout, text = listOf(PdfTextLine("A", Float.NaN, 0f, 10f, 10f)))
            assertFailsWith<IllegalArgumentException> { writer.addPage(jpeg, 100, 50, layout.copy(imageWidthPt = Double.NaN)) }
        }
        val text = buffer.bytes.toByteArray().decodeToString()
        assertFalse("NaN" in text)
        assertFalse("/F0" in text)
    }

    @Test
    fun failedWriteStopsWriterButStillClosesOutput() {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0, 0)
        val layout = PdfLayout.layout(PixelSize(100, 50), PdfPageSize.A4, PdfOrientation.AUTO, 10.0, 200, 16_000_000)
        var closed = false
        val output = object : PdfOutput {
            override fun write(bytes: ByteArray, offset: Int, length: Int) = throw IllegalStateException("disk full")
            override fun close() {
                closed = true
            }
        }
        val writer = PdfWriter(output)
        // 버퍼보다 커서 바로 쓴다.
        assertFailsWith<IllegalStateException> { writer.addPage(jpeg + ByteArray(20_000), 100, 50, layout) }
        assertFailsWith<IllegalStateException> { writer.addPage(jpeg, 100, 50, layout) }
        assertFailsWith<IllegalStateException> { writer.close() }
        assertTrue(closed)
    }
}
