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
import kotlin.math.hypot
import androidx.compose.ui.graphics.PathFillType
import com.naury.framekit.core.document.DocumentQuad
import kotlin.math.atan2
import kotlin.math.roundToInt
import androidx.compose.material3.TextButton
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.Stable
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
import com.naury.framekit.image.render.ImageRenderPlan
import com.naury.framekit.android.result.FrameKitException
import android.util.Log
import com.naury.framekit.image.render.PreviewMode
import com.naury.framekit.ui.canvas.CanvasZoomState
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import com.naury.framekit.ui.canvas.ZoomableCanvas
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
    // 문서 보정은 편집 전 원본 전체를 보여 주고 그 위에 네 모서리를 맞춘다.
    val documentMode = state.activeTool == ImageTool.DOCUMENT
    val project = if (documentMode) {
        state.displayed.copy(
            geometry = GeometryEdit(), cutout = null, adjustments = Adjustments(), filter = FilterSelection(),
            overlays = emptyList(), drawing = emptyList(), privacyMasks = emptyList(),
        )
    } else if (state.showingOriginal) {
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
    // 제스처 중의 초안이 검증을 통과하지 못해도 화면 구성에서 예외를 던져 앱이 멈추지 않게 직전 계획을 유지한다.
    val lastPlan = remember { arrayOfNulls<ImageRenderPlan>(1) }
    val plan = remember(project, toolMode, documentMode) {
        try {
            if (toolMode || documentMode) {
                ImageRenderPlanFactory.uncropped(project, metadata, previewLongEdge)
            } else {
                ImageRenderPlanFactory.create(project, metadata, ImageRenderPlanFactory.outputSize(project, metadata, Long.MAX_VALUE))
            }
        } catch (error: FrameKitException) {
            Log.w("FrameKit", "preview plan skipped: ${error.code}")
            null
        }
    }?.also { lastPlan[0] = it } ?: lastPlan[0] ?: return

    // 화면 확대는 편집 결과에 영향이 없다. 도구를 바꾸면(자르기 화면과 결과 화면은 배치가 달라) 처음 크기로 돌아온다.
    val zoomState = remember { CanvasZoomState() }
    val scope = rememberCoroutineScope()
    LaunchedEffect(state.activeTool) { zoomState.reset() }
    // 스티커·텍스트를 잡고 있는 동안에는 두 손가락 제스처를 오버레이 변형에 양보한다.
    val overlayGesture = remember { BooleanArray(1) }
    // 그리기·가리기·자르기는 한 손가락이 편집 동작이라, 확대 중 한 손가락 이동은 그 밖의 화면에서만 쓴다.
    val singleFingerPan = state.activeTool !in setOf(ImageTool.DRAW, ImageTool.PRIVACY, ImageTool.CROP, ImageTool.ROTATE, ImageTool.DOCUMENT)
    // 문서 모서리를 끄는 동안의 모서리 번호(돋보기를 그리는 데 쓴다).
    var draggingCorner by remember { mutableStateOf<Int?>(null) }
    Box(modifier.fillMaxSize()) {
        ZoomableCanvas(
            state = zoomState,
            enabled = !state.showingOriginal,
            modifier = Modifier.fillMaxSize().background(colors.canvasBackground),
            singleFingerPan = singleFingerPan,
            isChildGestureActive = { overlayGesture[0] },
        ) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val padding = with(density) { (if (toolMode || documentMode) 28.dp else 16.dp).toPx().toDouble() }
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
                // 확대 중에는 레이어 안 좌표 1px이 화면에서 zoom px이라, 손잡이 크기·여백을 그만큼 줄여 화면 크기를 유지한다.
                val zoom = zoomState.zoom.toDouble()
                // 스티커를 가장자리로 옮겨도 삭제·복제 손잡이를 누를 수 있도록 손잡이만 화면에 보이는 영역 안쪽으로 당긴다.
                val handleMargin = with(density) { 20.dp.toPx().toDouble() } / zoom
                // 캔버스 위쪽 가운데에는 실행 취소·다시 실행 버튼이 떠 있어, 손잡이가 그 아래로 숨지 않게 위쪽 한계를 더 둔다.
                val handleTop = with(density) { 84.dp.toPx().toDouble() } / zoom
                val centerX = constraints.maxWidth / 2.0
                val centerY = constraints.maxHeight / 2.0
                // 화면에 보이는 영역을 확대 전 좌표로 바꾼 것: l = c + (s - c - pan) / zoom.
                val visibleLeft = centerX + (0 - centerX - zoomState.pan.x) / zoom
                val visibleTop = centerY + (0 - centerY - zoomState.pan.y) / zoom
                val visibleRight = centerX + (constraints.maxWidth - centerX - zoomState.pan.x) / zoom
                val visibleBottom = centerY + (constraints.maxHeight - centerY - zoomState.pan.y) / zoom
                // 0: 삭제, 1: 복제, 2: 한 손가락 크기·회전 손잡이.
                val selectionHandles = selectionCorners?.take(3)?.map { corner ->
                    PointN(
                        corner.x.coerceIn(visibleLeft + handleMargin, (visibleRight - handleMargin).coerceAtLeast(visibleLeft + handleMargin)),
                        corner.y.coerceIn(visibleTop + handleTop, (visibleBottom - handleMargin).coerceAtLeast(visibleTop + handleTop)),
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
                val currentDocumentQuad by rememberUpdatedState(state.documentQuad.takeIf { documentMode })
                val touchRadius = with(density) { 24.dp.toPx().toDouble() } / zoom
                val currentTouchRadius by rememberUpdatedState(touchRadius)
                // 자르기·회전 중에는 손을 뗄 때마다 남은 영역이 화면을 채우도록 부드럽게 확대해 맞춘다.
                val fitPadding = padding.toFloat()
                val areaSize = Size(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat())
                LaunchedEffect(toolMode, project.geometry.crop, project.geometry.quarterTurns, state.draggingCrop, areaSize) {
                    if (!toolMode || state.draggingCrop) return@LaunchedEffect
                    val target = Rect(cropFrame.left.toFloat(), cropFrame.top.toFloat(), cropFrame.right.toFloat(), cropFrame.bottom.toFloat())
                    val (fitZoom, fitPan) = zoomState.fitTarget(target, areaSize, fitPadding)
                    zoomState.animateTo(fitZoom, fitPan)
                }
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
                                    val handle = CropFrameHitTest.find(currentFrame, position.x.toDouble(), position.y.toDouble(), currentTouchRadius)
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
                    ImageTool.DOCUMENT -> Modifier.pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            val quad = currentDocumentQuad ?: return@awaitEachGesture
                            // 모서리는 손가락보다 작아 보여서 넉넉한 반경 안에서 가장 가까운 모서리를 잡는다.
                            val reach = currentTouchRadius * 1.8
                            val corner = quad.corners
                                .mapIndexed { index, point -> index to currentViewport.toViewport(point) }
                                .map { (index, p) -> index to hypot(p.x - down.position.x, p.y - down.position.y) }
                                .filter { it.second <= reach }
                                .minByOrNull { it.second }?.first ?: return@awaitEachGesture
                            down.consume()
                            // 손가락이 모서리를 가리지 않도록, 처음 닿은 위치와 모서리의 차이를 유지하며 옮긴다.
                            val anchor = currentViewport.toViewport(quad.corners[corner])
                            val offset = Offset((anchor.x - down.position.x).toFloat(), (anchor.y - down.position.y).toFloat())
                            draggingCorner = corner
                            do {
                                val event = awaitPointerEvent()
                                if (event.changes.count { it.pressed } >= 2) break
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                val target = change.position + offset
                                val point = currentViewport.toContentUnbounded(target.x.toDouble(), target.y.toDouble())
                                actions.moveDocumentCorner(corner, point.x, point.y)
                                change.consume()
                            } while (change.pressed)
                            draggingCorner = null
                        }
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
                                // 두 번째 손가락이 닿으면 바깥 틀이 확대·이동을 맡는다. 그리던 획은 남기지 않는다.
                                if (event.changes.count { it.pressed } >= 2) {
                                    if (stroking) {
                                        actions.cancelStroke()
                                        stroking = false
                                    }
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
                            val handleRadius = currentTouchRadius
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
                                            overlayGesture[0] = true
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
                                            overlayGesture[0] = false
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
                                        return@awaitEachGesture
                                    }
                                    // 짧게 탭했으면 곧바로 이어지는 두 번째 탭을 기다려 그 지점을 확대하거나 원래 크기로 돌린다.
                                    val tapped = currentEvent.changes.firstOrNull { it.id == down.id }?.let { !it.pressed && !it.isConsumed } == true
                                    if (!tapped) return@awaitEachGesture
                                    val second = withTimeoutOrNull(viewConfiguration.doubleTapTimeoutMillis) { awaitFirstDown(requireUnconsumed = false) }
                                    if (second != null && (second.position - position).getDistance() < viewConfiguration.touchSlop * 3) {
                                        val (targetZoom, targetPan) = zoomState.doubleTapTarget(second.position, Size(size.width.toFloat(), size.height.toFloat()))
                                        scope.launch { zoomState.animateTo(targetZoom, targetPan) }
                                        waitForUpOrCancellation()
                                    }
                                    return@awaitEachGesture
                                }
                                val wasSelected = hit == selectedId
                                overlayGesture[0] = true
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
                                overlayGesture[0] = false
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
                        if (selectionCorners != null && selectionHandles != null) drawSelection(selectionCorners, selectionHandles, colors.accent, zoomState.zoom)
                    val documentQuad = state.documentQuad
                    if (documentMode && documentQuad != null) {
                        drawDocumentQuad(documentQuad, viewport, colors.accent, zoomState.zoom, draggingCorner)
                        val dragged = draggingCorner
                        if (dragged != null) {
                            drawLoupe(state.preview.bitmap, documentQuad.corners[dragged], viewport, contentRect, colors.accent)
                        }
                    }
                    if (toolMode) {
                        drawCropFrame(
                            frame = cropFrame,
                            accent = colors.accent,
                            showGrid = state.draggingCrop || state.activeTool == ImageTool.ROTATE,
                            showHandles = state.activeTool == ImageTool.CROP,
                            zoom = zoomState.zoom,
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
        // 자르기·회전에서는 자동 맞춤이 배율을 정하고, 버튼이 왼쪽 아래 핸들을 가리므로 숨긴다.
        if (!toolMode) ZoomResetButton(zoomState, Modifier.align(Alignment.BottomStart))
    }
}

private val PREVIEW_PAINT = Paint(Paint.FILTER_BITMAP_FLAG)

private fun near(point: PointN, position: Offset, radius: Double): Boolean {
    val dx = point.x - position.x
    val dy = point.y - position.y
    return dx * dx + dy * dy <= radius * radius
}

// 선택 상자와 모서리 버튼: 왼쪽 위는 삭제(×), 오른쪽 위는 복제(+).
private fun DrawScope.drawSelection(corners: List<PointN>, handles: List<PointN>, accent: Color, zoom: Float) {
    // 확대된 레이어 안에 그리므로 화면에서 같은 크기로 보이게 줄인다.
    val unit = 1f / zoom.coerceAtLeast(1f)
    val path = Path().apply {
        moveTo(corners[0].x.toFloat(), corners[0].y.toFloat())
        corners.drop(1).forEach { lineTo(it.x.toFloat(), it.y.toFloat()) }
        close()
    }
    drawPath(path, Color.Black.copy(alpha = 0.4f), style = Stroke(3.dp.toPx() * unit))
    drawPath(path, Color.White, style = Stroke(1.dp.toPx() * unit))
    val radius = 12.dp.toPx() * unit * unit
    val mark = 5.dp.toPx() * unit
    val stroke = 2.dp.toPx() * unit
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

// 문서 바깥을 어둡게, 네 변과 모서리 손잡이를 그린다. 끌고 있는 모서리는 크게 보여 준다.
private fun DrawScope.drawDocumentQuad(quad: DocumentQuad, viewport: ViewportTransform, accent: Color, zoom: Float, dragging: Int?) {
    val unit = 1f / zoom.coerceAtLeast(1f)
    val points = quad.corners.map { viewport.toViewport(it) }.map { Offset(it.x.toFloat(), it.y.toFloat()) }
    val outline = Path().apply {
        moveTo(points[0].x, points[0].y)
        points.drop(1).forEach { lineTo(it.x, it.y) }
        close()
    }
    val outside = Path().apply {
        fillType = PathFillType.EvenOdd
        addRect(androidx.compose.ui.geometry.Rect(Offset.Zero, size))
        addPath(outline)
    }
    drawPath(outside, Color.Black.copy(alpha = 0.5f))
    drawPath(outline, accent.copy(alpha = 0.12f))
    drawPath(outline, accent, style = Stroke(2.dp.toPx() * unit))
    points.forEachIndexed { index, point ->
        val radius = (if (index == dragging) 13.dp else 10.dp).toPx() * unit
        drawCircle(Color.Black.copy(alpha = 0.35f), radius + 2.dp.toPx() * unit, point)
        drawCircle(Color.White, radius, point)
        drawCircle(accent, radius, point, style = Stroke(3.dp.toPx() * unit))
    }
}

/**
 * 끌고 있는 모서리 주변을 확대해 보여 주는 돋보기. 손가락에 가려 보이지 않는 종이 끝을 정확히 맞추게 한다.
 * 모서리가 화면 왼쪽 위에 있으면 오른쪽 위에, 아니면 왼쪽 위에 둔다.
 */
private fun DrawScope.drawLoupe(bitmap: Bitmap, corner: PointN, viewport: ViewportTransform, content: RectN, accent: Color) {
    if (bitmap.isRecycled) return
    val radius = 56.dp.toPx()
    val margin = 16.dp.toPx()
    val at = viewport.toViewport(corner)
    val onLeftTop = at.x < size.width / 2 && at.y < radius * 2 + margin * 2
    val center = Offset(if (onLeftTop) size.width - margin - radius else margin + radius, margin + radius)
    val magnification = 3f
    // 콘텐츠 정규화 좌표 → bitmap 픽셀 → 돋보기 화면 좌표.
    val pxPerSource = (content.width / bitmap.width).toFloat() * magnification
    val bx = (corner.x * bitmap.width).toFloat()
    val by = (corner.y * bitmap.height).toFloat()
    drawCircle(Color.Black, radius, center)
    drawIntoCanvas { canvas ->
        val native = canvas.nativeCanvas
        val checkpoint = native.save()
        native.clipPath(android.graphics.Path().apply { addCircle(center.x, center.y, radius, android.graphics.Path.Direction.CW) })
        val matrix = android.graphics.Matrix().apply {
            setTranslate(-bx, -by)
            postScale(pxPerSource, pxPerSource)
            postTranslate(center.x, center.y)
        }
        native.drawBitmap(bitmap, matrix, LOUPE_PAINT)
        native.restoreToCount(checkpoint)
    }
    val cross = 10.dp.toPx()
    drawLine(accent, center - Offset(cross, 0f), center + Offset(cross, 0f), 2.dp.toPx())
    drawLine(accent, center - Offset(0f, cross), center + Offset(0f, cross), 2.dp.toPx())
    drawCircle(Color.White, radius, center, style = Stroke(3.dp.toPx()))
}

private val LOUPE_PAINT = Paint(Paint.FILTER_BITMAP_FLAG)
