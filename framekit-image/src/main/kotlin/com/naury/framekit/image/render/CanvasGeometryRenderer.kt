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
 * geometry 편집용 CPU renderer다.
 *
 * 같은 [draw] 호출로 화면 미리보기(출력→viewport 변환을 추가)와 내보내기 bitmap을 모두 그린다.
 * 선택 프레임·그리드·핸들은 UI가 그 위에 그리며 plan에는 포함되지 않는다.
 */
public object CanvasGeometryRenderer {

    /**
     * [plan]에 따라 [source]를 [canvas]에 그리며, 출력 영역으로 clip한다.
     *
     * @param outputToTarget 출력 픽셀에서 canvas 픽셀로의 추가 변환. 예를 들어 결과를 viewport에
     *   맞출 때 쓴다. 내보내기에서는 항등 변환이다.
     * @param cutoutMask 정방향 원본 전체를 덮는 피사체 mask. alpha가 0인 곳의 배경은
     *   투명해진다.
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
     * [plan]을 새 ARGB_8888 bitmap으로 렌더링한다.
     *
     * @param backgroundArgb 이미지 아래에 칠할 색. 투명도를 유지하려면 `null`.
     */
    public fun render(source: DecodedImage, plan: ImageRenderPlan, backgroundArgb: Int?, cutoutMask: Bitmap? = null): Bitmap {
        val output = createBitmap(plan.outputSize.width, plan.outputSize.height)
        val canvas = Canvas(output)
        if (backgroundArgb != null) canvas.drawColor(backgroundArgb)
        draw(canvas, source, plan, cutoutMask = cutoutMask)
        return output
    }

    /**
     * 한 번에 원본의 띠 하나만 메모리에 올라오도록 [plan]을 띠 단위로 렌더링한다.
     *
     * 각 띠는 가장자리 filtering이 실제 이웃 픽셀을 쓰도록 padding을 붙여 디코딩하고, 그리기는
     * 그 띠의 행으로 clip한다. Android canvas clip은 anti-aliasing되지 않으므로 모든 출력 픽셀은
     * 정확히 한 띠가 칠하며 이음새가 생기지 않는다.
     *
     * @param bands 각 띠의 정방향 원본 행 범위. 위에서 아래 순서이며 보이는 영역을 덮는다.
     * @param decodeBand padding이 붙은 정방향 사각형을 디코딩한다. 결과는 사용 후 recycle된다.
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
