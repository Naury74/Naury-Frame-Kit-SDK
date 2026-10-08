package com.naury.framekit.ui.video.editor

import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.positionChanged
import androidx.core.graphics.withMatrix
import com.naury.framekit.core.geometry.Affine2D
import com.naury.framekit.core.model.PixelSize
import com.naury.framekit.core.overlay.ImageOverlay
import com.naury.framekit.image.overlay.OverlayRenderer
import com.naury.framekit.image.render.toAndroidMatrix
import kotlin.math.abs
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Rect
import androidx.compose.runtime.LaunchedEffect
import com.naury.framekit.ui.canvas.ZoomResetButton
import com.naury.framekit.ui.canvas.ZoomableCanvas
import com.naury.framekit.ui.canvas.CanvasZoomState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.naury.framekit.core.geometry.RectN
import com.naury.framekit.core.geometry.Size2D
import com.naury.framekit.core.geometry.ViewportTransform
import com.naury.framekit.core.overlay.MaskShape
import com.naury.framekit.core.video.TimedPrivacyMask
import com.naury.framekit.ui.R as UiR
import com.naury.framekit.ui.canvas.CropFrameHitTest
import com.naury.framekit.ui.canvas.drawCropFrame
import com.naury.framekit.ui.canvas.excludeCropHandleGestures
import com.naury.framekit.ui.design.FrameKitTheme
import com.naury.framekit.ui.video.R
import com.naury.framekit.ui.video.contract.VideoTool
import kotlin.math.roundToInt

/**
 * TextureView 위의 영상 미리보기. 그 위에 자르기 프레임과 가리기 마스크를 그린다.
 *
 * 오버레이 좌표가 출력 캔버스와 일치하도록 surface를 맞춰진 콘텐츠 위에 정확히 배치한다.
 * 도구 밖에서는 탭하면 재생/일시정지가 전환된다.
 */
