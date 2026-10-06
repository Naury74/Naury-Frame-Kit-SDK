package com.naury.framekit.image.overlay

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.LruCache
import androidx.core.graphics.withClip
import com.naury.framekit.core.geometry.PointN
import com.naury.framekit.core.model.PixelSize
import com.naury.framekit.core.overlay.BrushKind
import com.naury.framekit.core.overlay.DrawingStroke
import com.naury.framekit.core.overlay.EmojiCatalog
import com.naury.framekit.image.catalog.CatalogAssets
import com.naury.framekit.core.overlay.ImageOverlay
import com.naury.framekit.core.overlay.TextAlignment
import com.naury.framekit.core.overlay.TextStyleSpec
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * 회전 전 오버레이의 크기(캔버스 px)로, 외곽선·그림자·배경 여백을 포함한다. hit test와 선택 프레임은
 * 이 상자의 회전된 꼭짓점을 쓴다.
 */
public data class OverlayLayout(val width: Float, val height: Float)

/**
 * 텍스트·스티커·그리기 레이어를 배치하고 그린다.
 *
 * 모든 크기는 캔버스 대비 비율에서 나오고 같은 인스턴스가 미리보기·내보내기·터치 hit test를 모두
 * 처리하므로, 오버레이는 어디서나 같은 픽셀에 놓인다. 텍스트는 [StaticLayout]으로 배치하며
 * 내보내기에 Compose 스크린샷은 쓰지 않는다. 캐시된 텍스트 layout이 미리보기와 내보내기 스레드 사이에서
 * paint를 공유하므로 public 메서드는 synchronized다.
 */
public class OverlayRenderer {

    private val textLayouts = LruCache<TextKey, StaticLayout>(TEXT_CACHE_SIZE)

    /** [canvas] 픽셀 크기의 캔버스에서 [overlay]의 회전 전 크기. */
    @Synchronized
    public fun layout(overlay: ImageOverlay, canvas: PixelSize): OverlayLayout = when (overlay) {
        is ImageOverlay.Text -> {
            val text = textLayout(overlay, canvas)
            val margin = textMargin(overlay.style, overlay.transform.scale, canvas)
            OverlayLayout(text.width + 2 * margin, text.height + 2 * margin)
        }
        is ImageOverlay.Sticker -> {
            val side = (overlay.widthRatio * canvas.width * overlay.transform.scale).toFloat()
            // 호스트 이미지 스티커는 이미지 비율을 지키고, 이모지는 정사각형이다.
            val image = CatalogAssets.sticker(overlay.assetId)
            OverlayLayout(side, if (image != null) side * image.height / image.width else side)
        }
    }

    /** 캔버스 px 기준 [overlay]의 회전된 꼭짓점. 왼쪽 위부터 시계 방향 순서다. */
    @Synchronized
    public fun corners(overlay: ImageOverlay, canvas: PixelSize): List<PointN> {
        val size = layout(overlay, canvas)
        val cx = overlay.transform.center.x * canvas.width
        val cy = overlay.transform.center.y * canvas.height
        val radians = Math.toRadians(overlay.transform.rotationDegrees)
        val c = cos(radians)
        val s = sin(radians)
        return listOf(-1.0 to -1.0, 1.0 to -1.0, 1.0 to 1.0, -1.0 to 1.0).map { (sx, sy) ->
            val x = sx * size.width / 2
            val y = sy * size.height / 2
            PointN(cx + x * c - y * s, cy + x * s + y * c)
        }
    }

    /** 캔버스 px 기준 ([x], [y]) 아래에 있는 가장 위 오버레이의 id. 없으면 `null`. */
    @Synchronized
    public fun hitTest(overlays: List<ImageOverlay>, x: Double, y: Double, canvas: PixelSize, slopPx: Double = 0.0): String? =
        overlays.lastOrNull { overlay ->
            val size = layout(overlay, canvas)
            val cx = overlay.transform.center.x * canvas.width
            val cy = overlay.transform.center.y * canvas.height
            val radians = Math.toRadians(-overlay.transform.rotationDegrees)
            val dx = x - cx
            val dy = y - cy
            val localX = dx * cos(radians) - dy * sin(radians)
            val localY = dx * sin(radians) + dy * cos(radians)
            abs(localX) <= size.width / 2 + slopPx && abs(localY) <= size.height / 2 + slopPx
        }?.id

