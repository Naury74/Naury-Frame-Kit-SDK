package com.naury.framekit.image.document

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withClip
import androidx.core.graphics.scale
import com.naury.framekit.android.result.EditorErrorCode
import com.naury.framekit.android.result.FrameKitException
import com.naury.framekit.android.source.SourceResolver
import com.naury.framekit.core.document.DocumentDetector
import com.naury.framekit.core.document.DocumentOutline
import com.naury.framekit.core.document.DocumentQuad
import com.naury.framekit.core.document.ScanMode
import com.naury.framekit.core.model.PixelSize
import com.naury.framekit.image.decode.BitmapDecoder
import com.naury.framekit.image.decode.ImageSourceInfo
import com.naury.framekit.image.decode.SampleSize
import java.io.File
import java.io.IOException
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 사진 속 문서를 찾아 반듯하게 펴는(원근 보정) 기능.
 *
 * [detect]는 미리보기 크기 이미지로 네 모서리를 찾고, [rectify]는 원본을 메모리 예산 안에서 다시 디코딩해
 * 네 모서리를 직사각형으로 펴서 JPEG 파일로 쓴다. 결과는 새 원본으로 편집을 이어 가는 데 쓴다.
 *
 * @param memoryBudgetBytes 디코딩한 원본과 결과 bitmap이 함께 쓸 수 있는 최대 바이트.
 */
