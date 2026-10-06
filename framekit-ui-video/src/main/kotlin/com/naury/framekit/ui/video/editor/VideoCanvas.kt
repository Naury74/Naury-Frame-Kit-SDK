package com.naury.framekit.ui.video.editor

import android.view.SurfaceView
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
 * SurfaceView 위의 영상 미리보기. 그 위에 자르기 프레임과 가리기 마스크를 그린다.
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
    onAttachSurface: (SurfaceView) -> Unit,
    onDetachSurface: (SurfaceView) -> Unit,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = FrameKitTheme.colors
    val density = LocalDensity.current
    val toolMode = state.activeTool?.isGeometry == true
    BoxWithConstraints(modifier.fillMaxSize().background(colors.canvasBackground)) {
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
        val touchRadius = with(density) { 24.dp.toPx().toDouble() }

        AndroidView(
            factory = { context -> SurfaceView(context).also(onAttachSurface) },
            onRelease = onDetachSurface,
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
            VideoTool.PRIVACY -> Modifier.pointerInput(state.activeTool) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val start = currentViewport.toContent(down.position.x.toDouble(), down.position.y.toDouble()) ?: return@awaitEachGesture
                    down.consume()
                    val hit = currentMasks.lastOrNull { it.bounds()?.let { r -> start.x in r.left..r.right && start.y in r.top..r.bottom } == true }
                    if (hit != null) {
                        actions.selectMask(hit.mask.id)
                        return@awaitEachGesture
                    }
                    actions.beginMask(start.x, start.y)
                    do {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        val point = currentViewport.toContentUnbounded(change.position.x.toDouble(), change.position.y.toDouble())
                        actions.extendMask(point.x.coerceIn(0.0, 1.0), point.y.coerceIn(0.0, 1.0))
                        change.consume()
                    } while (change.pressed)
                    actions.finishMask()
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
                )
            }
            visibleMasks.forEach { drawMask(it, viewport, selected = it.mask.id == state.selectedMaskId, accent = colors.accent) }
        }
    }
}

private fun TimedPrivacyMask.bounds(): RectN? = when (val shape = mask.shape) {
    is MaskShape.Rectangle -> shape.rect
    is MaskShape.Ellipse -> shape.rect
    is MaskShape.Brush -> null
}

private fun DrawScope.drawMask(timed: TimedPrivacyMask, viewport: ViewportTransform, selected: Boolean, accent: Color) {
    val rect = viewport.toViewport(timed.bounds() ?: return)
    val topLeft = Offset(rect.left.toFloat(), rect.top.toFloat())
    val size = Size(rect.width.toFloat(), rect.height.toFloat())
    val color = if (selected) accent else Color.White.copy(alpha = 0.8f)
    val stroke = Stroke((if (selected) 2.dp else 1.dp).toPx())
    if (timed.mask.shape is MaskShape.Ellipse) drawOval(color, topLeft, size, style = stroke) else drawRect(color, topLeft, size, style = stroke)
}
