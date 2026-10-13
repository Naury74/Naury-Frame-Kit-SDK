package com.naury.framekit.ui.audioeditor.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.naury.framekit.core.audio.AudioProject
import com.naury.framekit.core.video.AudioClip
import com.naury.framekit.core.video.AudioRole
import com.naury.framekit.core.video.TimeRangeUs
import com.naury.framekit.ui.design.FrameKitTheme
import com.naury.framekit.video.audio.Waveform
import kotlin.math.abs

/** 타임라인에서 고친 것을 알리는 콜백 묶음. */
internal class AudioTimelineActions(
    val onScrub: (Long) -> Unit,
    val onSelect: (String?) -> Unit,
    val onEmptyLayer: () -> Unit,
    val onBeginDrag: (String, DragKind) -> Boolean,
    val onDrag: (Long) -> Unit,
    val onEndDrag: () -> Unit,
    val onRangeEdge: (Boolean, Long) -> Unit,
)

/**
 * 음성 편집 타임라인. 가운데 재생 헤드는 고정되고 내용이 움직인다. 위는 본 줄(이어지는 음성), 아래는 겹친 줄이다.
 *
 * - 한 손가락으로 끌면 재생 위치를 옮기고, 두 손가락으로 확대·축소한다.
 * - 조각을 눌러 고르면 양 끝 손잡이가 생기고 끌어서 자른다. 겹친 줄 조각은 가운데를 끌어 옮긴다.
 * - 구간 편집 중에는 구간의 손잡이를 끈다.
 *
 * @param tall 세로 공간이 넉넉할 때 줄을 더 높게 그려 파형을 크게 보이고 잡기 쉽게 한다.
 */
