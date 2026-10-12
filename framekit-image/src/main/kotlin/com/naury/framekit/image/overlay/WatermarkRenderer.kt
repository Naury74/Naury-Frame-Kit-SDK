package com.naury.framekit.image.overlay

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import com.naury.framekit.core.model.PixelSize
import com.naury.framekit.core.overlay.Watermark
import com.naury.framekit.core.overlay.WatermarkPattern
import com.naury.framekit.image.catalog.CatalogAssets
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.min

/**
 * [Watermark]를 출력 캔버스에 그린다. 사진 미리보기·저장과 영상 미리보기·저장이 모두 이 렌더러를 써서 같은 모양이 된다.
 *
 * 캔버스는 출력 화면(자르기·회전 뒤) 좌표여야 한다. 글자는 한 줄이고, 크기·여백은 짧은 변에 비례한다.
 */
public class WatermarkRenderer {

    private val text = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
    private val box = Paint(Paint.ANTI_ALIAS_FLAG)

    /** 워터마크를 [size] 크기 캔버스에 그린다. 유효하지 않은 워터마크([Watermark.isValid])는 그리지 않는다. */
    public fun draw(canvas: Canvas, size: PixelSize, watermark: Watermark?) {
        if (watermark == null || !watermark.isValid || size.width <= 0 || size.height <= 0) return
        val w = size.width.toFloat()
        val h = size.height.toFloat()
        val unit = min(w, h)
        val label = watermark.text.trim()
        prepare(watermark, unit * watermark.sizeRatio.toFloat())
        when (watermark.pattern) {
            WatermarkPattern.SINGLE -> drawSingle(canvas, w, h, unit, label, watermark)
            WatermarkPattern.TILE -> drawTile(canvas, w, h, label, watermark)
            WatermarkPattern.DIAGONAL -> drawDiagonal(canvas, w, h, label, watermark)
            WatermarkPattern.BAND -> drawBand(canvas, w, h, unit, label, watermark)
        }
    }

    private fun prepare(watermark: Watermark, textSize: Float) {
        text.typeface = typefaceOf(watermark.fontId)
        text.textSize = textSize
        text.color = watermark.colorArgb
        text.alpha = (Color.alpha(watermark.colorArgb) * watermark.opacity).toInt().coerceIn(0, 255)
        text.textAlign = Paint.Align.LEFT
        applyShadow(watermark)
        // 글자 색이 밝으면 어두운 상자, 어두우면 밝은 상자를 깔아 어디서나 읽히게 한다.
        val light = luminance(watermark.colorArgb) > 0.5
        box.color = if (light) Color.BLACK else Color.WHITE
        box.alpha = (BOX_ALPHA * watermark.opacity).toInt().coerceIn(0, 255)
    }

    private fun applyShadow(watermark: Watermark) {
        if (watermark.shadow) {
            val light = luminance(watermark.colorArgb) > 0.5
            val shadowColor = if (light) Color.BLACK else Color.WHITE
            val alpha = (SHADOW_ALPHA * watermark.opacity).toInt().coerceIn(0, 255)
            text.setShadowLayer(text.textSize * 0.12f, 0f, text.textSize * 0.04f, Color.argb(alpha, Color.red(shadowColor), Color.green(shadowColor), Color.blue(shadowColor)))
        } else {
            text.clearShadowLayer()
        }
    }

    // 글자를 (x, y) 왼쪽 위에서 시작해 그리고, 필요하면 둥근 상자를 깐다.
    private fun drawLabel(canvas: Canvas, label: String, left: Float, top: Float, withBox: Boolean) {
        val metrics = text.fontMetrics
        val width = text.measureText(label)
        val height = metrics.descent - metrics.ascent
        if (withBox) {
            val pad = text.textSize * BOX_PADDING
            val rect = RectF(left - pad * 1.4f, top - pad, left + width + pad * 1.4f, top + height + pad)
            canvas.drawRoundRect(rect, rect.height() / 2, rect.height() / 2, box)
        }
        canvas.drawText(label, left, top - metrics.ascent, text)
    }

    private fun labelSize(label: String): Pair<Float, Float> {
        val metrics = text.fontMetrics
        return text.measureText(label) to (metrics.descent - metrics.ascent)
    }

