package com.naury.framekit.core.pdf

import java.io.OutputStream

/** [output]에 PDF를 쓰는 [PdfWriter]를 만든다. [PdfWriter.close]가 [output]도 닫는다. */
public fun PdfWriter(output: OutputStream, producer: String = "FrameKit"): PdfWriter =
    PdfWriter(OutputStreamPdfOutput(output), producer)

private class OutputStreamPdfOutput(private val target: OutputStream) : PdfOutput {
    override fun write(bytes: ByteArray, offset: Int, length: Int) = target.write(bytes, offset, length)

    override fun close() = target.close()
}