@Composable
internal fun AudioTimeline(
    project: AudioProject,
    names: Map<String, String?>,
    waveforms: Map<String, Waveform>,
    positionUs: Long,
    selectedId: String?,
    range: TimeRangeUs?,
    labels: Pair<String, String>,
    emptyLayerLabel: String,
    actions: AudioTimelineActions,
    description: String,
    modifier: Modifier = Modifier,
    tall: Boolean = false,
) {
    val mainHeight = if (tall) MAIN_H_TALL else MAIN_H
    val layerHeight = if (tall) LAYER_H_TALL else LAYER_H
    val colors = FrameKitTheme.colors
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val duration = project.durationUs
    val current by rememberUpdatedState(Snap(project, positionUs, selectedId, range, actions))
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(mainHeight + layerHeight + GAP * 3)
            .semantics {
                contentDescription = description
                progressBarRangeInfo = ProgressBarRangeInfo(positionUs.toFloat(), 0f..duration.coerceAtLeast(1).toFloat(), steps = 0)
                setProgress { target ->
                    current.actions.onScrub(target.toLong().coerceIn(0, duration))
                    true
                }
            },
    ) {
        val widthPx = constraints.maxWidth.toFloat()
        var dpPerSecond by remember(duration > 0) {
            val fit = with(density) { (widthPx * 0.8f).toDp().value } / (duration / 1_000_000f).coerceAtLeast(0.5f)
            mutableFloatStateOf(fit.coerceIn(MIN_DPS, MAX_DPS))
        }
        val pxPerUs = with(density) { dpPerSecond.dp.toPx() } / 1_000_000f
        val currentPx by rememberUpdatedState(pxPerUs)
        val center = widthPx / 2
        val mainTop = with(density) { GAP.toPx() }
        val mainH = with(density) { mainHeight.toPx() }
        val layerTop = mainTop + mainH + with(density) { GAP.toPx() }
        val layerH = with(density) { layerHeight.toPx() }
        val handle = with(density) { 20.dp.toPx() }

        Canvas(
            Modifier
                .matchParentSize()
                .systemGestureExclusion()
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        val s = current
                        val px = currentPx
                        fun x(t: Long) = center + (t - s.positionUs) * px
                        fun t(xp: Float) = (s.positionUs + (xp - center) / px).toLong()
                        val inMain = down.position.y < layerTop - with(density) { GAP.toPx() } / 2
                        val role = if (inMain) AudioRole.VOICE else AudioRole.MUSIC
                        val total = s.project.durationUs
                        // 무엇을 잡았는지: 구간 손잡이 > 고른 조각의 손잡이·몸통 > 재생 위치 옮기기.
                        var target: Pair<String, DragKind>? = null
                        var rangeEdge: Boolean? = null
                        s.range?.let { r ->
                            if (abs(down.position.x - x(r.startUs)) <= handle) rangeEdge = true
                            else if (abs(down.position.x - x(r.endExclusiveUs)) <= handle) rangeEdge = false
                        }
                        if (rangeEdge == null) {
                            s.project.clips.firstOrNull { it.id == s.selectedId && it.role.lane() == role }?.let { clip ->
                                val r = clip.outputRange(total)
                                val left = x(r.startUs)
                                val right = x(r.endExclusiveUs)
                                val edge = handle.coerceAtMost((right - left) / 3)
                                target = when {
                                    abs(down.position.x - left) <= edge -> clip.id to DragKind.START
                                    abs(down.position.x - right) <= edge -> clip.id to DragKind.END
                                    clip.role != AudioRole.VOICE && down.position.x in left..right -> clip.id to DragKind.MOVE
                                    else -> null
                                }
                            }
                        }
                        val startPosition = s.positionUs
                        var moved = false
                        var mode = 0 // 1: 재생 위치, 2: 조각, 3: 구간 손잡이
                        var dx = 0f
                        do {
                            val event = awaitPointerEvent()
                            val pressed = event.changes.filter { it.pressed }
                            if (pressed.size >= 2) {
                                val zoom = event.calculateZoom()
                                if (zoom != 1f) {
                                    dpPerSecond = (dpPerSecond * zoom).coerceIn(MIN_DPS, MAX_DPS)
                                    moved = true
                                }
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                                continue
                            }
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            dx += change.position.x - change.previousPosition.x
                            if (!moved && abs(dx) > viewConfiguration.touchSlop) {
                                moved = true
                                mode = when {
                                    rangeEdge != null -> 3
                                    target != null && s.actions.onBeginDrag(target!!.first, target!!.second) -> 2
                                    else -> 1
                                }
                            }
                            if (!moved) continue
                            val deltaUs = (dx / currentPx).toLong()
                            when (mode) {
                                1 -> current.actions.onScrub((startPosition - deltaUs).coerceIn(0, current.project.durationUs))
                                2 -> current.actions.onDrag(deltaUs)
                                3 -> {
                                    val r = s.range!!
                                    current.actions.onRangeEdge(rangeEdge!!, (if (rangeEdge == true) r.startUs else r.endExclusiveUs) + deltaUs)
                                }
                            }
                            change.consume()
                        } while (event.changes.any { it.pressed })
                        if (mode == 2) current.actions.onEndDrag()
                        if (!moved) {
                            val time = t(down.position.x)
                            val hit = s.project.clips.lastOrNull { it.role.lane() == role && time in it.outputRange(total) }
                            when {
                                hit != null -> s.actions.onSelect(if (hit.id == s.selectedId) null else hit.id)
                                role == AudioRole.MUSIC && s.project.layerClips.isEmpty() -> s.actions.onEmptyLayer()
                                else -> s.actions.onSelect(null)
                            }
                        }
                    }
                },
        ) {
            fun x(t: Long) = center + (t - positionUs) * pxPerUs
            // 줄 바탕
            drawRoundRect(colors.surface, Offset(0f, mainTop), Size(size.width, mainH), CornerRadius(12.dp.toPx()))
            drawRoundRect(colors.surface.copy(alpha = 0.6f), Offset(0f, layerTop), Size(size.width, layerH), CornerRadius(10.dp.toPx()))
            if (project.layerClips.isEmpty()) {
                drawLabel(measurer, "+ $emptyLayerLabel", center + 10.dp.toPx(), layerTop, layerH, colors.foregroundMuted, size.width / 2)
            }
            project.clips.forEach { clip ->
                val main = clip.role == AudioRole.VOICE
                val top = if (main) mainTop else layerTop
                val h = if (main) mainH else layerH
                val r = clip.outputRange(duration)
                val left = x(r.startUs) + 1.dp.toPx()
                val right = x(r.endExclusiveUs) - 1.dp.toPx()
                if (right < 0 || left > size.width || right <= left) return@forEach
                val color = if (main) colors.accent else LAYER
                val selected = clip.id == selectedId
                drawRoundRect(color.copy(alpha = if (selected) 0.95f else 0.75f), Offset(left, top), Size(right - left, h), CornerRadius(10.dp.toPx()))
                clipRect(left, top, right, top + h) {
                    waveforms[clip.source.value]?.let { wave -> drawWave(wave, clip, left, right, top, h, center, positionUs, pxPerUs) }
                    if (clip.fadeInUs > 0) drawWedge(left, top, clip.fadeInUs * pxPerUs, h, true)
                    if (clip.fadeOutUs > 0) drawWedge(right, top, clip.fadeOutUs * pxPerUs, h, false)
                    val name = (if (clip.loop) "↻ " else "") + (names[clip.source.value] ?: if (main) labels.first else labels.second)
                    drawLabel(measurer, name, left.coerceAtLeast(0f) + 8.dp.toPx(), top + 4.dp.toPx(), 14.dp.toPx(), Color.White, right - left.coerceAtLeast(0f) - 12.dp.toPx())
                }
                if (selected) {
                    drawRoundRect(colors.foreground, Offset(left, top), Size(right - left, h), CornerRadius(10.dp.toPx()), style = Stroke(2.dp.toPx()))
                    drawHandles(left, right, top, h, colors.foreground)
                }
            }
            range?.let { r ->
                val l = x(r.startUs)
                val rr = x(r.endExclusiveUs)
                drawRect(colors.error.copy(alpha = 0.22f), Offset(l, 0f), Size(rr - l, size.height))
                drawHandles(l, rr, mainTop, layerTop + layerH - mainTop, colors.error)
            }
            val start = x(0)
            val end = x(duration)
            // 처음 앞·끝 뒤는 배경색으로 덮어 흐리게 한다(밝은 테마에서도 회색 덩어리로 보이지 않게).
            if (start > 0) drawRect(colors.background.copy(alpha = 0.6f), Offset.Zero, Size(start, size.height))
            if (end < size.width) drawRect(colors.background.copy(alpha = 0.6f), Offset(end, 0f), Size(size.width - end, size.height))
            drawLine(colors.background.copy(alpha = 0.5f), Offset(center, 0f), Offset(center, size.height), 4.dp.toPx())
            drawLine(colors.foreground, Offset(center, 0f), Offset(center, size.height), 2.dp.toPx())
            drawCircle(colors.foreground, 4.dp.toPx(), Offset(center, 2.dp.toPx()))
        }
    }
}

