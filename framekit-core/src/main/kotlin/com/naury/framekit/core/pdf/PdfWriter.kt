package com.naury.framekit.core.pdf

import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.OutputStream
import java.util.Locale

/**
 * 인식한 글자 한 줄. 좌표는 쪽 JPEG의 픽셀 단위이며 왼쪽 위가 원점이다.
 *
 * @property text 줄의 글자. 빈 문자열은 쓰지 않는다.
 * @property left 줄 상자의 왼쪽(px).
 * @property top 줄 상자의 위쪽(px).
 * @property width 줄 상자의 폭(px), 양수.
 * @property height 줄 상자의 높이(px), 양수.
 */
public data class PdfTextLine(val text: String, val left: Float, val top: Float, val width: Float, val height: Float)

/**
 * JPEG 이미지 쪽으로 이루어진 PDF 1.4 파일을 순서대로 쓴다.
 *
 * 쪽마다 JPEG 바이트를 그대로(DCTDecode) 넣으므로 다시 압축하지 않아 파일이 작고, 쪽을 쓰는 즉시
 * 스트림으로 내보내 메모리는 한 쪽만큼만 쓴다. [close] 전까지는 PDF가 완성되지 않는다.
 *
 * 글자 인식 결과를 넘기면 이미지 위에 보이지 않는 글자 레이어(렌더링 모드 3)를 겹쳐, PDF 뷰어에서 검색·선택·
 * 복사가 된다. 글꼴은 파일에 넣지 않는 Identity-H CID 글꼴이며 ToUnicode 표로 유니코드를 되찾는다.
 * 기본 다국어 평면(BMP) 밖의 문자(일부 이모지 등)는 레이어에서 뺀다.
 */
public class PdfWriter(output: OutputStream, private val producer: String = "FrameKit") : Closeable {

    private val out = CountingStream(BufferedOutputStream(output))
    private val offsets = sortedMapOf<Int, Long>()
    private val pageIds = mutableListOf<Int>()
    private var nextId = FIRST_PAGE_ID
    private var closed = false
    private var fontId = 0

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
     * @param text 이 쪽에 겹칠 보이지 않는 글자 줄. 비어 있으면 글자 레이어를 만들지 않는다.
     */
    public fun addPage(
        jpeg: ByteArray,
        pixelWidth: Int,
        pixelHeight: Int,
        layout: PdfPageLayout,
        grayscale: Boolean = false,
        text: List<PdfTextLine> = emptyList(),
    ) {
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

        val textOps = textLayer(text, pixelWidth, pixelHeight, layout)
        if (textOps.isNotEmpty() && fontId == 0) writeFont()
        val content = String.format(
            Locale.ROOT,
            "q %.3f 0 0 %.3f %.3f %.3f cm /Im0 Do Q\n",
            layout.imageWidthPt, layout.imageHeightPt, layout.imageXPt, layout.imageYPt,
        ) + textOps
        val contentBytes = content.toByteArray(Charsets.US_ASCII)
        beginObject(contentId)
        out.write("<< /Length ${contentBytes.size} >>\nstream\n")
        out.write(contentBytes)
        out.write("\nendstream\nendobj\n")

        val mediaBox = String.format(Locale.ROOT, "[0 0 %.3f %.3f]", layout.pageWidthPt, layout.pageHeightPt)
        writeObject(
            pageId,
            "<< /Type /Page /Parent $PAGES_ID 0 R /MediaBox $mediaBox " +
                "/Resources << /XObject << /Im0 $imageId 0 R >>${if (textOps.isNotEmpty()) " /Font << /F0 $fontId 0 R >>" else ""} >> /Contents $contentId 0 R >>",
        )
        pageIds += pageId
    }

