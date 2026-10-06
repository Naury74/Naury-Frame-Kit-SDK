package com.naury.framekit.core.pdf

import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.OutputStream
import java.util.Locale

/**
 * JPEG 이미지 쪽으로만 이루어진 PDF 1.4 파일을 순서대로 쓴다.
 *
 * 쪽마다 JPEG 바이트를 그대로(DCTDecode) 넣으므로 다시 압축하지 않아 파일이 작고, 쪽을 쓰는 즉시
 * 스트림으로 내보내 메모리는 한 쪽만큼만 쓴다. [close] 전까지는 PDF가 완성되지 않는다.
 */
public class PdfWriter(output: OutputStream, private val producer: String = "FrameKit") : Closeable {

    private val out = CountingStream(BufferedOutputStream(output))
    private val offsets = sortedMapOf<Int, Long>()
    private val pageIds = mutableListOf<Int>()
    private var nextId = FIRST_PAGE_ID
    private var closed = false

    init {
        out.write("%PDF-1.4\n")
        // 이진 파일임을 알리는 주석. 일부 전송 도구가 텍스트로 다루지 않게 한다.
        out.write(byteArrayOf('%'.code.toByte(), 0xE2.toByte(), 0xE3.toByte(), 0xCF.toByte(), 0xD3.toByte(), '\n'.code.toByte()))
        writeObject(CATALOG_ID, "<< /Type /Catalog /Pages $PAGES_ID 0 R >>")
    }

    /** 지금까지 쓴 쪽 수. */
    public val pageCount: Int get() = pageIds.size

    /**
     * 쪽 하나를 쓴다.
     *
     * @param jpeg 기준(baseline) 또는 progressive JPEG 바이트. RGB(3채널) 또는 흑백(1채널).
     * @param pixelWidth JPEG의 픽셀 너비.
     * @param grayscale JPEG가 1채널이면 `true`.
     */
    public fun addPage(jpeg: ByteArray, pixelWidth: Int, pixelHeight: Int, layout: PdfPageLayout, grayscale: Boolean = false) {
        check(!closed) { "Writer is closed" }
        require(pixelWidth > 0 && pixelHeight > 0) { "Image size must be positive" }
        require(jpeg.size > 2 && jpeg[0] == 0xFF.toByte() && jpeg[1] == 0xD8.toByte()) { "Not a JPEG stream" }
        val pageId = nextId
        val contentId = nextId + 1
        val imageId = nextId + 2
        nextId += 3

        val colorSpace = if (grayscale) "/DeviceGray" else "/DeviceRGB"
        beginObject(imageId)
        out.write(
            "<< /Type /XObject /Subtype /Image /Width $pixelWidth /Height $pixelHeight /ColorSpace $colorSpace " +
                "/BitsPerComponent 8 /Filter /DCTDecode /Length ${jpeg.size} >>\nstream\n",
        )
        out.write(jpeg)
        out.write("\nendstream\nendobj\n")

        val content = String.format(
            Locale.ROOT,
            "q %.3f 0 0 %.3f %.3f %.3f cm /Im0 Do Q\n",
            layout.imageWidthPt, layout.imageHeightPt, layout.imageXPt, layout.imageYPt,
        )
        val contentBytes = content.toByteArray(Charsets.US_ASCII)
        beginObject(contentId)
        out.write("<< /Length ${contentBytes.size} >>\nstream\n")
        out.write(contentBytes)
        out.write("\nendstream\nendobj\n")

        val mediaBox = String.format(Locale.ROOT, "[0 0 %.3f %.3f]", layout.pageWidthPt, layout.pageHeightPt)
        writeObject(
            pageId,
            "<< /Type /Page /Parent $PAGES_ID 0 R /MediaBox $mediaBox " +
                "/Resources << /XObject << /Im0 $imageId 0 R >> >> /Contents $contentId 0 R >>",
        )
        pageIds += pageId
    }

    /** 쪽 목록과 교차 참조표를 써서 PDF를 마친다. 쪽이 하나도 없으면 [IllegalStateException]. */
    override fun close() {
        if (closed) return
        closed = true
        try {
            check(pageIds.isNotEmpty()) { "A PDF needs at least one page" }
            writeObject(PAGES_ID, "<< /Type /Pages /Kids [${pageIds.joinToString(" ") { "$it 0 R" }}] /Count ${pageIds.size} >>")
            val infoId = nextId++
            writeObject(infoId, "<< /Producer (${escape(producer)}) >>")
            val xref = out.count
            val size = nextId
            out.write("xref\n0 $size\n0000000000 65535 f \n")
            for (id in 1 until size) {
                val offset = offsets[id]
                out.write(if (offset != null) String.format(Locale.ROOT, "%010d 00000 n \n", offset) else "0000000000 65535 f \n")
            }
            out.write("trailer\n<< /Size $size /Root $CATALOG_ID 0 R /Info $infoId 0 R >>\nstartxref\n$xref\n%%EOF\n")
        } finally {
            out.close()
        }
    }

    private fun beginObject(id: Int) {
        offsets[id] = out.count
        out.write("$id 0 obj\n")
    }

    private fun writeObject(id: Int, body: String) {
        beginObject(id)
        out.write(body)
        out.write("\nendobj\n")
    }

    private fun escape(text: String): String =
        text.filter { it.code in 0x20..0x7E }.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)")

    private class CountingStream(private val target: OutputStream) : OutputStream() {
        var count: Long = 0
            private set

        override fun write(b: Int) {
            target.write(b)
            count++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            target.write(b, off, len)
            count += len
        }

        fun write(text: String) = write(text.toByteArray(Charsets.US_ASCII))

        override fun flush() = target.flush()

        override fun close() = target.close()
    }

    private companion object {
        const val CATALOG_ID = 1
        const val PAGES_ID = 2
        const val FIRST_PAGE_ID = 3
    }
}
