package com.naury.framekit.ui.motion.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.naury.framekit.core.video.AudioRole
import com.naury.framekit.motion.model.MotionAudio
import com.naury.framekit.video.audio.Waveform
import kotlin.math.abs

/**
 * 움직이는 이미지의 배경 음악·음성 줄. 결과 전체 길이를 화면 폭에 맞춰 보여 준다(움직이는 이미지는 짧다).
 *
 * 조각을 눌러 고르고, 고른 조각은 끌어서 옮기거나 양 끝을 끌어 자른다. 빈 줄을 누르면 그 줄에 소리를 더한다.
 */
@Composable
internal fun MotionAudioLanes(
    audio: List<MotionAudio>,
    names: Map<String, String?>,
    waveforms: Map<String, Waveform>,
    selectedId: String?,
    durationMs: Long,
    positionMs: Long,
    labels: Map<AudioRole, String>,
    addLabels: Map<AudioRole, String>,
    onSelect: (String) -> Unit,
    onAdd: (AudioRole) -> Unit,
    onBeginDrag: (String, MotionAudioDrag) -> Boolean,
    onDrag: (Long) -> Unit,
    onEndDrag: () -> Unit,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    val current by rememberUpdatedState(Lanes(audio, selectedId, durationMs.coerceAtLeast(1)))
    val actions by rememberUpdatedState(LaneActions(onSelect, onAdd, onBeginDrag, onDrag, onEndDrag, onSeek))
    Canvas(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .height(LANE_HEIGHT * 2 + LANE_GAP)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val s = current
                    val a = actions
                    val laneH = LANE_HEIGHT.toPx()
                    val gap = LANE_GAP.toPx()
                    val role = if (down.position.y < laneH + gap / 2) AudioRole.MUSIC else AudioRole.VOICE
                    fun x(ms: Long) = ms.toFloat() / s.durationMs * size.width
                    fun ms(px: Float) = (px / size.width * s.durationMs).toLong()
                    val selected = s.audio.firstOrNull { it.id == s.selectedId && it.role == role }
                    val handle = HANDLE.toPx()
                    val target: Pair<String, MotionAudioDrag>? = selected?.let { piece ->
                        val range = piece.outputRangeMs(s.durationMs)
                        val left = x(range.first)
                        val right = x(range.last)
                        val edge = handle.coerceAtMost((right - left) / 3)
                        when {
                            abs(down.position.x - left) <= edge -> piece.id to MotionAudioDrag.START
                            abs(down.position.x - right) <= edge -> piece.id to MotionAudioDrag.END
                            down.position.x in left..right -> piece.id to MotionAudioDrag.MOVE
                            else -> null
                        }
                    }
                    var dragging = false
                    var moved = false
                    var dx = 0f
                    do {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        dx += change.position.x - change.previousPosition.x
                        if (!moved && abs(dx) > viewConfiguration.touchSlop) {
                            moved = true
                            dragging = target != null && a.onBeginDrag(target.first, target.second)
                        }
                        if (moved) {
                            if (dragging) a.onDrag(ms(dx)) else a.onSeek(ms(change.position.x).coerceIn(0, s.durationMs))
                            change.consume()
                        }
                    } while (event.changes.any { it.pressed })
                    if (dragging) a.onEndDrag()
                    if (!moved) {
                        val time = ms(down.position.x)
                        val hit = s.audio.lastOrNull { it.role == role && time in it.outputRangeMs(s.durationMs) }
                        if (hit != null) a.onSelect(hit.id) else a.onAdd(role)
                    }
                }
            },
    ) {
        val laneH = LANE_HEIGHT.toPx()
        val gap = LANE_GAP.toPx()
        val total = durationMs.coerceAtLeast(1)
        fun x(ms: Long) = ms.toFloat() / total * size.width
        listOf(AudioRole.MUSIC, AudioRole.VOICE).forEachIndexed { index, role ->
            val top = index * (laneH + gap)
            val color = if (role == AudioRole.MUSIC) Color(0xFF2EB67D) else Color(0xFFF59F00)
            drawRoundRect(color.copy(alpha = 0.12f), Offset(0f, top), Size(size.width, laneH), CornerRadius(laneH / 3))
            val pieces = audio.filter { it.role == role }
            if (pieces.isEmpty()) {
                drawLabel(measurer, "+ ${addLabels[role].orEmpty()}", 8.dp.toPx(), top, laneH, color, size.width)
            } else {
                drawLabel(measurer, labels[role].orEmpty(), 6.dp.toPx(), top, laneH, Color.White.copy(alpha = 0.5f), size.width / 4)
            }
            pieces.forEach { piece ->
                val range = piece.outputRangeMs(total)
                val left = x(range.first)
                val right = x(range.last)
                if (right <= left) return@forEach
                drawRoundRect(color.copy(alpha = 0.85f), Offset(left, top), Size(right - left, laneH), CornerRadius(laneH / 3))
                clipRect(left, top, right, top + laneH) {
                    waveforms[piece.sourceId]?.let { wave ->
                        var px = left
                        val clip = piece.toAudioClip()
                        val length = clip.sourceRange.durationUs.coerceAtLeast(1)
                        while (px < right) {
                            val elapsed = (px / size.width * total).toLong() * 1000 - clip.timelineStartUs
                            val first = length - clip.startOffsetUs
                            val source = if (elapsed < first) clip.sourceRange.startUs + clip.startOffsetUs + elapsed else clip.sourceRange.startUs + (elapsed - first) % length
                            val half = laneH * 0.42f * wave.peakAt(source)
                            drawLine(Color.Black.copy(alpha = 0.28f), Offset(px, top + laneH / 2 - half), Offset(px, top + laneH / 2 + half), 1.5.dp.toPx())
                            px += 3.dp.toPx()
                        }
                    }
                    if (piece.fadeInMs > 0) drawFade(left, top, piece.fadeInMs.toFloat() / total * size.width, laneH, true)
                    if (piece.fadeOutMs > 0) drawFade(right, top, piece.fadeOutMs.toFloat() / total * size.width, laneH, false)
                    val name = (if (piece.loop) "↻ " else "") + (names[piece.sourceId] ?: labels[role].orEmpty())
                    drawLabel(measurer, name, left + 6.dp.toPx(), top, laneH, Color.White, right - left - 8.dp.toPx())
                }
                if (piece.id == selectedId) {
                    drawRoundRect(Color.White, Offset(left, top), Size(right - left, laneH), CornerRadius(laneH / 3), style = Stroke(2.dp.toPx()))
                    val w = 7.dp.toPx()
                    listOf(left - w / 2, right - w / 2).forEach { hx ->
                        drawRoundRect(Color.White, Offset(hx, top - 1.dp.toPx()), Size(w, laneH + 2.dp.toPx()), CornerRadius(w / 2))
                    }
                }
            }
        }
        val playhead = x(positionMs)
        drawLine(Color.White, Offset(playhead, -2.dp.toPx()), Offset(playhead, size.height + 2.dp.toPx()), 2.dp.toPx())
    }
}