    private fun drawSingle(canvas: Canvas, w: Float, h: Float, unit: Float, label: String, watermark: Watermark) {
        val (width, height) = labelSize(label)
        // 상자가 있으면 상자 몫만큼 안쪽으로 들인다.
        val inset = if (watermark.background) text.textSize * BOX_PADDING * 1.4f else 0f
        val margin = unit * MARGIN + inset
        val position = watermark.position
        val left = when (position.horizontal) {
            -1 -> margin
            1 -> w - margin - width
            else -> (w - width) / 2
        }
        val top = when (position.vertical) {
            -1 -> margin
            1 -> h - margin - height
            else -> (h - height) / 2
        }
        drawLabel(canvas, label, left, top, watermark.background)
    }

    private fun drawTile(canvas: Canvas, w: Float, h: Float, label: String, watermark: Watermark) {
        val (width, height) = labelSize(label)
        val density = watermark.density.toFloat()
        val stepX = width + text.textSize * TILE_GAP_X / density
        val stepY = height + text.textSize * TILE_GAP_Y / density
        // 기울인 뒤에도 화면 모서리가 비지 않도록 대각선 길이만큼 덮는다.
        val reach = hypot(w, h) / 2 + stepX
        val rows = ceil(reach / stepY).toInt()
        val cols = ceil(reach / stepX).toInt() + 1
        canvas.save()
        canvas.rotate(watermark.rotationDegrees.toFloat(), w / 2, h / 2)
        for (row in -rows..rows) {
            // 줄마다 반 칸씩 엇갈려 벽돌 모양으로 둔다.
            val shift = if (row % 2 == 0) 0f else stepX / 2
            for (col in -cols..cols) {
                val cx = w / 2 + col * stepX + shift
                val cy = h / 2 + row * stepY
                drawLabel(canvas, label, cx - width / 2, cy - height / 2, watermark.background)
            }
        }
        canvas.restore()
    }

    private fun drawDiagonal(canvas: Canvas, w: Float, h: Float, label: String, watermark: Watermark) {
        val diagonal = hypot(w, h)
        // 대각선의 75%를 채우되 짧은 변의 1/4보다 크지 않게 한다(짧은 글자가 너무 커지지 않도록).
        text.textSize = REFERENCE_SIZE
        val scale = diagonal * DIAGONAL_FILL / text.measureText(label)
        text.textSize = min(REFERENCE_SIZE * scale, min(w, h) * DIAGONAL_MAX_HEIGHT)
        applyShadow(watermark)
        val (width, height) = labelSize(label)
        canvas.save()
        canvas.rotate(-Math.toDegrees(atan2(h.toDouble(), w.toDouble())).toFloat(), w / 2, h / 2)
        drawLabel(canvas, label, (w - width) / 2, (h - height) / 2, withBox = false)
        canvas.restore()
    }

    private fun drawBand(canvas: Canvas, w: Float, h: Float, unit: Float, label: String, watermark: Watermark) {
        val (width, height) = labelSize(label)
        val bandHeight = height * BAND_HEIGHT
        val top = if (watermark.position.vertical < 0) 0f else h - bandHeight
        canvas.drawRect(0f, top, w, top + bandHeight, box)
        val margin = unit * MARGIN
        val left = when (watermark.position.horizontal) {
            -1 -> margin
            1 -> w - margin - width
            else -> (w - width) / 2
        }
        drawLabel(canvas, label, left, top + (bandHeight - height) / 2, withBox = false)
    }

    private fun luminance(argb: Int): Double = (0.299 * Color.red(argb) + 0.587 * Color.green(argb) + 0.114 * Color.blue(argb)) / 255.0

    private fun typefaceOf(fontId: String): Typeface = CatalogAssets.typeface(fontId) ?: when (fontId) {
        "sans-bold" -> Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        "serif" -> Typeface.SERIF
        "serif-bold" -> Typeface.create(Typeface.SERIF, Typeface.BOLD)
        "mono" -> Typeface.MONOSPACE
        "handwriting" -> Typeface.create("cursive", Typeface.NORMAL)
        else -> Typeface.SANS_SERIF
    }

    private companion object {
        // 짧은 변에 대한 가장자리 여백.
        const val MARGIN = 0.035f
        const val BOX_PADDING = 0.35f
        const val BOX_ALPHA = 110
        const val SHADOW_ALPHA = 140
        // 반복 무늬의 글자 사이 간격(글자 크기 배수).
        const val TILE_GAP_X = 2.5f
        const val TILE_GAP_Y = 2.6f
        const val DIAGONAL_FILL = 0.75f
        const val DIAGONAL_MAX_HEIGHT = 0.25f
        const val BAND_HEIGHT = 1.9f
        const val REFERENCE_SIZE = 100f
    }
}