@Composable
internal fun VideoCanvas(
    state: VideoEditorUiState.Ready,
    previewSize: Pair<Int, Int>?,
    positionUs: Long,
    actions: VideoCanvasActions,
    onAttachSurface: (Surface, Int, Int) -> Unit,
    onDetachSurface: (Surface) -> Unit,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = FrameKitTheme.colors
    val density = LocalDensity.current
    val toolMode = state.activeTool?.isGeometry == true
    // 화면 확대는 결과에 영향이 없다. 도구를 바꾸면 처음 크기로 돌아온다.
    val zoomState = remember { CanvasZoomState() }
    LaunchedEffect(state.activeTool) { zoomState.reset() }
    val overlayGesture = remember { BooleanArray(1) }
    // 가리기·자르기·텍스트·스티커는 한 손가락이 편집 동작이라, 확대 중 한 손가락 이동은 그 밖의 화면에서만 쓴다.
    val singleFingerPan = state.activeTool == null || state.activeTool !in setOf(
        VideoTool.PRIVACY, VideoTool.CROP, VideoTool.ROTATE, VideoTool.TEXT, VideoTool.STICKER, VideoTool.TRIM,
    )
    Box(modifier.fillMaxSize()) {
        ZoomableCanvas(
            state = zoomState,
            enabled = true,
            modifier = Modifier.fillMaxSize().background(colors.canvasBackground),
            singleFingerPan = singleFingerPan,
            isChildGestureActive = { overlayGesture[0] },
        ) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val size = previewSize ?: return@BoxWithConstraints
                val padding = with(density) { (if (toolMode) 28.dp else 16.dp).toPx().toDouble() }
                val viewport = ViewportTransform(
                    contentSize = Size2D(size.first.toDouble(), size.second.toDouble()),
                    viewportWidth = constraints.maxWidth.toDouble().coerceAtLeast(1.0),
                    viewportHeight = constraints.maxHeight.toDouble().coerceAtLeast(1.0),
                    padding = padding,
                )
                val content = viewport.toViewport(RectN.Full)
                val cropFrame = viewport.toViewport(state.clip.effects.geometry.crop)
                val currentViewport by rememberUpdatedState(viewport)
                val currentFrame by rememberUpdatedState(cropFrame)
                val visibleMasks = if (state.activeTool == VideoTool.PRIVACY) {
                    state.displayed.timeline.privacyMasks.filter { positionUs in it.range || it.mask.id == state.selectedMaskId }
                } else {
                    emptyList()
                }
                val currentMasks by rememberUpdatedState(visibleMasks)
                val currentSelectedMask by rememberUpdatedState(state.selectedMaskId)
                // 텍스트·스티커 도구에서는 플레이어 대신 화면이 오버레이를 그려 손가락을 바로 따라가게 한다.
                val overlayMode = state.activeTool == VideoTool.TEXT || state.activeTool == VideoTool.STICKER
                val visibleOverlays = if (overlayMode) {
                    state.displayed.timeline.overlays
                        .filter { positionUs in it.range || it.overlay.id == state.selectedOverlayId || it.overlay.id == state.editingTextId }
                        .map { it.overlay }
                } else {
                    emptyList()
                }
                val outputSize = PixelSize(size.first, size.second)
                val outputToViewport = viewport.contentToViewport * Affine2D.scale(1.0 / size.first, 1.0 / size.second)
                val overlayRenderer = remember { OverlayRenderer() }
                val selectedOverlay = visibleOverlays.firstOrNull { it.id == state.selectedOverlayId }
                val selectionCorners = selectedOverlay?.let { overlay -> overlayRenderer.corners(overlay, outputSize).map { outputToViewport.map(it) } }
                val currentOverlays by rememberUpdatedState(visibleOverlays)
                val currentOutputToViewport by rememberUpdatedState(outputToViewport)
                val currentOutputSize by rememberUpdatedState(outputSize)
                val currentSelectedOverlay by rememberUpdatedState(state.selectedOverlayId)
                val zoom = zoomState.zoom
                // 확대된 레이어 안 좌표라 손가락 기준 반경을 배율만큼 줄여 화면에서 같은 크기로 잡히게 한다.
                val touchRadius = with(density) { 24.dp.toPx().toDouble() } / zoom
                val currentTouchRadius by rememberUpdatedState(touchRadius)
                // 자르기·회전 중에는 손을 뗄 때마다 남은 영역이 화면을 채우도록 부드럽게 확대해 맞춘다.
                val areaSize = Size(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat())
                val geometry = state.clip.effects.geometry
                LaunchedEffect(toolMode, geometry.crop, geometry.quarterTurns, state.draggingCrop, areaSize) {
                    if (!toolMode || state.draggingCrop) return@LaunchedEffect
                    val target = Rect(cropFrame.left.toFloat(), cropFrame.top.toFloat(), cropFrame.right.toFloat(), cropFrame.bottom.toFloat())
                    val (fitZoom, fitPan) = zoomState.fitTarget(target, areaSize, padding.toFloat())
                    zoomState.animateTo(fitZoom, fitPan)
                }

                // TextureView는 일반 View라 확대 레이어와 잘라내기가 그대로 적용된다(SurfaceView는 키우면 화면이 깨진다).
                val currentAttach by rememberUpdatedState(onAttachSurface)
                val currentDetach by rememberUpdatedState(onDetachSurface)
                AndroidView(
                    factory = { context -> TextureView(context).apply { surfaceTextureListener = PreviewSurfaceListener({ s, w, h -> currentAttach(s, w, h) }, { currentDetach(it) }) } },
                    onRelease = { view -> (view.surfaceTextureListener as? PreviewSurfaceListener)?.release() },
                    modifier = Modifier
                        .offset { IntOffset(content.left.roundToInt(), content.top.roundToInt()) }
                        .size(with(density) { content.width.toFloat().toDp() }, with(density) { content.height.toFloat().toDp() }),
                )

                val cropDescription = stringResource(UiR.string.framekit_crop_area)
                val timelineDescription = stringResource(R.string.framekit_play)
                val gestures = when (state.activeTool) {
                    VideoTool.CROP -> Modifier
                        .excludeCropHandleGestures(cropFrame, touchRadius.toFloat())
                        .semantics { contentDescription = cropDescription }
                        .pointerInput(state.activeTool) {
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
                    VideoTool.PRIVACY -> Modifier.pointerInput(state.activeTool) {
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            val start = currentViewport.toContent(down.position.x.toDouble(), down.position.y.toDouble()) ?: return@awaitEachGesture
                            down.consume()
                            val fitted = currentViewport.fittedSize
                            // 선택된 마스크의 오른쪽 아래 모서리를 잡으면 크기를, 안쪽을 잡으면 위치를 바꾼다.
                            val selected = currentMasks.firstOrNull { it.mask.id == currentSelectedMask }
                            val corner = selected?.bounds()?.let { r -> currentViewport.toViewport(r) }
                            val onCorner = corner != null &&
                                abs(down.position.x - corner.right) <= currentTouchRadius && abs(down.position.y - corner.bottom) <= currentTouchRadius
                            val hit = if (onCorner) selected else currentMasks.lastOrNull { it.bounds()?.let { r -> start.x in r.left..r.right && start.y in r.top..r.bottom } == true }
                            if (hit != null) {
                                actions.beginMaskEdit(hit.mask.id, resize = onCorner)
                                do {
                                    val event = awaitPointerEvent()
                                    // 두 번째 손가락이 닿으면 바깥 틀이 확대·이동을 맡는다.
                                    if (event.changes.count { it.pressed } >= 2) break
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                    val delta = change.position - down.position
                                    actions.dragMaskEdit(delta.x / fitted.width, delta.y / fitted.height)
                                    change.consume()
                                } while (change.pressed)
                                actions.finishMaskEdit()
                                return@awaitEachGesture
                            }
                            actions.beginMask(start.x, start.y)
                            do {
                                val event = awaitPointerEvent()
                                if (event.changes.count { it.pressed } >= 2) {
                                    // 확대하려던 것이므로 그리던 마스크는 남기지 않는다.
                                    actions.cancelMask()
                                    return@awaitEachGesture
                                }
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                val point = currentViewport.toContentUnbounded(change.position.x.toDouble(), change.position.y.toDouble())
                                actions.extendMask(point.x.coerceIn(0.0, 1.0), point.y.coerceIn(0.0, 1.0))
                                change.consume()
                            } while (change.pressed)
                            actions.finishMask()
                        }
                    }
                    VideoTool.TEXT, VideoTool.STICKER -> Modifier.pointerInput(state.activeTool) {
                        val slopPx = 12.dp.toPx().toDouble()
                        val snap = 6.dp.toPx().toDouble()
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val output = currentOutputToViewport.inverted().map(down.position.x.toDouble(), down.position.y.toDouble())
                            val hit = overlayRenderer.hitTest(currentOverlays, output.x, output.y, currentOutputSize, slopPx = slopPx)
                            if (hit == null) {
                                actions.selectOverlay(null)
                                return@awaitEachGesture
                            }
                            val wasSelected = hit == currentSelectedOverlay
                            overlayGesture[0] = true
                            actions.beginOverlayGesture(hit)
                            val fitted = currentViewport.fittedSize
                            var pan = Offset.Zero
                            var zoom = 1f
                            var rotation = 0f
                            var moved = false
                            do {
                                val event = awaitPointerEvent()
                                pan += event.calculatePan()
                                zoom *= event.calculateZoom()
                                rotation += event.calculateRotation()
                                if (!moved && (pan.getDistance() > viewConfiguration.touchSlop || abs(zoom - 1f) > 0.02f || abs(rotation) > 2f)) moved = true
                                if (moved) {
                                    actions.updateOverlayGesture(pan.x / fitted.width, pan.y / fitted.height, zoom.toDouble(), rotation.toDouble(), snap / fitted.width, snap / fitted.height)
                                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                                }
                            } while (event.changes.any { it.pressed })
                            actions.finishOverlayGesture()
                            overlayGesture[0] = false
                            val overlay = currentOverlays.firstOrNull { it.id == hit }
                            if (!moved && wasSelected && overlay is ImageOverlay.Text) actions.editText(hit)
                        }
                    }
                    null -> Modifier
                        .semantics { contentDescription = timelineDescription }
                        .pointerInput(state.activeTool) { detectTapGestures(onTap = { onTap() }) }
                    else -> Modifier
                }

                Canvas(Modifier.fillMaxSize().then(gestures)) {
                    if (toolMode) {
                        drawCropFrame(
                            frame = cropFrame,
                            accent = colors.accent,
                            showGrid = state.draggingCrop || state.activeTool == VideoTool.ROTATE,
                            showHandles = state.activeTool == VideoTool.CROP,
                            zoom = zoom,
                        )
                    }
                    visibleMasks.forEach { drawMask(it, viewport, selected = it.mask.id == state.selectedMaskId, accent = colors.accent, zoom = zoom) }
                    if (visibleOverlays.isNotEmpty()) {
                        drawIntoCanvas { canvas ->
                            canvas.nativeCanvas.withMatrix(outputToViewport.toAndroidMatrix()) {
                                overlayRenderer.draw(this, outputSize, visibleOverlays, emptyList())
                            }
                        }
                    }
                    selectionCorners?.let { corners ->
                        val path = Path().apply {
                            moveTo(corners[0].x.toFloat(), corners[0].y.toFloat())
                            corners.drop(1).forEach { lineTo(it.x.toFloat(), it.y.toFloat()) }
                            close()
                        }
                        drawPath(path, Color.Black.copy(alpha = 0.4f), style = Stroke(3.dp.toPx() / zoom))
                        drawPath(path, colors.accent, style = Stroke(1.5.dp.toPx() / zoom))
                    }
                }
            }
        }
        // 자르기·회전에서는 자동 맞춤이 배율을 정하고, 버튼이 왼쪽 아래 핸들을 가리므로 숨긴다.
        if (!toolMode) ZoomResetButton(zoomState, Modifier.align(Alignment.BottomStart))
    }
}

