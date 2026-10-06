package com.naury.framekit.image.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withClip
import androidx.core.graphics.withMatrix
import com.naury.framekit.core.geometry.Affine2D
import com.naury.framekit.core.model.PixelRect
import com.naury.framekit.core.model.PixelSize
import com.naury.framekit.image.decode.DecodedImage

/**
 * CPU renderer for geometric edits.
 *
 * The same [draw] call paints the on-screen preview (with an extra output-to-viewport transform) and
 * the export bitmap. Selection frames, grids and handles are drawn by the UI on top and are never part
 * of a plan.
 */
public object CanvasGeometryRenderer {

    /**
     * Draws [source] according to [plan] onto [canvas], clipped to the output area.
     *
     * @param outputToTarget extra transform from output pixels to canvas pixels, for example to fit
     *   the result into a viewport. Identity for export.
     * @param cutoutMask subject mask covering the whole upright source; the background becomes
     *   transparent where its alpha is 0.
     */
    public fun draw(
        canvas: Canvas,
        source: DecodedImage,
        plan: ImageRenderPlan,
        outputToTarget: Affine2D = Affine2D.Identity,
        cutoutMask: Bitmap? = null,
    ) {
        val bitmap = source.bitmap
        val region = source.uprightRegion
        val decodedToUpright = Affine2D.translate(region.left.toDouble(), region.top.toDouble()) *
            Affine2D.scale(region.width.toDouble() / bitmap.width, region.height.toDouble() / bitmap.height)
        canvas.withMatrix(outputToTarget.toAndroidMatrix()) {
            clipRect(0f, 0f, plan.outputSize.width.toFloat(), plan.outputSize.height.toFloat())
            val layer = if (cutoutMask != null) saveLayer(0f, 0f, plan.outputSize.width.toFloat(), plan.outputSize.height.toFloat(), null) else -1
            drawBitmap(bitmap, (plan.sourceToOutput * decodedToUpright).toAndroidMatrix(), PAINT)
            if (cutoutMask != null) {
                drawBitmap(cutoutMask, (plan.sourceToOutput * maskToUpright(cutoutMask, source.uprightSize)).toAndroidMatrix(), MASK_PAINT)
                restoreToCount(layer)
            }
        }
    }

    private fun maskToUpright(mask: Bitmap, upright: PixelSize): Affine2D =
        Affine2D.scale(upright.width.toDouble() / mask.width, upright.height.toDouble() / mask.height)

    /**
     * Renders [plan] into a new ARGB_8888 bitmap.
     *
     * @param backgroundArgb color painted below the image, or `null` to keep transparency.
     */
    public fun render(source: DecodedImage, plan: ImageRenderPlan, backgroundArgb: Int?, cutoutMask: Bitmap? = null): Bitmap {
        val output = createBitmap(plan.outputSize.width, plan.outputSize.height)
        val canvas = Canvas(output)
        if (backgroundArgb != null) canvas.drawColor(backgroundArgb)
        draw(canvas, source, plan, cutoutMask = cutoutMask)
        return output
    }

    /**
     * Renders [plan] band by band so that only one band of the source is in memory at a time.
     *
     * Each band is decoded with padding so filtering at its edges has real neighbors, and drawing is
     * clipped to the band's own rows. Android canvas clips are not anti-aliased, so every output pixel
     * is painted by exactly one band and no seam appears.
     *
     * @param bands upright source rows of each band, top to bottom, covering the visible area.
     * @param decodeBand decodes the given padded upright rectangle; the result is recycled after use.
     */
    public fun renderBanded(
        plan: ImageRenderPlan,
        backgroundArgb: Int?,
        bands: List<PixelRect>,
        padding: Int,
        uprightSize: PixelSize,
        cutoutMask: Bitmap? = null,
        decodeBand: (PixelRect) -> DecodedImage,
    ): Bitmap {
        val output = createBitmap(plan.outputSize.width, plan.outputSize.height)
        val canvas = Canvas(output)
        if (backgroundArgb != null) canvas.drawColor(backgroundArgb)
        val outputClip = Rect(0, 0, plan.outputSize.width, plan.outputSize.height)
        try {
            bands.forEach { band ->
                val padded = PixelRect(
                    band.left,
                    (band.top - padding).coerceAtLeast(0),
                    band.right,
                    (band.bottom + padding).coerceAtMost(uprightSize.height),
                )
                val decoded = decodeBand(padded)
                try {
                    val region = decoded.uprightRegion
                    val decodedToUpright = Affine2D.translate(region.left.toDouble(), region.top.toDouble()) *
                        Affine2D.scale(region.width.toDouble() / decoded.bitmap.width, region.height.toDouble() / decoded.bitmap.height)
                    canvas.withClip(outputClip) {
                        concat(plan.sourceToOutput.toAndroidMatrix())
                        clipRect(band.left.toFloat(), band.top.toFloat(), band.right.toFloat(), band.bottom.toFloat())
                        val layer = if (cutoutMask != null) saveLayer(band.left.toFloat(), band.top.toFloat(), band.right.toFloat(), band.bottom.toFloat(), null) else -1
                        drawBitmap(decoded.bitmap, decodedToUpright.toAndroidMatrix(), PAINT)
                        if (cutoutMask != null) {
                            drawBitmap(cutoutMask, maskToUpright(cutoutMask, uprightSize).toAndroidMatrix(), MASK_PAINT)
                            restoreToCount(layer)
                        }
                    }
                } finally {
                    decoded.recycle()
                }
            }
        } catch (error: Throwable) {
            output.recycle()
            throw error
        }
        return output
    }

    private val PAINT = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val MASK_PAINT = Paint(Paint.FILTER_BITMAP_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
}
