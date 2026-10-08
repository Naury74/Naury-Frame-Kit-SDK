package com.naury.framekit.image.document

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import com.naury.framekit.android.result.EditorErrorCode
import com.naury.framekit.android.result.FrameKitException
import com.naury.framekit.android.source.SourceResolver
import com.naury.framekit.core.document.DocumentDetector
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
    ): PixelSize {
        if (!quad.isUsable) throw FrameKitException(EditorErrorCode.INVALID_PROJECT, "Corners do not form a document")
        val upright = info.metadata.uprightSize
        val (fullWidth, fullHeight) = quad.rectifiedSize(upright.width, upright.height)
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
                warp(source, quad, result)
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

        private const val BYTES_PER_PIXEL = 4L
        private const val MIN_BUDGET_PIXELS = 4_000_000L

        /**
         * [image]에서 문서 모서리를 찾는다. 이미지를 [DocumentDetector.WORKING_LONG_EDGE]로 줄여 밝기로 바꾼 뒤
         * 감지하므로 빠르다. 찾지 못하면 `null`.
         */
        public fun detect(image: Bitmap): DocumentQuad? = analyze(image).quad

        /** [detect]와 같지만 문서가 아니라고 본 이유를 함께 돌려준다(로그용). */
        public fun analyze(image: Bitmap): DocumentDetector.Analysis {
            val long = max(image.width, image.height)
            val factor = min(1f, DocumentDetector.WORKING_LONG_EDGE.toFloat() / long)
            val w = max(8, (image.width * factor).roundToInt())
            val h = max(8, (image.height * factor).roundToInt())
            val small = if (w == image.width && h == image.height) image else image.scale(w, h)
            try {
                val pixels = IntArray(w * h)
                small.getPixels(pixels, 0, w, 0, 0, w, h)
                val luma = IntArray(pixels.size) { i ->
                    val c = pixels[i]
                    (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000
                }
                return DocumentDetector.analyze(luma, w, h)
            } finally {
                if (small !== image) small.recycle()
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
