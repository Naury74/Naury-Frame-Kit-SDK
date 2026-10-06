package com.naury.framekit.ui.image.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import android.graphics.Paint
import android.graphics.RectF
import com.naury.framekit.core.effect.Adjustments
import com.naury.framekit.core.effect.FilterSelection
import com.naury.framekit.core.geometry.Affine2D
import com.naury.framekit.core.geometry.CropHandle
import com.naury.framekit.core.geometry.GeometryEdit
import com.naury.framekit.core.geometry.RectN
import com.naury.framekit.core.geometry.Size2D
import com.naury.framekit.core.geometry.ViewportTransform
import com.naury.framekit.image.render.CanvasGeometryRenderer
import com.naury.framekit.image.render.ImageRenderPlanFactory
import com.naury.framekit.image.render.PreviewMode
import com.naury.framekit.ui.design.FrameKitTheme
import com.naury.framekit.ui.image.R
import com.naury.framekit.ui.image.contract.ImageTool
import com.naury.framekit.ui.R as UiR

/**
 * Draws the project with the shared renderer and handles canvas gestures.
 *
 * Outside a tool the edited result is shown; long-pressing shows the original. While a geometry tool
 * is open the whole rotated image is shown with the crop frame on top.
 */
@Composable
internal fun ImageCanvas(
    state: ImageEditorUiState.Ready,
    rendered: RenderedPreview?,
    onViewportSize: (Int, Int) -> Unit,
    onBeginCropDrag: (CropHandle) -> Unit,
    onDragCrop: (Double, Double) -> Unit,
    onEndCropDrag: () -> Unit,
    onShowOriginal: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = FrameKitTheme.colors
    val density = LocalDensity.current
    val metadata = state.source.metadata
    val toolMode = state.activeTool?.isDraft == true
    val project = if (state.showingOriginal) {
        state.displayed.copy(geometry = GeometryEdit(), adjustments = Adjustments(), filter = FilterSelection())
    } else {
        state.displayed
    }
    val mode = if (toolMode) PreviewMode.UNCROPPED else PreviewMode.RESULT
    // 색 효과가 있으면 같은 형태(geometry)로 렌더된 미리보기를 쓴다. 새 값이 렌더되는 동안에는 직전 결과를 보여 준다.
    val effectedBitmap = rendered
        ?.takeIf { !project.colorSpec.isIdentity && it.mode == mode && it.project.geometry == project.geometry }
        ?.bitmap
    val previewLongEdge = maxOf(state.preview.bitmap.width, state.preview.bitmap.height)
    val plan = remember(project, toolMode) {
        if (toolMode) {
            ImageRenderPlanFactory.uncropped(project, metadata, previewLongEdge)
        } else {
            ImageRenderPlanFactory.create(project, metadata, ImageRenderPlanFactory.outputSize(project, metadata, Long.MAX_VALUE))
        }
    }

    BoxWithConstraints(modifier.fillMaxSize().background(colors.canvasBackground)) {
        val padding = with(density) { (if (toolMode) 28.dp else 16.dp).toPx().toDouble() }
        val viewport = ViewportTransform(
            contentSize = Size2D(plan.outputSize.width.toDouble(), plan.outputSize.height.toDouble()),
            viewportWidth = constraints.maxWidth.toDouble().coerceAtLeast(1.0),
            viewportHeight = constraints.maxHeight.toDouble().coerceAtLeast(1.0),
            padding = padding,
        )
        val outputToViewport = viewport.contentToViewport *
            Affine2D.scale(1.0 / plan.outputSize.width, 1.0 / plan.outputSize.height)
        val cropFrame = viewport.toViewport(project.geometry.crop)
        val viewportWidth = constraints.maxWidth
        val viewportHeight = constraints.maxHeight
        LaunchedEffect(viewportWidth, viewportHeight) { onViewportSize(viewportWidth, viewportHeight) }
        val contentRect = viewport.toViewport(RectN.Full)
        val currentViewport by rememberUpdatedState(viewport)
        val currentFrame by rememberUpdatedState(cropFrame)
        val touchRadius = with(density) { 24.dp.toPx().toDouble() }
        val cropDescription = stringResource(R.string.framekit_crop_area)
        val originalDescription = stringResource(UiR.string.framekit_show_original)

        val gestures = when (state.activeTool) {
            ImageTool.CROP -> Modifier
                .excludeSystemGestures(cropFrame, touchRadius.toFloat())
                .semantics { contentDescription = cropDescription }
                .pointerInput(Unit) {
                    var total = Offset.Zero
                    var active = false
                    detectDragGestures(
                        onDragStart = { position ->
                            val handle = CropFrameHitTest.find(currentFrame, position.x.toDouble(), position.y.toDouble(), touchRadius)
                            active = handle != null
                            total = Offset.Zero
                            if (handle != null) onBeginCropDrag(handle)
                        },
                        onDrag = { change, amount ->
                            if (!active) return@detectDragGestures
                            change.consume()
                            total += amount
                            val fitted = currentViewport.fittedSize
                            onDragCrop(total.x / fitted.width, total.y / fitted.height)
                        },
                        onDragEnd = { if (active) onEndCropDrag() },
                        onDragCancel = { if (active) onEndCropDrag() },
                    )
                }
            null -> Modifier
                .semantics { contentDescription = originalDescription }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onLongPress = { onShowOriginal(true) },
                        onPress = {
                            tryAwaitRelease()
                            onShowOriginal(false)
                        },
                    )
                }
            else -> Modifier
        }

        Canvas(Modifier.fillMaxSize().then(gestures)) {
            drawIntoCanvas { canvas ->
                if (effectedBitmap != null && !effectedBitmap.isRecycled) {
                    val target = RectF(contentRect.left.toFloat(), contentRect.top.toFloat(), contentRect.right.toFloat(), contentRect.bottom.toFloat())
                    canvas.nativeCanvas.drawBitmap(effectedBitmap, null, target, PREVIEW_PAINT)
                } else {
                    CanvasGeometryRenderer.draw(canvas.nativeCanvas, state.preview, plan, outputToViewport)
                }
            }
            if (toolMode) {
                drawCropFrame(
                    frame = cropFrame,
                    accent = colors.accent,
                    showGrid = state.draggingCrop || state.activeTool == ImageTool.ROTATE,
                    showHandles = state.activeTool == ImageTool.CROP,
                )
            }
        }

        if (state.showingOriginal && !state.displayed.geometry.isIdentity) {
            Text(
                stringResource(UiR.string.framekit_original_badge),
                color = colors.foreground,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(16.dp)
                    .background(colors.surface.copy(alpha = 0.8f), MaterialTheme.shapes.small)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
    }
}