/**
 * TextureView의 surface가 준비되거나 크기가 바뀌면 미리보기에 연결하고, 사라지면 뗀다.
 * surface 객체는 여기서 만들고 해제한다.
 */
private class PreviewSurfaceListener(
    private val onAttach: (Surface, Int, Int) -> Unit,
    private val onDetach: (Surface) -> Unit,
) : TextureView.SurfaceTextureListener {
    private var surface: Surface? = null

    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
        val created = Surface(texture)
        surface = created
        onAttach(created, width, height)
    }

    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
        surface?.let { onAttach(it, width, height) }
    }

    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
        release()
        return true
    }

    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit

    fun release() {
        val current = surface ?: return
        surface = null
        onDetach(current)
        current.release()
    }
}

private fun TimedPrivacyMask.bounds(): RectN? = when (val shape = mask.shape) {
    is MaskShape.Rectangle -> shape.rect
    is MaskShape.Ellipse -> shape.rect
    is MaskShape.Brush -> null
}

private fun DrawScope.drawMask(timed: TimedPrivacyMask, viewport: ViewportTransform, selected: Boolean, accent: Color, zoom: Float) {
    val unit = 1f / zoom.coerceAtLeast(1f)
    val rect = viewport.toViewport(timed.bounds() ?: return)
    val topLeft = Offset(rect.left.toFloat(), rect.top.toFloat())
    val size = Size(rect.width.toFloat(), rect.height.toFloat())
    val color = if (selected) accent else Color.White.copy(alpha = 0.8f)
    val stroke = Stroke((if (selected) 2.dp else 1.dp).toPx() * unit)
    if (timed.mask.shape is MaskShape.Ellipse) drawOval(color, topLeft, size, style = stroke) else drawRect(color, topLeft, size, style = stroke)
    if (selected) {
        // 크기 조절 손잡이. 모양과 상관없이 감싸는 사각형의 오른쪽 아래에 둔다.
        val handle = Offset(rect.right.toFloat(), rect.bottom.toFloat())
        drawCircle(Color.Black.copy(alpha = 0.4f), 9.dp.toPx() * unit, handle)
        drawCircle(accent, 7.dp.toPx() * unit, handle)
    }
}