    /**
     * [canvas]에 그리기 레이어를 먼저 그리고 오버레이를 목록 순서대로 그린다. [canvas] 좌표는
     * [size] 캔버스의 출력 픽셀이다. 캔버스 밖 내용은 clip된다.
     *
     * @param skipId 그리지 않을 오버레이. 예를 들어 텍스트 필드에서 편집 중일 때 쓴다.
     */
    @Synchronized
    public fun draw(canvas: Canvas, size: PixelSize, overlays: List<ImageOverlay>, drawing: List<DrawingStroke>, skipId: String? = null) {
        canvas.withClip(0f, 0f, size.width.toFloat(), size.height.toFloat()) {
            if (drawing.isNotEmpty()) drawStrokes(this, size, drawing)
            overlays.forEach { overlay -> if (overlay.id != skipId) drawOverlay(this, size, overlay) }
        }
    }

    private fun drawOverlay(canvas: Canvas, size: PixelSize, overlay: ImageOverlay) {
        val layout = layout(overlay, size)
        val transform = overlay.transform
        val checkpoint = canvas.saveLayerAlpha(null, (transform.opacity.coerceIn(0.0, 1.0) * 255).toInt())
        canvas.translate((transform.center.x * size.width).toFloat(), (transform.center.y * size.height).toFloat())
        canvas.rotate(transform.rotationDegrees.toFloat())
        canvas.translate(-layout.width / 2, -layout.height / 2)
        when (overlay) {
            is ImageOverlay.Text -> drawText(canvas, size, overlay, layout)
            is ImageOverlay.Sticker -> drawSticker(canvas, overlay, layout)
        }
        canvas.restoreToCount(checkpoint)
    }