/**
 * 가장자리 근처의 crop 핸들을 끌 때 시스템 뒤로 가기 제스처가 먼저 반응하지 않도록 핸들 주변만 제외한다.
 * 시스템은 가장자리마다 제외 높이를 제한하므로 화면 전체가 아니라 모서리와 좌우 중앙 핸들만 등록한다.
 */
private fun Modifier.excludeSystemGestures(frame: RectN, radius: Float): Modifier {
    val left = frame.left.toFloat()
    val right = frame.right.toFloat()
    val top = frame.top.toFloat()
    val bottom = frame.bottom.toFloat()
    val centerY = (top + bottom) / 2f
    return listOf(
        Offset(left, top), Offset(right, top), Offset(left, bottom), Offset(right, bottom),
        Offset(left, centerY), Offset(right, centerY),
    ).fold(this) { modifier, point ->
        modifier.systemGestureExclusion { Rect(point.x - radius, point.y - radius, point.x + radius, point.y + radius) }
    }
}

private val PREVIEW_PAINT = Paint(Paint.FILTER_BITMAP_FLAG)

private fun DrawScope.drawCropFrame(frame: RectN, accent: Color, showGrid: Boolean, showHandles: Boolean) {
    val left = frame.left.toFloat()
    val top = frame.top.toFloat()
    val right = frame.right.toFloat()
    val bottom = frame.bottom.toFloat()
    val scrim = Color.Black.copy(alpha = 0.55f)
    drawRect(scrim, Offset.Zero, Size(size.width, top))
    drawRect(scrim, Offset(0f, bottom), Size(size.width, size.height - bottom))
    drawRect(scrim, Offset(0f, top), Size(left, bottom - top))
    drawRect(scrim, Offset(right, top), Size(size.width - right, bottom - top))

    val line = 1.dp.toPx()
    if (showGrid) {
        val gridColor = Color.White.copy(alpha = 0.45f)
        for (index in 1..2) {
            val x = left + (right - left) * index / 3f
            val y = top + (bottom - top) * index / 3f
            drawLine(gridColor, Offset(x, top), Offset(x, bottom), line)
            drawLine(gridColor, Offset(left, y), Offset(right, y), line)
        }
    }
    drawRect(Color.White, Offset(left, top), Size(right - left, bottom - top), style = Stroke(line))

    if (showHandles) {
        val length = 18.dp.toPx()
        val thickness = 3.dp.toPx()
        val handleColor = Color.White
        listOf(
            Triple(left, top, 1f to 1f),
            Triple(right, top, -1f to 1f),
            Triple(right, bottom, -1f to -1f),
            Triple(left, bottom, 1f to -1f),
        ).forEach { (x, y, direction) ->
            drawLine(handleColor, Offset(x, y), Offset(x + length * direction.first, y), thickness)
            drawLine(handleColor, Offset(x, y), Offset(x, y + length * direction.second), thickness)
        }
        val midLength = 14.dp.toPx()
        val centerX = (left + right) / 2
        val centerY = (top + bottom) / 2
        drawLine(accent, Offset(centerX - midLength / 2, top), Offset(centerX + midLength / 2, top), thickness)
        drawLine(accent, Offset(centerX - midLength / 2, bottom), Offset(centerX + midLength / 2, bottom), thickness)
        drawLine(accent, Offset(left, centerY - midLength / 2), Offset(left, centerY + midLength / 2), thickness)
        drawLine(accent, Offset(right, centerY - midLength / 2), Offset(right, centerY + midLength / 2), thickness)
    }
}
