package com.naury.framekit.image.overlay

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import com.naury.framekit.core.effect.ColorEffectProcessor
import com.naury.framekit.core.geometry.RectN
import com.naury.framekit.core.model.PixelRect
import com.naury.framekit.core.overlay.MaskShape
import com.naury.framekit.core.overlay.PrivacyEffect
import com.naury.framekit.core.overlay.PrivacyMask
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 색 보정을 마친 이미지에 블러·모자이크 mask를 제자리에서 적용한다.
 *
 * 모든 mask는 가리기 전 이미지의 복사본을 읽으므로 겹친 mask가 두 번 블러되지 않는다. 각 mask 주변
 * 영역만 처리한다. 모자이크 블록은 캔버스 원점에 고정되고 짧은 변에 비례한 크기의 격자 위에 놓이므로
 * 미리보기와 내보내기에서 같은 블록이 보인다.
 */
public object PrivacyRenderer {

    /** 이 크기의 캔버스에서 [apply]가 추가로 쓰는 메모리. 이미지 복사본 하나와 영역 버퍼다. */
    public fun workingBytes(width: Int, height: Int, masks: List<PrivacyMask>): Long =
        if (masks.isEmpty()) 0L else width.toLong() * height * 4 * 2

    public fun apply(bitmap: Bitmap, masks: List<PrivacyMask>) {
        if (masks.isEmpty()) return
        require(bitmap.isMutable) { "Bitmap must be mutable" }
        val base = bitmap.copy(Bitmap.Config.ARGB_8888, false)
        val canvas = Canvas(bitmap)
        try {
            masks.forEach { mask -> drawMask(canvas, base, mask) }
        } finally {
            base.recycle()
        }
    }