public class DocumentRectifier(
    resolver: SourceResolver,
    contentResolver: ContentResolver? = null,
    private val memoryBudgetBytes: Long,
) {
    private val decoder = BitmapDecoder(resolver, contentResolver)

    /**
     * 펴낸 문서를 [output]에 JPEG로 쓴다. 긴 변은 [maxLongEdge]를 넘지 않고, 원본보다 키우지 않는다.
     *
     * @param mode 편 뒤 적용할 스캔 보정([ScanEnhancer]). 기본은 색을 살린 스캔.
     * @param outline 종이가 휘거나 접혔을 때의 실제 테두리([DocumentRectifier.analyze]가 찾는다). 모서리가 [quad]와 같고 변이
     *   휘었으면 테두리 곡선을 따라 펴고, 아니면 네 모서리로 편다.
     * @return 결과 픽셀 크기.
     * @throws FrameKitException 모서리가 문서 모양이 아니면 `INVALID_PROJECT`, 메모리가 모자라면
     *   `INSUFFICIENT_MEMORY`, 파일을 쓰지 못하면 `OUTPUT_WRITE_FAILED`, 그 밖의 디코딩 오류.
     */
    public fun rectify(
        info: ImageSourceInfo,
        quad: DocumentQuad,
        output: File,
        mode: ScanMode = ScanMode.COLOR,
        maxLongEdge: Int = MAX_LONG_EDGE,
        quality: Int = JPEG_QUALITY,
        outline: DocumentOutline? = null,
    ): PixelSize {
        if (!quad.isUsable) throw FrameKitException(EditorErrorCode.INVALID_PROJECT, "Corners do not form a document")
        val curved = outline?.takeIf { it.quad == quad && it.isCurved }
        val upright = info.metadata.uprightSize
        val (fullWidth, fullHeight) = curved?.rectifiedSize(upright.width, upright.height) ?: quad.rectifiedSize(upright.width, upright.height)
        // 결과와 디코딩한 원본이 함께 메모리에 있으므로 둘을 합쳐 예산 안에 들게 줄인다.
        val budgetPixels = (memoryBudgetBytes / BYTES_PER_PIXEL / 3).coerceAtLeast(MIN_BUDGET_PIXELS)
        var scale = min(1.0, maxLongEdge.toDouble() / max(fullWidth, fullHeight))
        val sourcePixels = upright.width.toLong() * upright.height
        scale = min(scale, sqrt(budgetPixels.toDouble() / (fullWidth.toLong() * fullHeight + sourcePixels).coerceAtLeast(1)))
        val width = max(1, (fullWidth * scale).roundToInt())
        val height = max(1, (fullHeight * scale).roundToInt())
        val sample = SampleSize.forMinimumLongEdge(upright, (max(upright.width, upright.height) * scale).roundToInt().coerceAtLeast(1))
        val decoded = decoder.decode(info, sample)
        try {
            val source = decoded.bitmap
            val result = try {
                createBitmap(width, height)
            } catch (error: OutOfMemoryError) {
                throw FrameKitException(EditorErrorCode.INSUFFICIENT_MEMORY, "Document did not fit in memory", error)
            }
            try {
                if (curved != null) warp(source, curved, result) else warp(source, quad, result)
                ScanEnhancer.enhance(result, mode)
                try {
                    output.parentFile?.mkdirs()
                    output.outputStream().use { stream ->
                        if (!result.compress(Bitmap.CompressFormat.JPEG, quality, stream)) {
                            throw FrameKitException(EditorErrorCode.ENCODE_FAILED, "Document could not be encoded")
                        }
                    }
                } catch (error: IOException) {
                    output.delete()
                    throw FrameKitException(EditorErrorCode.OUTPUT_WRITE_FAILED, "Writing the document failed", error)
                }
                return PixelSize(width, height)
            } finally {
                result.recycle()
            }
        } finally {
            decoded.recycle()
        }
    }

    public companion object {
        /** 결과 긴 변의 기본 최댓값(px). 문서 글자를 읽기에 충분하고 파일이 지나치게 크지 않다. */
        public const val MAX_LONG_EDGE: Int = 4096
        public const val JPEG_QUALITY: Int = 92

        /** 휜 테두리를 펼 때 나누는 칸 수(가로·세로 각각). */
        public const val MESH_CELLS: Int = 12

        private const val BYTES_PER_PIXEL = 4L
        private const val MIN_BUDGET_PIXELS = 4_000_000L

        /**
         * [image]에서 문서 모서리를 찾는다. 이미지를 [DocumentDetector.WORKING_LONG_EDGE]로 줄여 밝기·채도로 바꾼 뒤
         * 감지하므로 빠르다. 찾지 못하면 `null`.
         */
        @JvmOverloads
        public fun detect(image: Bitmap, insideOnly: Boolean = false): DocumentQuad? = analyze(image, insideOnly).quad

        /**
         * [detect]와 같지만 문서가 아니라고 본 이유를 함께 돌려준다(로그용).
         *
         * @param insideOnly 모서리가 사진 가장자리에 닿는 결과는 버린다([DocumentDetector.analyze] 참고).
         */
        @JvmOverloads
        public fun analyze(image: Bitmap, insideOnly: Boolean = false): DocumentDetector.Analysis {
            val long = max(image.width, image.height)
            val factor = min(1f, DocumentDetector.WORKING_LONG_EDGE.toFloat() / long)
            val w = max(8, (image.width * factor).roundToInt())
            val h = max(8, (image.height * factor).roundToInt())
            val small = if (w == image.width && h == image.height) image else image.scale(w, h)
            try {
                val pixels = IntArray(w * h)
                small.getPixels(pixels, 0, w, 0, 0, w, h)
                val luma = IntArray(pixels.size)
                // 종이는 색이 거의 없어, 밝기가 비슷한 색 있는 바탕(나무 책상 등)과 채도로 구별한다.
                val chroma = IntArray(pixels.size)
                for (i in pixels.indices) {
                    val c = pixels[i]
                    val r = Color.red(c); val g = Color.green(c); val b = Color.blue(c)
                    luma[i] = (r * 299 + g * 587 + b * 114) / 1000
                    chroma[i] = maxOf(r, g, b) - minOf(r, g, b)
                }
                return DocumentDetector.analyze(luma, w, h, chroma, insideOnly)
            } finally {
                if (small !== image) small.recycle()
            }
        }

        /**
         * [image]에서 문서의 실제 테두리(휘거나 접힌 변 포함)를 찾는다. 찾지 못하면 `null`.
         *
         * @param insideOnly 모서리가 사진 가장자리에 닿는 결과는 버린다.
         */
        @JvmOverloads
        public fun detectOutline(image: Bitmap, insideOnly: Boolean = false): DocumentOutline? = analyze(image, insideOnly).outline

        /**
         * [source] 위의 휜 테두리 [outline]을 [target] 전체로 편다. 결과를 [MESH_CELLS]×[MESH_CELLS] 칸으로 나눠, 칸마다 테두리
         * 곡선 면 위의 네 점을 칸의 네 모서리로 옮기는 원근 변환으로 그린다.
         */
        public fun warp(source: Bitmap, outline: DocumentOutline, target: Bitmap) {
            val canvas = Canvas(target)
            canvas.drawColor(Color.WHITE)
            val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
            val w = target.width.toFloat()
            val h = target.height.toFloat()
            val n = MESH_CELLS
            val grid = Array(n + 1) { j -> Array(n + 1) { i -> outline.point(i.toDouble() / n, j.toDouble() / n) } }
            val matrix = Matrix()
            for (j in 0 until n) for (i in 0 until n) {
                val corners = listOf(grid[j][i], grid[j][i + 1], grid[j + 1][i + 1], grid[j + 1][i])
                val src = corners.flatMap { listOf((it.x * source.width).toFloat(), (it.y * source.height).toFloat()) }.toFloatArray()
                val left = w * i / n
                val right = w * (i + 1) / n
                val top = h * j / n
                val bottom = h * (j + 1) / n
                val dst = floatArrayOf(left, top, right, top, right, bottom, left, bottom)
                if (!matrix.setPolyToPoly(src, 0, dst, 0, 4)) continue
                // 칸 사이에 틈이 보이지 않도록 반 픽셀씩 겹쳐 그린다.
                canvas.withClip(left - 0.5f, top - 0.5f, right + 0.5f, bottom + 0.5f) { drawBitmap(source, matrix, paint) }
            }
        }

        /** [source] 위의 [quad]를 [target] 전체로 편다. 테스트와 미리보기에서도 쓴다. */
        public fun warp(source: Bitmap, quad: DocumentQuad, target: Bitmap) {
            val src = quad.corners.flatMap { listOf((it.x * source.width).toFloat(), (it.y * source.height).toFloat()) }.toFloatArray()
            val w = target.width.toFloat()
            val h = target.height.toFloat()
            val dst = floatArrayOf(0f, 0f, w, 0f, w, h, 0f, h)
            val matrix = Matrix()
            if (!matrix.setPolyToPoly(src, 0, dst, 0, 4)) throw FrameKitException(EditorErrorCode.INVALID_PROJECT, "Corners cannot be mapped")
            val canvas = Canvas(target)
            canvas.drawColor(Color.WHITE)
            canvas.drawBitmap(source, matrix, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        }
    }
}