    // 줄마다 상자 폭에 맞게 가로 비율(Tz)을 정해, 뷰어에서 선택 영역이 이미지의 글자 위에 겹치게 한다.
    private fun textLayer(lines: List<PdfTextLine>, pixelWidth: Int, pixelHeight: Int, layout: PdfPageLayout): String {
        val sx = layout.imageWidthPt / pixelWidth
        val sy = layout.imageHeightPt / pixelHeight
        val builder = StringBuilder()
        lines.forEach { line ->
            val units = line.text.filter { !it.isSurrogate() && !it.isISOControl() }
            if (units.isBlank() || line.width <= 0f || line.height <= 0f) return@forEach
            val fontSize = line.height * sy
            val widthPt = line.width * sx
            // 글꼴을 넣지 않아 글자 폭은 기본값 1em(DW 1000)으로 계산된다.
            val scale = (100.0 * widthPt / (units.length * fontSize)).coerceIn(1.0, 1000.0)
            val x = layout.imageXPt + line.left * sx
            // 기준선은 상자 아래에서 높이의 20% 위로 둔다(대부분 글꼴의 아래 내림 비율).
            val y = layout.imageYPt + layout.imageHeightPt - (line.top + line.height * 0.8f) * sy
            val hex = units.map { String.format(Locale.ROOT, "%04X", it.code) }.joinToString("")
            builder.append(String.format(Locale.ROOT, "BT /F0 %.2f Tf 3 Tr %.2f Tz 1 0 0 1 %.3f %.3f Tm <%s> Tj ET\n", fontSize, scale, x, y, hex))
        }
        return builder.toString()
    }

    private fun writeFont() {
        val type0 = nextId
        val cidFont = nextId + 1
        val descriptor = nextId + 2
        val toUnicode = nextId + 3
        nextId += 4
        fontId = type0
        writeObject(
            type0,
            "<< /Type /Font /Subtype /Type0 /BaseFont /FrameKitOcr /Encoding /Identity-H " +
                "/DescendantFonts [$cidFont 0 R] /ToUnicode $toUnicode 0 R >>",
        )
        writeObject(
            cidFont,
            "<< /Type /Font /Subtype /CIDFontType2 /BaseFont /FrameKitOcr " +
                "/CIDSystemInfo << /Registry (Adobe) /Ordering (Identity) /Supplement 0 >> " +
                "/FontDescriptor $descriptor 0 R /DW 1000 /CIDToGIDMap /Identity >>",
        )
        writeObject(
            descriptor,
            "<< /Type /FontDescriptor /FontName /FrameKitOcr /Flags 4 /FontBBox [0 -200 1000 800] " +
                "/ItalicAngle 0 /Ascent 800 /Descent -200 /CapHeight 700 /StemV 80 >>",
        )
        // CID를 그 값의 유니코드(BMP)로 되돌리는 표. bfrange는 한 블록에 100개까지라 256개를 나눠 쓴다.
        val cmap = StringBuilder()
        cmap.append("/CIDInit /ProcSet findresource begin\n12 dict begin\nbegincmap\n")
        cmap.append("/CIDSystemInfo << /Registry (Adobe) /Ordering (UCS) /Supplement 0 >> def\n")
        cmap.append("/CMapName /FrameKit-UCS def\n/CMapType 2 def\n")
        cmap.append("1 begincodespacerange\n<0000> <FFFF>\nendcodespacerange\n")
        (0 until 256).chunked(100).forEach { chunk ->
            cmap.append("${chunk.size} beginbfrange\n")
            chunk.forEach { high ->
                val prefix = String.format(Locale.ROOT, "%02X", high)
                cmap.append("<${prefix}00> <${prefix}FF> <${prefix}00>\n")
            }
            cmap.append("endbfrange\n")
        }
        cmap.append("endcmap\nCMapName currentdict /CMap defineresource pop\nend\nend\n")
        val bytes = cmap.toString().toByteArray(Charsets.US_ASCII)
        beginObject(toUnicode)
        out.write("<< /Length ${bytes.size} >>\nstream\n")
        out.write(bytes)
        out.write("\nendstream\nendobj\n")
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