/** 결과에서 들리는 구간(밀리초, 끝 포함 안 함을 [LongRange.last]로 나타냄). */
private fun MotionAudio.outputRangeMs(durationMs: Long): LongRange {
    val range = toAudioClip().outputRange(durationMs * 1000)
    return (range.startUs / 1000)..(range.endExclusiveUs / 1000)
}

private data class Lanes(val audio: List<MotionAudio>, val selectedId: String?, val durationMs: Long)

private class LaneActions(
    val onSelect: (String) -> Unit,
    val onAdd: (AudioRole) -> Unit,
    val onBeginDrag: (String, MotionAudioDrag) -> Boolean,
    val onDrag: (Long) -> Unit,
    val onEndDrag: () -> Unit,
    val onSeek: (Long) -> Unit,
)

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawFade(edgeX: Float, top: Float, width: Float, height: Float, fromLeft: Boolean) {
    val path = Path().apply {
        moveTo(edgeX, top)
        lineTo(if (fromLeft) edgeX + width else edgeX - width, top)
        lineTo(edgeX, top + height)
        close()
    }
    drawPath(path, Color.Black.copy(alpha = 0.3f))
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawLabel(
    measurer: androidx.compose.ui.text.TextMeasurer,
    text: String,
    left: Float,
    top: Float,
    height: Float,
    color: Color,
    maxWidth: Float,
) {
    if (maxWidth < 12.dp.toPx() || text.isBlank()) return
    val layout = measurer.measure(text, TextStyle(color = color, fontSize = 11.sp), overflow = TextOverflow.Ellipsis, maxLines = 1, constraints = Constraints(maxWidth = maxWidth.toInt().coerceAtLeast(1)))
    drawText(layout, topLeft = Offset(left, top + (height - layout.size.height) / 2))
}

private val LANE_HEIGHT = 26.dp
private val LANE_GAP = 6.dp
private val HANDLE = 18.dp