    private fun drawMask(canvas: Canvas, base: Bitmap, mask: PrivacyMask) {
        val width = base.width
        val height = base.height
        val shortEdge = min(width, height).toFloat()
        val path = shapePath(mask.shape, width, height)
        val brushWidth = (mask.shape as? MaskShape.Brush)?.let { (it.widthShortEdgeRatio * shortEdge).toFloat() } ?: 0f
        val bounds = RectF().also { path.computeBounds(it, true) }
        bounds.inset(-brushWidth / 2 - 1, -brushWidth / 2 - 1)
        val area = PixelRect(
            floor(bounds.left).toInt().coerceIn(0, width),
            floor(bounds.top).toInt().coerceIn(0, height),
            ceil(bounds.right).toInt().coerceIn(0, width),
            ceil(bounds.bottom).toInt().coerceIn(0, height),
        )
        if (area.isEmpty) return

        val (effect, origin) = when (val e = mask.effect) {
            is PrivacyEffect.Mosaic -> mosaic(base, area, max(1, (e.blockShortEdgeRatio * shortEdge).roundToInt()))
            is PrivacyEffect.Blur -> blur(base, area, (e.radiusShortEdgeRatio * shortEdge).toFloat())
        }
        try {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = BitmapShader(effect, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                    setLocalMatrix(Matrix().apply { setTranslate(origin.first.toFloat(), origin.second.toFloat()) })
                }
                if (mask.shape is MaskShape.Brush) {
                    style = Paint.Style.STROKE
                    strokeWidth = brushWidth
                    strokeCap = Paint.Cap.ROUND
                    strokeJoin = Paint.Join.ROUND
                }
            }
            canvas.drawPath(path, paint)
        } finally {
            effect.recycle()
        }
    }

    private fun shapePath(shape: MaskShape, width: Int, height: Int): Path = Path().apply {
        when (shape) {
            is MaskShape.Rectangle -> addRect(rectF(shape.rect, width, height), Path.Direction.CW)
            is MaskShape.Ellipse -> addOval(rectF(shape.rect, width, height), Path.Direction.CW)
            is MaskShape.Brush -> {
                val points = shape.points
                moveTo((points[0].x * width).toFloat(), (points[0].y * height).toFloat())
                if (points.size == 1) lineTo((points[0].x * width).toFloat() + 0.01f, (points[0].y * height).toFloat())
                points.drop(1).forEach { lineTo((it.x * width).toFloat(), (it.y * height).toFloat()) }
            }
        }
    }

    private fun rectF(rect: RectN, width: Int, height: Int) = RectF(
        (min(rect.left, rect.right) * width).toFloat(),
        (min(rect.top, rect.bottom) * height).toFloat(),
        (max(rect.left, rect.right) * width).toFloat(),
        (max(rect.top, rect.bottom) * height).toFloat(),
    )

    // 격자를 캔버스 원점에 맞춰 영역을 블록 경계까지 넓힌 뒤 블록마다 원본 평균색으로 채운다.
    private fun mosaic(base: Bitmap, area: PixelRect, block: Int): Pair<Bitmap, Pair<Int, Int>> {
        val left = area.left / block * block
        val top = area.top / block * block
        val right = min(base.width, ceil(area.right / block.toDouble()).toInt() * block)
        val bottom = min(base.height, ceil(area.bottom / block.toDouble()).toInt() * block)
        val w = right - left
        val h = bottom - top
        val pixels = IntArray(w * h)
        base.getPixels(pixels, 0, w, left, top, w, h)
        var by = 0
        while (by < h) {
            var bx = 0
            val bh = min(block, h - by)
            while (bx < w) {
                val bw = min(block, w - bx)
                var a = 0L
                var r = 0L
                var g = 0L
                var b = 0L
                for (y in by until by + bh) for (x in bx until bx + bw) {
                    val p = pixels[y * w + x]
                    a += p ushr 24
                    r += (p shr 16) and 0xFF
                    g += (p shr 8) and 0xFF
                    b += p and 0xFF
                }
                val n = (bw * bh).toLong()
                val average = ((a / n).toInt() shl 24) or ((r / n).toInt() shl 16) or ((g / n).toInt() shl 8) or (b / n).toInt()
                for (y in by until by + bh) for (x in bx until bx + bw) pixels[y * w + x] = average
                bx += block
            }
            by += block
        }
        val result = createBitmap(w, h)
        result.setPixels(pixels, 0, w, 0, 0, w, h)
        return result to (left to top)
    }

    // 경계 밖 3σ까지 포함해 블러한다. 반경이 크면 축소해 블러하고 다시 키워 큰 사진에서도 계산량을 제한한다.
    private fun blur(base: Bitmap, area: PixelRect, sigma: Float): Pair<Bitmap, Pair<Int, Int>> {
        val pad = ceil(3 * sigma).toInt()
        val left = max(0, area.left - pad)
        val top = max(0, area.top - pad)
        val right = min(base.width, area.right + pad)
        val bottom = min(base.height, area.bottom + pad)
        val w = right - left
        val h = bottom - top
        val factor = max(1, ceil(sigma / MAX_DIRECT_SIGMA).toInt())
        val region = Bitmap.createBitmap(base, left, top, w, h)
        val small = if (factor > 1) region.scale(max(1, w / factor), max(1, h / factor)) else region
        val sw = small.width
        val sh = small.height
        val pixels = IntArray(sw * sh)
        small.getPixels(pixels, 0, sw, 0, 0, sw, sh)
        val blurred = ColorEffectProcessor.blur(pixels, sw, sh, ColorEffectProcessor.gaussianWeights(sigma / factor))
        val smallResult = createBitmap(sw, sh).apply { setPixels(blurred, 0, sw, 0, 0, sw, sh) }
        if (small !== region) small.recycle()
        region.recycle()
        val result = if (factor > 1) smallResult.scale(w, h).also { smallResult.recycle() } else smallResult
        return result to (left to top)
    }

    private const val MAX_DIRECT_SIGMA = 8f
}