    private fun drawText(canvas: Canvas, size: PixelSize, overlay: ImageOverlay.Text, box: OverlayLayout) {
        val style = overlay.style
        val scale = overlay.transform.scale
        val unit = (size.height * scale).toFloat()
        style.background?.let { background ->
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = background.colorArgb }
            val corner = (background.cornerHeightRatio * unit).toFloat()
            canvas.drawRoundRect(RectF(0f, 0f, box.width, box.height), corner, corner, paint)
        }
        val layout = textLayout(overlay, size)
        val margin = textMargin(style, scale, size)
        canvas.translate(margin, margin)
        val paint = layout.paint
        style.stroke?.let { stroke ->
            paint.style = Paint.Style.STROKE
            paint.strokeJoin = Paint.Join.ROUND
            paint.strokeWidth = (stroke.widthHeightRatio * unit).toFloat()
            paint.color = stroke.colorArgb
            paint.clearShadowLayer()
            layout.draw(canvas)
        }
        paint.style = Paint.Style.FILL
        paint.color = style.colorArgb
        style.shadow?.let { shadow ->
            paint.setShadowLayer(
                max(0.01f, (shadow.blurHeightRatio * unit).toFloat()),
                (shadow.offsetXHeightRatio * unit).toFloat(),
                (shadow.offsetYHeightRatio * unit).toFloat(),
                shadow.colorArgb,
            )
        } ?: paint.clearShadowLayer()
        layout.draw(canvas)
    }

    private fun drawSticker(canvas: Canvas, overlay: ImageOverlay.Sticker, box: OverlayLayout) {
        CatalogAssets.sticker(overlay.assetId)?.let { image ->
            canvas.drawBitmap(image, null, RectF(0f, 0f, box.width, box.height), STICKER_PAINT)
            return
        }
        val emoji = EmojiCatalog.emojiOf(overlay.assetId) ?: return
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = REFERENCE_TEXT_SIZE
            textAlign = Paint.Align.CENTER
        }
        // 기준 크기로 잰 글리프 폭이 스티커 폭에 맞도록 글자 크기를 정한다.
        val measured = max(1f, paint.measureText(emoji))
        paint.textSize = REFERENCE_TEXT_SIZE * box.width / measured
        val metrics = paint.fontMetrics
        val baseline = box.height / 2 - (metrics.ascent + metrics.descent) / 2
        canvas.drawText(emoji, box.width / 2, baseline, paint)
    }

    // 지우개는 DST_OUT으로 이 레이어만 지운다. 사진과 다른 오버레이는 레이어 밖이라 영향이 없다.
    private fun drawStrokes(canvas: Canvas, size: PixelSize, strokes: List<DrawingStroke>) {
        val checkpoint = canvas.saveLayer(0f, 0f, size.width.toFloat(), size.height.toFloat(), null)
        val shortEdge = min(size.width, size.height).toFloat()
        strokes.forEach { stroke ->
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = (stroke.widthShortEdgeRatio * shortEdge).toFloat()
                strokeJoin = Paint.Join.ROUND
                strokeCap = if (stroke.brush == BrushKind.MARKER) Paint.Cap.SQUARE else Paint.Cap.ROUND
                color = stroke.colorArgb
                alpha = (stroke.opacity.coerceIn(0.0, 1.0) * 255 * (if (stroke.brush == BrushKind.HIGHLIGHTER) HIGHLIGHTER_ALPHA else 1f)).toInt()
                if (stroke.brush == BrushKind.ERASER) {
                    xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
                    alpha = 255
                }
            }
            canvas.drawPath(strokePath(stroke, size), paint)
        }
        canvas.restoreToCount(checkpoint)
    }

    /** stroke 점들을 지나는 부드러운 path. 미리보기와 내보내기가 공유한다. */
    @Synchronized
    public fun strokePath(stroke: DrawingStroke, size: PixelSize): Path {
        val path = Path()
        val points = stroke.points
        if (points.isEmpty()) return path
        fun x(i: Int) = (points[i].x * size.width).toFloat()
        fun y(i: Int) = (points[i].y * size.height).toFloat()
        path.moveTo(x(0), y(0))
        if (points.size == 1) {
            // 점 하나만 찍은 경우에도 둥근 끝 모양이 보이도록 아주 짧은 선을 만든다.
            path.lineTo(x(0) + 0.01f, y(0))
            return path
        }
        for (i in 1 until points.size - 1) {
            val midX = (x(i) + x(i + 1)) / 2
            val midY = (y(i) + y(i + 1)) / 2
            path.quadTo(x(i), y(i), midX, midY)
        }
        path.lineTo(x(points.lastIndex), y(points.lastIndex))
        return path
    }

    private fun textLayout(overlay: ImageOverlay.Text, canvas: PixelSize): StaticLayout {
        val key = TextKey(overlay.text, overlay.style, overlay.transform.scale, canvas)
        textLayouts.get(key)?.let { return it }
        val style = overlay.style
        val scale = overlay.transform.scale
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = max(1f, (style.fontSizeHeightRatio * canvas.height * scale).toFloat())
            typeface = typefaceOf(style.fontId)
            letterSpacing = style.letterSpacingEm.toFloat()
            color = style.colorArgb
        }
        val maxWidth = max(1, ceil(style.maxWidthRatio * canvas.width * scale).toInt())
        val alignment = when (style.alignment) {
            TextAlignment.START -> Layout.Alignment.ALIGN_NORMAL
            TextAlignment.CENTER -> Layout.Alignment.ALIGN_CENTER
            TextAlignment.END -> Layout.Alignment.ALIGN_OPPOSITE
        }
        val text = overlay.text.ifEmpty { " " }
        val wrapped = build(text, paint, maxWidth, alignment, style.lineSpacingMultiplier.toFloat())
        // 가장 긴 줄 폭으로 다시 배치해 상자가 글자에 딱 맞고 정렬이 그 안에서 적용되게 한다.
        val widest = (0 until wrapped.lineCount).maxOf { wrapped.getLineWidth(it) }
        val fitted = build(text, paint, max(1, ceil(widest).toInt()), alignment, style.lineSpacingMultiplier.toFloat())
        textLayouts.put(key, fitted)
        return fitted
    }

    private fun build(text: String, paint: TextPaint, width: Int, alignment: Layout.Alignment, spacing: Float): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(alignment)
            .setLineSpacing(0f, spacing)
            .setIncludePad(true)
            .build()

    private fun textMargin(style: TextStyleSpec, scale: Double, canvas: PixelSize): Float {
        val unit = canvas.height * scale
        val stroke = style.stroke?.let { it.widthHeightRatio * unit / 2 } ?: 0.0
        val shadow = style.shadow?.let { max(abs(it.offsetXHeightRatio), abs(it.offsetYHeightRatio)) * unit + it.blurHeightRatio * unit } ?: 0.0
        val background = style.background?.let { it.paddingHeightRatio * unit } ?: 0.0
        return max(stroke + shadow, background).toFloat()
    }

    private fun typefaceOf(fontId: String): Typeface = CatalogAssets.typeface(fontId) ?: when (fontId) {
        "sans-bold" -> Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        "serif" -> Typeface.SERIF
        "serif-bold" -> Typeface.create(Typeface.SERIF, Typeface.BOLD)
        "mono" -> Typeface.MONOSPACE
        "handwriting" -> Typeface.create("cursive", Typeface.NORMAL)
        else -> Typeface.SANS_SERIF
    }

    private data class TextKey(val text: String, val style: TextStyleSpec, val scale: Double, val canvas: PixelSize)

    private companion object {
        const val TEXT_CACHE_SIZE = 32
        const val REFERENCE_TEXT_SIZE = 100f
        const val HIGHLIGHTER_ALPHA = 0.4f
        val STICKER_PAINT = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    }
}
