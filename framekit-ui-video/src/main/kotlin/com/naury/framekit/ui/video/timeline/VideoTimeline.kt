package com.naury.framekit.ui.video.timeline

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.naury.framekit.ui.design.FrameKitTheme
import com.naury.framekit.ui.video.editor.TrimEdge
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Trim handles of the timeline as fractions of its width.
 *
 * @property onDrag receives the handle position as a fraction of the timeline width.
 */
internal class TrimSelection(
    val start: Float,
    val end: Float,
    val onBegin: (TrimEdge) -> Unit,
    val onDrag: (Float) -> Unit,
    val onEnd: () -> Unit,
)

/**
 * Thumbnail strip with a playhead. Tapping or dragging scrubs; with [trim] the handles at both ends
 * of the kept part can be dragged and the rest is dimmed.
 *
 * @param frameKey changes whenever [frameTimeAt] maps fractions to different source times.
 * @param frameTimeAt source time shown at a fraction of the width.
 * @param frameAspect width / height of a frame.
 */
@Composable
internal fun VideoTimeline(
    frameKey: Any,
    frameTimeAt: (Double) -> Long,
    frameAspect: Float,
    loadFrame: suspend (Long, Int) -> Bitmap?,
    playhead: Float,
    onScrub: (Float) -> Unit,
    onScrubEnd: () -> Unit,
    description: String,
    modifier: Modifier = Modifier,
    trim: TrimSelection? = null,
) {
    val colors = FrameKitTheme.colors
    val density = LocalDensity.current
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .height(TIMELINE_HEIGHT)
            .semantics { contentDescription = description },
    ) {
        val insetPx = with(density) { TRACK_INSET.toPx() }
        val widthPx = (constraints.maxWidth - 2 * insetPx).coerceAtLeast(1f)
        val heightPx = with(density) { TIMELINE_HEIGHT.roundToPx() }.coerceAtMost(MAX_FRAME_PX)
        val tileWidth = (heightPx * frameAspect).coerceAtLeast(1f)
        val tiles = ceil(widthPx / tileWidth).toInt().coerceIn(1, MAX_TILES)
        val currentTimeAt by rememberUpdatedState(frameTimeAt)
        val frames by produceState(emptyMap<Int, Bitmap>(), frameKey, tiles, heightPx) {
            value = emptyMap()
            for (index in 0 until tiles) {
                val bitmap = loadFrame(currentTimeAt((index + 0.5) / tiles), heightPx) ?: continue
                value = value + (index to bitmap)
            }
        }
        val currentTrim by rememberUpdatedState(trim)
        val currentScrub by rememberUpdatedState(onScrub)
        val currentScrubEnd by rememberUpdatedState(onScrubEnd)
        val handleRadius = with(density) { 24.dp.toPx() }
        val handleWidth = with(density) { 12.dp.toPx() }

        // 손잡이는 트랙 양 끝 바깥에 그려지므로 터치는 여백까지 포함한 전체 폭에서 받고 트랙 좌표로 바꾼다.
        Box(
            Modifier
                .matchParentSize()
                // 화면 가장자리의 손잡이를 끌 때 시스템 뒤로 가기 제스처가 먼저 반응하지 않게 한다.
                .systemGestureExclusion()
                .pointerInput(trim != null) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        val width = (size.width - 2 * insetPx).coerceAtLeast(1f)
                        fun track(x: Float) = x - insetPx
                        val selection = currentTrim
                        val edge = selection?.let {
                            val startX = it.start * width
                            val endX = it.end * width
                            when {
                                abs(track(down.position.x) - startX) <= handleRadius &&
                                    abs(track(down.position.x) - startX) <= abs(track(down.position.x) - endX) -> TrimEdge.START
                                abs(track(down.position.x) - endX) <= handleRadius -> TrimEdge.END
                                else -> null
                            }
                        }
                        down.consume()
                        if (edge != null && selection != null) selection.onBegin(edge) else currentScrub((track(down.position.x) / width).coerceIn(0f, 1f))
                        do {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            val fraction = (track(change.position.x) / width).coerceIn(0f, 1f)
                            if (edge != null && selection != null) selection.onDrag(fraction) else currentScrub(fraction)
                            change.consume()
                        } while (change.pressed)
                        if (edge != null && selection != null) selection.onEnd() else currentScrubEnd()
                    }
                },
        )
        Canvas(Modifier.matchParentSize().padding(horizontal = TRACK_INSET)) {
            val radius = CornerRadius(6.dp.toPx())
            drawRoundRect(colors.surface, cornerRadius = radius)
            for (index in 0 until tiles) {
                val bitmap = frames[index] ?: continue
                if (bitmap.isRecycled) continue
                val left = (index * size.width / tiles).roundToInt()
                val right = ((index + 1) * size.width / tiles).roundToInt()
                drawImage(
                    bitmap.asImageBitmap(),
                    dstOffset = IntOffset(left, 0),
                    dstSize = IntSize(right - left, size.height.roundToInt()),
                )
            }
            if (trim != null) {
                val startX = trim.start * size.width
                val endX = trim.end * size.width
                val dim = Color.Black.copy(alpha = 0.6f)
                drawRect(dim, Offset.Zero, Size(startX, size.height))
                drawRect(dim, Offset(endX, 0f), Size(size.width - endX, size.height))
                val line = 3.dp.toPx()
                drawRect(colors.accent, Offset(startX, 0f), Size(endX - startX, size.height), style = Stroke(line))
                listOf(startX, endX).forEachIndexed { index, x ->
                    val left = if (index == 0) x - handleWidth else x
                    drawRoundRect(colors.accent, Offset(left, 0f), Size(handleWidth, size.height), radius)
                    drawLine(colors.onAccent, Offset(left + handleWidth / 2, size.height * 0.35f), Offset(left + handleWidth / 2, size.height * 0.65f), 2.dp.toPx())
                }
            }
            val x = playhead.coerceIn(0f, 1f) * size.width
            drawLine(Color.Black.copy(alpha = 0.5f), Offset(x, -4.dp.toPx()), Offset(x, size.height + 4.dp.toPx()), 4.dp.toPx())
            drawLine(Color.White, Offset(x, -4.dp.toPx()), Offset(x, size.height + 4.dp.toPx()), 2.dp.toPx())
        }
    }
}

/** `m:ss.d`, or `h:mm:ss` from one hour. */
internal fun formatTime(us: Long): String {
    val tenths = (us.coerceAtLeast(0) + 50_000) / 100_000
    val totalSeconds = tenths / 10
    val hours = totalSeconds / 3600
    val minutes = totalSeconds / 60 % 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.ROOT, "%d:%02d.%d", minutes, seconds, tenths % 10)
    }
}

private val TIMELINE_HEIGHT = 56.dp
private val TRACK_INSET = 20.dp
private const val MAX_TILES = 24
private const val MAX_FRAME_PX = 160
