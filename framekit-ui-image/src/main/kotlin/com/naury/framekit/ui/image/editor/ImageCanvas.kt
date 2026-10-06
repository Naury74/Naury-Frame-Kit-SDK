package com.naury.framekit.ui.image.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.input.pointer.positionChanged
import androidx.core.graphics.withMatrix
import com.naury.framekit.core.geometry.PointN
import com.naury.framekit.core.overlay.ImageOverlay
import com.naury.framekit.image.overlay.OverlayRenderer
import com.naury.framekit.image.render.toAndroidMatrix
import kotlin.math.abs
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import kotlin.math.atan2
import kotlin.math.roundToInt
import androidx.compose.material3.TextButton
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.Stable
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
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
import android.graphics.Bitmap
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
import com.naury.framekit.ui.canvas.CropFrameHitTest
import com.naury.framekit.ui.canvas.drawCropFrame
import com.naury.framekit.ui.canvas.excludeCropHandleGestures
import com.naury.framekit.image.render.CanvasGeometryRenderer
import com.naury.framekit.image.render.ImageRenderPlanFactory
import com.naury.framekit.image.render.PreviewMode
import com.naury.framekit.ui.canvas.CanvasZoomState
import com.naury.framekit.ui.canvas.ZoomResetButton
import com.naury.framekit.ui.design.FrameKitTheme
import com.naury.framekit.ui.image.contract.ImageTool
import com.naury.framekit.ui.R as UiR

/**
 * 공용 렌더러로 프로젝트를 그리고 캔버스 제스처를 처리한다.
 *
 * 도구 밖에서는 편집 결과를 보여주고, 길게 누르면 원본을 보여준다. 기하 도구가 열려 있는 동안에는
 * 회전된 이미지 전체 위에 자르기 프레임을 표시한다.
 */