private data class Snap(val project: AudioProject, val positionUs: Long, val selectedId: String?, val range: TimeRangeUs?, val actions: AudioTimelineActions)

private fun AudioRole.lane() = if (this == AudioRole.VOICE) AudioRole.VOICE else AudioRole.MUSIC

private fun DrawScope.drawWave(wave: Waveform, clip: AudioClip, left: Float, right: Float, top: Float, h: Float, center: Float, positionUs: Long, pxPerUs: Float) {
    val step = 3.dp.toPx()
    val bar = 2.dp.toPx()
    val length = clip.sourceRange.durationUs.coerceAtLeast(1)
    val first = length - clip.startOffsetUs
    val middle = top + h / 2 + 6.dp.toPx()
    var px = left.coerceAtLeast(0f)
    val end = right.coerceAtMost(size.width)
    while (px < end) {
        val time = positionUs + ((px - center) / pxPerUs).toLong()
        val elapsed = time - clip.timelineStartUs
        val source = if (elapsed < first) clip.sourceRange.startUs + clip.startOffsetUs + elapsed else clip.sourceRange.startUs + (elapsed - first) % length
        val half = ((h / 2 - 8.dp.toPx()) * wave.peakAt(source)).coerceAtLeast(0.75f)
        drawLine(Color.White.copy(alpha = 0.7f), Offset(px, middle - half), Offset(px, middle + half), bar)
        px += step
    }
}

private fun DrawScope.drawHandles(left: Float, right: Float, top: Float, h: Float, color: Color) {
    val w = 8.dp.toPx()
    listOf(left - w / 2, right - w / 2).forEach { x0 ->
        drawRoundRect(color, Offset(x0, top - 2.dp.toPx()), Size(w, h + 4.dp.toPx()), CornerRadius(w / 2))
        drawLine(Color.Black.copy(alpha = 0.45f), Offset(x0 + w / 2, top + h * 0.35f), Offset(x0 + w / 2, top + h * 0.65f), 1.5.dp.toPx())
    }
}

private fun DrawScope.drawWedge(edgeX: Float, top: Float, width: Float, h: Float, fromLeft: Boolean) {
    val path = Path().apply {
        moveTo(edgeX, top)
        lineTo(if (fromLeft) edgeX + width else edgeX - width, top)
        lineTo(edgeX, top + h)
        close()
    }
    drawPath(path, Color.Black.copy(alpha = 0.3f))
}

private fun DrawScope.drawLabel(measurer: androidx.compose.ui.text.TextMeasurer, text: String, left: Float, top: Float, h: Float, color: Color, maxWidth: Float) {
    if (maxWidth < 16.dp.toPx() || text.isBlank()) return
    val layout = measurer.measure(text, TextStyle(color = color, fontSize = 11.sp), overflow = TextOverflow.Ellipsis, maxLines = 1, constraints = Constraints(maxWidth = maxWidth.toInt().coerceAtLeast(1)))
    drawText(layout, topLeft = Offset(left, top + (h - layout.size.height) / 2))
}

private val MAIN_H = 84.dp
private val LAYER_H = 44.dp
private val MAIN_H_TALL = 150.dp
private val LAYER_H_TALL = 72.dp
private val GAP = 8.dp
private val LAYER = Color(0xFF2EB67D)
private const val MIN_DPS = 6f
private const val MAX_DPS = 320f
