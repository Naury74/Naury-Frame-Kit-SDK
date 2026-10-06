package com.naury.framekit.image.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withMatrix
import com.naury.framekit.core.geometry.Affine2D
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
     */
    public fun draw(
        canvas: Canvas,
        source: DecodedImage,
        plan: ImageRenderPlan,
        outputToTarget: Affine2D = Affine2D.Identity,
    ) {
        val bitmap = source.bitmap
        val decodedToUpright = Affine2D.scale(
            source.uprightSize.width.toDouble() / bitmap.width,
            source.uprightSize.height.toDouble() / bitmap.height,
        )
        canvas.withMatrix(outputToTarget.toAndroidMatrix()) {
            clipRect(0f, 0f, plan.outputSize.width.toFloat(), plan.outputSize.height.toFloat())
            drawBitmap(bitmap, (plan.sourceToOutput * decodedToUpright).toAndroidMatrix(), PAINT)
        }
    }

    /**
     * Renders [plan] into a new ARGB_8888 bitmap.
     *
     * @param backgroundArgb color painted below the image, or `null` to keep transparency.
     */
    public fun render(source: DecodedImage, plan: ImageRenderPlan, backgroundArgb: Int?): Bitmap {
        val output = createBitmap(plan.outputSize.width, plan.outputSize.height)
        val canvas = Canvas(output)
        if (backgroundArgb != null) canvas.drawColor(backgroundArgb)
        draw(canvas, source, plan)
        return output
    }

    private val PAINT = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
}