@Composable
internal fun ImageCanvas(
    state: ImageEditorUiState.Ready,
    rendered: RenderedPreview?,
    cutoutMask: Bitmap?,
    actions: ImageCanvasActions,
    modifier: Modifier = Modifier,
) {
    val colors = FrameKitTheme.colors
    val density = LocalDensity.current
    val metadata = state.source.metadata
    val toolMode = state.activeTool?.isGeometry == true
    val project = if (state.showingOriginal) {
        state.displayed.copy(geometry = GeometryEdit(), cutout = null, adjustments = Adjustments(), filter = FilterSelection(), privacyMasks = emptyList())
    } else {
        state.displayed
    }
    val mode = if (toolMode) PreviewMode.UNCROPPED else PreviewMode.RESULT
    // 색 효과가 있으면 같은 형태(geometry)로 렌더된 미리보기를 쓴다. 새 값이 렌더되는 동안에는 직전 결과를 보여 준다.
    val effectedBitmap = rendered
        ?.takeIf { project.needsRenderedPreview && it.mode == mode && it.project.geometry == project.geometry }
        ?.bitmap
    val previewLongEdge = maxOf(state.preview.bitmap.width, state.preview.bitmap.height)
    val plan = remember(project, toolMode) {
        if (toolMode) {
            ImageRenderPlanFactory.uncropped(project, metadata, previewLongEdge)
        } else {
            ImageRenderPlanFactory.create(project, metadata, ImageRenderPlanFactory.outputSize(project, metadata, Long.MAX_VALUE))
        }
    }

    // 그리기·가리기에서만 쓰는 화면 확대. 편집 결과에는 영향이 없고 도구를 바꾸면 처음 크기로 돌아온다.
    val zoomable = state.activeTool == ImageTool.DRAW || state.activeTool == ImageTool.PRIVACY
    val zoomState = remember { CanvasZoomState() }
    LaunchedEffect(zoomable) { if (!zoomable) zoomState.reset() }
    Box(modifier.fillMaxSize().background(colors.canvasBackground).clipToBounds()) {
        BoxWithConstraints(
            Modifier.fillMaxSize().graphicsLayer {
                scaleX = zoomState.zoom
                scaleY = zoomState.zoom
                translationX = zoomState.pan.x
                translationY = zoomState.pan.y
            },
        ) {
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
            LaunchedEffect(viewportWidth, viewportHeight) { actions.onViewportSize(viewportWidth, viewportHeight) }
            val overlayRenderer = remember { OverlayRenderer() }
            val showOverlays = !toolMode && !state.showingOriginal
            val selected = state.selectedOverlayId?.let { id -> project.overlays.firstOrNull { it.id == id } }
                ?.takeIf { showOverlays && state.activeTool != ImageTool.TEXT && state.activeTool != ImageTool.DRAW && state.activeTool != ImageTool.PRIVACY }
            val selectionCorners = selected?.let { overlay ->
                overlayRenderer.corners(overlay, plan.outputSize).map { outputToViewport.map(it) }
            }
            val currentProject by rememberUpdatedState(project)
            val currentSelection by rememberUpdatedState(selected?.id)
            // 스티커를 가장자리로 옮겨도 삭제·복제 손잡이를 누를 수 있도록 손잡이만 화면 안쪽으로 당긴다.
            val handleMargin = with(density) { 20.dp.toPx().toDouble() }
            // 0: 삭제, 1: 복제, 2: 한 손가락 크기·회전 손잡이.
            val selectionHandles = selectionCorners?.take(3)?.map { corner ->
                PointN(
                    corner.x.coerceIn(handleMargin, (constraints.maxWidth - handleMargin).coerceAtLeast(handleMargin)),
                    corner.y.coerceIn(handleMargin, (constraints.maxHeight - handleMargin).coerceAtLeast(handleMargin)),
                )
            }
            val currentCorners by rememberUpdatedState(selectionHandles)
            val selectionCenter = selectionCorners?.let { c -> PointN(c.sumOf { it.x } / c.size, c.sumOf { it.y } / c.size) }
            val currentCenter by rememberUpdatedState(selectionCenter)
            val currentOutputToViewport by rememberUpdatedState(outputToViewport)
            val currentOutputSize by rememberUpdatedState(plan.outputSize)
            val contentRect = viewport.toViewport(RectN.Full)
            val currentViewport by rememberUpdatedState(viewport)
            val currentFrame by rememberUpdatedState(cropFrame)
            val touchRadius = with(density) { 24.dp.toPx().toDouble() }
            val cropDescription = stringResource(UiR.string.framekit_crop_area)
            val originalDescription = stringResource(UiR.string.framekit_show_original)

            val gestures = when (state.activeTool) {
                ImageTool.CROP -> Modifier
                    .excludeCropHandleGestures(cropFrame, touchRadius.toFloat())
                    .semantics { contentDescription = cropDescription }
                    .pointerInput(Unit) {
                        var total = Offset.Zero
                        var active = false
                        detectDragGestures(
                            onDragStart = { position ->
                                val handle = CropFrameHitTest.find(currentFrame, position.x.toDouble(), position.y.toDouble(), touchRadius)
                                active = handle != null
                                total = Offset.Zero
                                if (handle != null) actions.beginCropDrag(handle)
                            },
                            onDrag = { change, amount ->
                                if (!active) return@detectDragGestures
                                change.consume()
                                total += amount
                                val fitted = currentViewport.fittedSize
                                actions.dragCrop(total.x / fitted.width, total.y / fitted.height)
                            },
                            onDragEnd = { if (active) actions.endCropDrag() },
                            onDragCancel = { if (active) actions.endCropDrag() },
                        )
                    }
                ImageTool.ROTATE, ImageTool.TEXT -> Modifier
                ImageTool.DRAW, ImageTool.PRIVACY -> Modifier.pointerInput(Unit) {
                    val minDistance = 2.dp.toPx().toDouble()
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        // 사진 바깥(letterbox)에서 시작한 획은 만들지 않는다.
                        val start = currentViewport.toContent(down.position.x.toDouble(), down.position.y.toDouble())
                        down.consume()
                        var stroking = start != null
                        if (start != null) actions.beginStroke(start.x, start.y, down.pressure.coerceIn(0f, 1f).toDouble())
                        val fitted = currentViewport.fittedSize
                        do {
                            val event = awaitPointerEvent()
                            // 두 손가락이면 그리기를 멈추고 캔버스를 확대·이동한다. 세밀한 부분을 가릴 때 쓴다.
                            if (event.changes.count { it.pressed } >= 2) {
                                if (stroking) {
                                    actions.cancelStroke()
                                    stroking = false
                                }
                                zoomState.apply(event.calculateZoom(), event.calculatePan(), size.width.toFloat(), size.height.toFloat())
                                event.changes.forEach { it.consume() }
                                continue
                            }
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (stroking) {
                                val point = currentViewport.toContentUnbounded(change.position.x.toDouble(), change.position.y.toDouble())
                                actions.extendStroke(
                                    point.x.coerceIn(0.0, 1.0),
                                    point.y.coerceIn(0.0, 1.0),
                                    change.pressure.coerceIn(0f, 1f).toDouble(),
                                    minDistance / fitted.width,
                                    minDistance / fitted.height,
                                )
                            }
                            change.consume()
                        } while (event.changes.any { it.pressed })
                        if (stroking) actions.finishStroke()
                    }
                }
                else -> Modifier
                    .semantics { contentDescription = originalDescription }
                    .pointerInput(Unit) {
                        val handleRadius = 24.dp.toPx().toDouble()
                        val snap = 6.dp.toPx().toDouble()
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val position = down.position
                            val corners = currentCorners
                            val selectedId = currentSelection
                            if (selectedId != null && corners != null) {
                                when {
                                    near(corners[0], position, handleRadius) -> {
                                        if (waitForUpOrCancellation() != null) actions.deleteOverlay(selectedId)
                                        return@awaitEachGesture
                                    }
                                    near(corners[1], position, handleRadius) -> {
                                        if (waitForUpOrCancellation() != null) actions.duplicateOverlay(selectedId)
                                        return@awaitEachGesture
                                    }
                                    near(corners[2], position, handleRadius) && currentCenter != null -> {
                                        // 중심에서 손가락까지의 거리·각도 변화로 크기와 회전을 바꾼다. 한 손으로도 조절할 수 있다.
                                        val center = Offset(currentCenter!!.x.toFloat(), currentCenter!!.y.toFloat())
                                        val start = position - center
                                        if (start.getDistance() < 1f) return@awaitEachGesture
                                        actions.beginOverlayGesture(selectedId)
                                        do {
                                            val event = awaitPointerEvent()
                                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                            val now = change.position - center
                                            val zoom = now.getDistance() / start.getDistance()
                                            val rotation = Math.toDegrees(atan2(now.y.toDouble(), now.x.toDouble()) - atan2(start.y.toDouble(), start.x.toDouble()))
                                            actions.updateOverlayGesture(0.0, 0.0, zoom.toDouble(), rotation, 0.0, 0.0)
                                            change.consume()
                                        } while (change.pressed)
                                        actions.finishOverlayGesture()
                                        return@awaitEachGesture
                                    }
                                }
                            }
                            val output = currentOutputToViewport.inverted().map(position.x.toDouble(), position.y.toDouble())
                            val hit = overlayRenderer.hitTest(currentProject.overlays, output.x, output.y, currentOutputSize, slopPx = handleRadius / 2)
                            if (hit == null) {
                                actions.selectOverlay(null)
                                if (awaitLongPressOrCancellation(down.id) != null) {
                                    actions.showOriginal(true)
                                    waitForUpOrCancellation()
                                    actions.showOriginal(false)
                                }
                                return@awaitEachGesture
                            }
                            val wasSelected = hit == selectedId
                            actions.beginOverlayGesture(hit)
                            val fitted = currentViewport.fittedSize
                            var pan = Offset.Zero
                            var zoom = 1f
                            var rotation = 0f
                            var moved = false
                            val slop = viewConfiguration.touchSlop
                            do {
                                val event = awaitPointerEvent()
                                pan += event.calculatePan()
                                zoom *= event.calculateZoom()
                                rotation += event.calculateRotation()
                                if (!moved && (pan.getDistance() > slop || abs(zoom - 1f) > 0.02f || abs(rotation) > 2f)) moved = true
                                if (moved) {
                                    actions.updateOverlayGesture(
                                        pan.x / fitted.width,
                                        pan.y / fitted.height,
                                        zoom.toDouble(),
                                        rotation.toDouble(),
                                        snap / fitted.width,
                                        snap / fitted.height,
                                    )
                                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                                }
                            } while (event.changes.any { it.pressed })
                            actions.finishOverlayGesture()
                            val overlay = currentProject.overlays.firstOrNull { it.id == hit }
                            if (!moved && wasSelected && overlay is ImageOverlay.Text) actions.editText(hit)
                        }
                    }
            }

            Canvas(Modifier.fillMaxSize().then(gestures)) {
                drawIntoCanvas { canvas ->
                    if (effectedBitmap != null && !effectedBitmap.isRecycled) {
                        val target = RectF(contentRect.left.toFloat(), contentRect.top.toFloat(), contentRect.right.toFloat(), contentRect.bottom.toFloat())
                        canvas.nativeCanvas.drawBitmap(effectedBitmap, null, target, PREVIEW_PAINT)
                    } else {
                        CanvasGeometryRenderer.draw(canvas.nativeCanvas, state.preview, plan, outputToViewport, cutoutMask.takeIf { project.cutout != null })
                    }
                }
                if (showOverlays && (project.overlays.isNotEmpty() || project.drawing.isNotEmpty())) {
                    drawIntoCanvas { canvas ->
                        canvas.nativeCanvas.withMatrix(outputToViewport.toAndroidMatrix()) {
                            overlayRenderer.draw(this, plan.outputSize, project.overlays, project.drawing)
                        }
                    }
                }
                if (selectionCorners != null && selectionHandles != null) drawSelection(selectionCorners, selectionHandles, colors.accent)
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
        ZoomResetButton(zoomState, Modifier.align(Alignment.BottomStart))
    }
}

