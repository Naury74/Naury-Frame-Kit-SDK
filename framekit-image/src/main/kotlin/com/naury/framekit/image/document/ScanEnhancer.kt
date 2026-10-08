package com.naury.framekit.image.document

import android.graphics.Bitmap
import android.graphics.Color
import androidx.core.graphics.scale
import com.naury.framekit.core.document.ScanMode
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 펴낸 문서를 스캐너로 읽은 것처럼 정리한다.
 *
 * 1) 이미지를 크게 줄여 칸마다 가장 밝은 값(종이 색)을 모아 배경 밝기 지도를 만든다. 글자는 어두워서 이 단계에서
 *    빠진다. 2) 각 픽셀을 배경 밝기로 나눠 그림자·조명 얼룩을 지우고 종이를 흰색으로 맞춘다. 3) 검은 점·흰 점을
 *    다시 잡아 글자를 진하게 한다. 원본 bitmap을 한 줄씩 고쳐 쓰므로 추가 메모리는 줄 하나와 작은 지도뿐이다.
 */
public object ScanEnhancer {

    /** [bitmap]을 [mode]에 맞게 고쳐 쓴다. [bitmap]은 수정 가능한 ARGB_8888이어야 한다. */
    public fun enhance(bitmap: Bitmap, mode: ScanMode) {
        if (mode == ScanMode.ORIGINAL) return
        require(bitmap.isMutable && bitmap.config == Bitmap.Config.ARGB_8888) { "Bitmap must be mutable ARGB_8888" }
        val width = bitmap.width
        val height = bitmap.height
        val background = BackgroundMap.of(bitmap)
        val row = IntArray(width)
        for (y in 0 until height) {
            bitmap.getPixels(row, 0, width, 0, y, width, 1)
            for (x in 0 until width) {
                val c = row[x]
                // 배경 밝기로 나눠 종이를 1(흰색)로 맞춘다. 너무 어두운 배경은 잡음이 커지지 않게 하한을 둔다.
                val bg = max(background.at(x, y), MIN_BACKGROUND)
                val r = Color.red(c) / bg
                val g = Color.green(c) / bg
                val b = Color.blue(c) / bg
                row[x] = when (mode) {
                    ScanMode.COLOR -> Color.rgb(level(r), level(g), level(b))
                    ScanMode.GRAYSCALE -> level(luma(r, g, b)).let { Color.rgb(it, it, it) }
                    ScanMode.BLACK_WHITE -> threshold(luma(r, g, b)).let { Color.rgb(it, it, it) }
                    ScanMode.ORIGINAL -> c
                }
            }
            bitmap.setPixels(row, 0, width, 0, y, width, 1)
        }
    }

    private fun luma(r: Float, g: Float, b: Float) = 0.299f * r + 0.587f * g + 0.114f * b

    // 정규화한 값(종이 ≈ 1)을 검은 점 BLACK, 흰 점 WHITE로 늘려 글자는 진하게, 종이는 완전히 희게.
    private fun level(v: Float): Int {
        val t = ((v - BLACK) / (WHITE - BLACK)).coerceIn(0f, 1f)
        // 가운데를 살짝 어둡게 해 얇은 글자가 흐려지지 않게 한다.
        return (t * t * (3f - 2f * t) * 0.35f + t * 0.65f).times(255f).roundToInt().coerceIn(0, 255)
    }

    // 부드러운 문턱값: 글자 가장자리가 계단처럼 보이지 않게 좁은 구간에서만 이어 준다.
    private fun threshold(v: Float): Int {
        val t = ((v - BW_LOW) / (BW_HIGH - BW_LOW)).coerceIn(0f, 1f)
        return (t * t * (3f - 2f * t) * 255f).roundToInt()
    }

    private const val MIN_BACKGROUND = 40f
    private const val BLACK = 0.18f
    private const val WHITE = 0.9f
    private const val BW_LOW = 0.55f
    private const val BW_HIGH = 0.78f

    /** 칸 크기 [CELL]px로 줄인 배경 밝기 지도. [at]은 이웃 칸 사이를 선형 보간한다. */
    private class BackgroundMap(private val values: FloatArray, private val columns: Int, private val rows: Int, private val cellWidth: Float, private val cellHeight: Float) {

        fun at(x: Int, y: Int): Float {
            val fx = ((x + 0.5f) / cellWidth - 0.5f).coerceIn(0f, columns - 1f)
            val fy = ((y + 0.5f) / cellHeight - 0.5f).coerceIn(0f, rows - 1f)
            val x0 = fx.toInt(); val y0 = fy.toInt()
            val x1 = min(x0 + 1, columns - 1); val y1 = min(y0 + 1, rows - 1)
            val tx = fx - x0; val ty = fy - y0
            val top = values[y0 * columns + x0] * (1 - tx) + values[y0 * columns + x1] * tx
            val bottom = values[y1 * columns + x0] * (1 - tx) + values[y1 * columns + x1] * tx
            return top * (1 - ty) + bottom * ty
        }

        companion object {
            const val CELL = 24

            fun of(bitmap: Bitmap): BackgroundMap {
                val columns = max(1, bitmap.width / CELL)
                val rows = max(1, bitmap.height / CELL)
                // 칸마다 평균이 아니라 밝은 쪽 값을 써야 글자가 배경에 섞이지 않는다. 먼저 4배 크게 줄여 칸 안의 최댓값을 구한다.
                val fine = bitmap.scale(columns * 4, rows * 4)
                val pixels = IntArray(fine.width * fine.height)
                fine.getPixels(pixels, 0, fine.width, 0, 0, fine.width, fine.height)
                if (fine !== bitmap) fine.recycle()
                val cells = FloatArray(columns * rows)
                for (cy in 0 until rows) for (cx in 0 until columns) {
                    var best = 0f
                    for (dy in 0 until 4) for (dx in 0 until 4) {
                        val c = pixels[(cy * 4 + dy) * columns * 4 + cx * 4 + dx]
                        val l = 0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c)
                        if (l > best) best = l
                    }
                    cells[cy * columns + cx] = best
                }
                // 큰 글자·사진 영역이 배경을 끌어내리지 않도록 이웃 최댓값으로 넓힌 뒤 평균으로 부드럽게 한다.
                val dilated = filter(cells, columns, rows, radius = 2) { values -> values.max() }
                val smooth = filter(dilated, columns, rows, radius = 2) { values -> values.average().toFloat() }
                return BackgroundMap(smooth, columns, rows, bitmap.width.toFloat() / columns, bitmap.height.toFloat() / rows)
            }

            private inline fun filter(source: FloatArray, columns: Int, rows: Int, radius: Int, combine: (List<Float>) -> Float): FloatArray {
                val out = FloatArray(source.size)
                for (y in 0 until rows) for (x in 0 until columns) {
                    val window = ArrayList<Float>((radius * 2 + 1) * (radius * 2 + 1))
                    for (dy in -radius..radius) for (dx in -radius..radius) {
                        val nx = x + dx; val ny = y + dy
                        if (nx in 0 until columns && ny in 0 until rows) window += source[ny * columns + nx]
                    }
                    out[y * columns + x] = combine(window)
                }
                return out
            }
        }
    }
}