private val PREVIEW_PAINT = Paint(Paint.FILTER_BITMAP_FLAG)

private fun near(point: PointN, position: Offset, radius: Double): Boolean {
    val dx = point.x - position.x
    val dy = point.y - position.y
    return dx * dx + dy * dy <= radius * radius
}

// 선택 상자와 모서리 버튼: 왼쪽 위는 삭제(×), 오른쪽 위는 복제(+).
private fun DrawScope.drawSelection(corners: List<PointN>, handles: List<PointN>, accent: Color) {
    val path = Path().apply {
        moveTo(corners[0].x.toFloat(), corners[0].y.toFloat())
        corners.drop(1).forEach { lineTo(it.x.toFloat(), it.y.toFloat()) }
        close()
    }
    drawPath(path, Color.Black.copy(alpha = 0.4f), style = Stroke(3.dp.toPx()))
    drawPath(path, Color.White, style = Stroke(1.dp.toPx()))
    val radius = 12.dp.toPx()
    val mark = 5.dp.toPx()
    val stroke = 2.dp.toPx()
    handles.forEachIndexed { index, corner ->
        val center = Offset(corner.x.toFloat(), corner.y.toFloat())
        drawCircle(if (index == 0) Color(0xFFFF3B30) else accent, radius, center)
        drawLine(Color.White, center - Offset(mark, 0f), center + Offset(mark, 0f), stroke)
        if (index == 0) {
            drawLine(Color.White, center - Offset(mark, mark), center + Offset(mark, mark), stroke)
            drawLine(Color.White, center - Offset(mark, -mark), center + Offset(mark, -mark), stroke)
        } else if (index == 1) {
            drawLine(Color.White, center - Offset(mark, 0f), center + Offset(mark, 0f), stroke)
            drawLine(Color.White, center - Offset(0f, mark), center + Offset(0f, mark), stroke)
        } else {
            // 크기·회전 손잡이: 대각선 양방향 화살표 모양.
            drawLine(Color.White, center - Offset(mark, mark), center + Offset(mark, mark), stroke)
            drawLine(Color.White, center + Offset(mark, mark), center + Offset(0f, mark), stroke)
            drawLine(Color.White, center + Offset(mark, mark), center + Offset(mark, 0f), stroke)
        }
    }
}
