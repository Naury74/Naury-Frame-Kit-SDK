package com.naury.framekit.ui.video.timeline

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.naury.framekit.core.model.SourceId
import com.naury.framekit.core.video.AudioRole
import com.naury.framekit.core.video.TimeRangeUs
import com.naury.framekit.ui.design.FrameKitTheme
import com.naury.framekit.ui.video.editor.AudioDragMode
import com.naury.framekit.ui.video.editor.TrimEdge
import com.naury.framekit.video.audio.Waveform
import kotlin.math.abs
import kotlin.math.roundToInt

/** 트랙 타임라인의 줄. 위에서 아래 순서다. */
internal enum class TrackLane { VIDEO, ORIGINAL, MUSIC, VOICE, OVERLAY, MASK }

/**
 * 원본 소리 줄에 그릴 클립의 소리 상태.
 *
 * @property mutedOutput 소리를 끈 구간(출력 시간 µs).
 */
internal data class TimelineOriginal(
    val clipId: String,
    val range: TimeRangeUs,
    val muted: Boolean,
    val mutedOutput: List<TimeRangeUs>,
    val source: SourceId,
    val sourceStartUs: Long,
    val speed: Double,
) {
    /** 출력 시각의 원본 위치. */
    fun sourceAt(timeUs: Long): Long = sourceStartUs + ((timeUs - range.startUs) * speed).toLong()
}

/** 배경 음악·음성 줄의 조각. [range]는 들리는 출력 구간이다. */
internal data class TimelineAudio(
    val id: String,
    val role: AudioRole,
    val range: TimeRangeUs,
    val name: String?,
    val loop: Boolean,
    val fadeInUs: Long,
    val fadeOutUs: Long,
    val source: SourceId,
    val sourceRange: TimeRangeUs,
    val startOffsetUs: Long,
    val timelineStartUs: Long,
) {
    /** 출력 시각의 원본 위치. 첫 재생은 [startOffsetUs]부터, 반복은 구간 처음부터다. */
    fun sourceAt(timeUs: Long): Long {
        val length = sourceRange.durationUs.coerceAtLeast(1)
        val elapsed = (timeUs - timelineStartUs).coerceAtLeast(0)
        val first = length - startOffsetUs
        return if (elapsed < first) sourceRange.startUs + startOffsetUs + elapsed else sourceRange.startUs + (elapsed - first) % length
    }
}

/** 트랙 타임라인에서 고친 것을 알리는 콜백 묶음. */
internal class TrackTimelineActions(
    val onScrub: (Long) -> Unit,
    val onScrubEnd: () -> Unit,
    val onSelectClip: (String) -> Unit,
    val onSelectOriginal: (String) -> Unit,
    val onSelectAudio: (String) -> Unit,
    val onSelectItem: (TimelineItemKind, String) -> Unit,
    val onAddAudio: (AudioRole) -> Unit,
    val onBeginClipEdge: (String, TrimEdge) -> Boolean,
    val onDragClipEdge: (Long) -> Unit,
    val onEndClipEdge: () -> Unit,
    val onBeginAudioDrag: (String, AudioDragMode) -> Boolean,
    val onDragAudio: (Long) -> Unit,
    val onEndAudioDrag: () -> Unit,
    val onMoveMuteEdge: (TrimEdge, Long) -> Unit,
)

/**
 * 영상·원본 소리·배경 음악·음성·텍스트·가리기를 줄로 나눠 보여 주는 타임라인. 가운데 재생 헤드는 고정되고 내용이 움직인다.
 *
 * - 한 손가락으로 끌면 재생 위치를 옮기고, 두 손가락으로 벌리거나 좁히면 확대한다.
 * - 눌러서 클립·원본 소리·소리 조각·텍스트를 고른다. 빈 음악·음성 줄을 누르면 추가한다.
 * - 고른 클립의 양 끝 손잡이를 끌면 그 자리에서 자르고, 고른 소리 조각은 끌어서 옮기거나 끝을 잘라 늘이고 줄인다.
 * - 원본 소리에서 끌 구간을 고르는 중이면 그 구간의 손잡이를 끌어 조절한다.
 *
 * @param clipHandles 고른 클립에 자르기 손잡이를 보일지.
 */
@Composable
internal fun TrackTimeline(
    clips: List<TimelineClip>,
    selectedClipId: String,
    clipHandles: Boolean,
    originals: List<TimelineOriginal>,
    originalSelected: Boolean,
    audio: List<TimelineAudio>,
    selectedAudioId: String?,
    items: List<TimelineItem>,
    muteSelection: TimeRangeUs?,
    durationUs: Long,
    positionUs: Long,
    labels: Map<TrackLane, String>,
    emptyAudioLabels: Map<AudioRole, String>,
    waveforms: Map<SourceId, Waveform>,
    loadFrame: suspend (SourceId, Long, Int) -> Bitmap?,
    actions: TrackTimelineActions,
    description: String,
    modifier: Modifier = Modifier,
) {
    val colors = FrameKitTheme.colors
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val hapticsEnabled = FrameKitTheme.config.enableHaptics
    val textMeasurer = rememberTextMeasurer()
    val lanes = buildList {
        add(TrackLane.VIDEO)
        if (originals.isNotEmpty()) add(TrackLane.ORIGINAL)
        if (AudioRole.MUSIC in emptyAudioLabels || audio.any { it.role == AudioRole.MUSIC }) add(TrackLane.MUSIC)
        if (AudioRole.VOICE in emptyAudioLabels || audio.any { it.role == AudioRole.VOICE }) add(TrackLane.VOICE)
        if (items.any { it.kind == TimelineItemKind.OVERLAY }) add(TrackLane.OVERLAY)
        if (items.any { it.kind == TimelineItemKind.MASK }) add(TrackLane.MASK)
    }
    val totalHeight = lanes.fold(0.dp) { sum, lane -> sum + laneHeight(lane) + LANE_GAP }
    val current by rememberUpdatedState(
        Snapshot(clips, selectedClipId, clipHandles, originals, originalSelected, audio, selectedAudioId, items, muteSelection, durationUs, positionUs, lanes, actions),
    )
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .height(totalHeight)
            .semantics {
                contentDescription = description
                stateDescription = "${formatTime(positionUs)} / ${formatTime(durationUs)}"
                progressBarRangeInfo = ProgressBarRangeInfo(positionUs.toFloat(), 0f..durationUs.coerceAtLeast(1).toFloat(), steps = 0)
                setProgress { target ->
                    current.actions.onScrub(target.toLong().coerceIn(0, durationUs))
                    current.actions.onScrubEnd()
                    true
                }
            },
    ) {
        val widthPx = constraints.maxWidth.toFloat()
        val videoPx = with(density) { laneHeight(TrackLane.VIDEO).toPx() }
        val thumbPx = videoPx.roundToInt().coerceAtMost(MAX_FRAME_PX)
        var dpPerSecond by remember(durationUs > 0) {
            val fit = with(density) { (widthPx * 0.8f).toDp().value } / (durationUs / 1_000_000f).coerceAtLeast(0.1f)
            mutableFloatStateOf(fit.coerceIn(DEFAULT_DP_PER_SECOND, MAX_DP_PER_SECOND))
        }
        val pxPerUs = with(density) { dpPerSecond.dp.toPx() } / 1_000_000f
        val center = widthPx / 2
        val laneTops = remember(lanes, density) {
            var top = 0f
            lanes.associateWith { lane -> top.also { top += with(density) { (laneHeight(lane) + LANE_GAP).toPx() } } }
        }
        val currentPxPerUs by rememberUpdatedState(pxPerUs)
        val currentTops by rememberUpdatedState(laneTops)

        val tiles = visibleTrackTiles(clips, positionUs, pxPerUs, center, widthPx, videoPx)
        val frames = remember { mutableStateMapOf<String, Bitmap>() }
        val currentLoad by rememberUpdatedState(loadFrame)
        LaunchedEffect(tiles.map { it.key }.toSet()) {
            if (frames.size > MAX_KEPT_FRAMES) frames.keys.retainAll(tiles.map { it.key }.toSet())
            tiles.filter { it.key !in frames }.forEach { tile -> currentLoad(tile.source, tile.sourceTimeUs, thumbPx)?.let { frames[tile.key] = it } }
        }
        val handlePx = with(density) { HANDLE_TOUCH.toPx() }
        val slopPx = with(density) { 10.dp.toPx() }

        Canvas(
            Modifier
                .matchParentSize()
                .systemGestureExclusion()
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        val s = current
                        val tops = currentTops
                        val pxUs = currentPxPerUs
                        fun x(timeUs: Long) = center + (timeUs - s.positionUs) * pxUs
                        fun timeAt(px: Float) = (s.positionUs + (px - center) / pxUs).toLong()
                        val lane = laneAt(down.position.y, tops, density)
                        val target = hitTarget(s, lane, down.position.x, handlePx, slopPx, ::x, ::timeAt)
                        val startPosition = s.positionUs
                        var lastTarget = startPosition
                        var moved = false
                        var mode: DragMode = DragMode.NONE
                        var totalDx = 0f
                        do {
                            val event = awaitPointerEvent()
                            val pressed = event.changes.filter { it.pressed }
                            if (pressed.size >= 2) {
                                val zoom = event.calculateZoom()
                                if (zoom != 1f) {
                                    dpPerSecond = (dpPerSecond * zoom).coerceIn(MIN_DP_PER_SECOND, MAX_DP_PER_SECOND)
                                    moved = true
                                }
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                                continue
                            }
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            totalDx += change.position.x - change.previousPosition.x
                            if (!moved && abs(totalDx) > viewConfiguration.touchSlop) {
                                moved = true
                                // 손잡이·고른 조각을 잡았으면 그것을 고치고, 아니면 재생 위치를 옮긴다.
                                mode = when (target) {
                                    is Hit.ClipEdge -> if (s.actions.onBeginClipEdge(target.clipId, target.edge)) DragMode.CLIP_EDGE else DragMode.SCRUB
                                    is Hit.AudioDrag -> if (s.actions.onBeginAudioDrag(target.id, target.mode)) DragMode.AUDIO else DragMode.SCRUB
                                    is Hit.MuteEdge -> DragMode.MUTE_EDGE
                                    else -> DragMode.SCRUB
                                }
                            }
                            if (!moved) continue
                            val deltaUs = (totalDx / currentPxPerUs).toLong()
                            when (mode) {
                                DragMode.CLIP_EDGE -> current.actions.onDragClipEdge(deltaUs)
                                DragMode.AUDIO -> current.actions.onDragAudio(deltaUs)
                                DragMode.MUTE_EDGE -> {
                                    val edge = (target as Hit.MuteEdge).edge
                                    val base = if (edge == TrimEdge.START) target.startUs else target.endUs
                                    current.actions.onMoveMuteEdge(edge, base + deltaUs)
                                }
                                DragMode.SCRUB -> {
                                    val next = (startPosition - totalDx / currentPxPerUs).toLong().coerceIn(0, current.durationUs)
                                    val boundaries = current.clips.map { it.outputStartUs } + current.durationUs
                                    val crossed = boundaries.any { b -> (lastTarget < b && next >= b) || (lastTarget > b && next <= b) }
                                    if (crossed && hapticsEnabled) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                    lastTarget = next
                                    current.actions.onScrub(next)
                                }
                                DragMode.NONE -> Unit
                            }
                            change.consume()
                        } while (event.changes.any { it.pressed })
                        when (mode) {
                            DragMode.CLIP_EDGE -> current.actions.onEndClipEdge()
                            DragMode.AUDIO -> current.actions.onEndAudioDrag()
                            DragMode.SCRUB -> current.actions.onScrubEnd()
                            else -> Unit
                        }
                        if (!moved) tap(s, lane, timeAt(down.position.x), slopPx / pxUs)
                    }
                },
        ) {
            val radius = CornerRadius(6.dp.toPx())
            val gap = 2.dp.toPx()
            fun x(timeUs: Long) = center + (timeUs - positionUs) * pxPerUs
            val audioSelected = selectedAudioId != null

            // 영상 줄
            val videoTop = laneTops.getValue(TrackLane.VIDEO)
            clips.forEach { clip ->
                val left = x(clip.outputStartUs) + gap / 2
                val right = x(clip.outputStartUs + clip.outputDurationUs) - gap / 2
                if (right < 0 || left > size.width) return@forEach
                drawRoundRect(colors.surface, Offset(left, videoTop), Size(right - left, videoPx), radius)
                clipRect(left, videoTop, right, videoTop + videoPx) {
                    tiles.filter { it.clipId == clip.id }.forEach { tile ->
                        val bitmap = frames[tile.key]?.takeIf { !it.isRecycled } ?: return@forEach
                        drawImage(
                            bitmap.asImageBitmap(),
                            dstOffset = IntOffset(tile.left.roundToInt(), videoTop.roundToInt()),
                            dstSize = IntSize(tile.width.roundToInt().coerceAtLeast(1), videoPx.roundToInt()),
                        )
                    }
                }
                val selected = clip.id == selectedClipId && !audioSelected && !originalSelected
                if (selected && (clips.size > 1 || clipHandles)) {
                    drawRoundRect(colors.accent, Offset(left, videoTop), Size(right - left, videoPx), radius, style = Stroke(2.5.dp.toPx()))
                }
                if (selected && clipHandles) drawHandles(left, right, videoTop, videoPx, colors.accent)
            }

            // 원본 소리 줄
            laneTops[TrackLane.ORIGINAL]?.let { top ->
                val h = laneHeight(TrackLane.ORIGINAL).toPx()
                val base = Color(0xFF4C7DFF)
                originals.forEach { original ->
                    val left = x(original.range.startUs) + gap / 2
                    val right = x(original.range.endExclusiveUs) - gap / 2
                    if (right < 0 || left > size.width) return@forEach
                    drawRoundRect(if (original.muted) MUTED else base.copy(alpha = 0.7f), Offset(left, top), Size(right - left, h), CornerRadius(h / 3))
                    waveforms[original.source]?.let { waveform ->
                        if (!original.muted) clipRect(left, top, right, top + h) { drawWave(waveform, left, right, top, h, Color.White.copy(alpha = 0.5f)) { px -> original.sourceAt(positionUs + ((px - center) / pxPerUs).toLong()) } }
                    }
                    original.mutedOutput.forEach { muted ->
                        val l = x(muted.startUs).coerceAtLeast(left)
                        val r = x(muted.endExclusiveUs).coerceAtMost(right)
                        if (r > l) drawHatched(l, top, r - l, h)
                    }
                    if (originalSelected && original.clipId == selectedClipId && muteSelection == null) {
                        drawRoundRect(Color.White, Offset(left, top), Size(right - left, h), CornerRadius(h / 3), style = Stroke(1.5.dp.toPx()))
                    }
                }
                muteSelection?.let { selection ->
                    val l = x(selection.startUs)
                    val r = x(selection.endExclusiveUs)
                    drawRect(colors.error.copy(alpha = 0.35f), Offset(l, top), Size(r - l, h))
                    drawHandles(l, r, top, h, colors.error)
                }
            }

            // 배경 음악·음성 줄
            listOf(TrackLane.MUSIC to AudioRole.MUSIC, TrackLane.VOICE to AudioRole.VOICE).forEach { (lane, role) ->
                val top = laneTops[lane] ?: return@forEach
                val h = laneHeight(lane).toPx()
                val color = if (role == AudioRole.MUSIC) Color(0xFF2EB67D) else Color(0xFFF59F00)
                val pieces = audio.filter { it.role == role }
                if (pieces.isEmpty()) {
                    // 빈 줄은 눌러서 추가할 수 있다는 것을 보여 준다.
                    val label = emptyAudioLabels[role] ?: return@forEach
                    drawRoundRect(color.copy(alpha = 0.12f), Offset(x(0).coerceAtLeast(0f), top), Size((x(durationUs).coerceAtMost(size.width) - x(0).coerceAtLeast(0f)).coerceAtLeast(0f), h), CornerRadius(h / 3))
                    drawLabel(textMeasurer, "+ $label", center + 8.dp.toPx(), top, h, color, maxWidth = size.width / 2)
                }
                pieces.forEach { piece ->
                    val left = x(piece.range.startUs)
                    val right = x(piece.range.endExclusiveUs)
                    if (right < 0 || left > size.width || right <= left) return@forEach
                    drawRoundRect(color.copy(alpha = 0.85f), Offset(left, top), Size(right - left, h), CornerRadius(h / 3))
                    waveforms[piece.source]?.let { waveform ->
                        clipRect(left, top, right, top + h) { drawWave(waveform, left, right, top, h, Color.Black.copy(alpha = 0.28f)) { px -> piece.sourceAt(positionUs + ((px - center) / pxPerUs).toLong()) } }
                    }
                    // 페이드는 끝을 어둡게 기울여 보여 준다.
                    if (piece.fadeInUs > 0) drawWedge(left, top, (piece.fadeInUs * pxPerUs).coerceAtMost(right - left), h, fromLeft = true)
                    if (piece.fadeOutUs > 0) drawWedge(right, top, (piece.fadeOutUs * pxPerUs).coerceAtMost(right - left), h, fromLeft = false)
                    val text = (if (piece.loop) "↻ " else "") + (piece.name ?: labels[lane].orEmpty())
                    clipRect(left, top, right, top + h) { drawLabel(textMeasurer, text, left.coerceAtLeast(0f) + 6.dp.toPx(), top, h, Color.White, maxWidth = right - left.coerceAtLeast(0f) - 8.dp.toPx()) }
                    if (piece.id == selectedAudioId) {
                        drawRoundRect(Color.White, Offset(left, top), Size(right - left, h), CornerRadius(h / 3), style = Stroke(2.dp.toPx()))
                        drawHandles(left, right, top, h, Color.White)
                    }
                }
            }

            // 텍스트·가리기 줄
            listOf(TrackLane.OVERLAY to TimelineItemKind.OVERLAY, TrackLane.MASK to TimelineItemKind.MASK).forEach { (lane, kind) ->
                val top = laneTops[lane] ?: return@forEach
                val h = laneHeight(lane).toPx()
                val color = if (kind == TimelineItemKind.OVERLAY) colors.accent.copy(alpha = 0.75f) else Color(0xFF8E8E99)
                items.filter { it.kind == kind }.forEach { item ->
                    val left = x(item.range.startUs).coerceAtLeast(-10f)
                    val right = x(item.range.endExclusiveUs).coerceAtMost(size.width + 10f)
                    if (right <= left) return@forEach
                    drawRoundRect(color, Offset(left, top), Size(right - left, h), CornerRadius(h / 2))
                    if (item.selected) drawRoundRect(Color.White, Offset(left, top), Size(right - left, h), CornerRadius(h / 2), style = Stroke(1.5.dp.toPx()))
                }
            }

            val start = x(0)
            val end = x(durationUs)
            // 처음 앞·끝 뒤는 배경색으로 덮어 흐리게 한다(밝은 테마에서도 회색 덩어리로 보이지 않게).
            if (start > 0) drawRect(colors.background.copy(alpha = 0.6f), Offset.Zero, Size(start, size.height))
            if (end < size.width) drawRect(colors.background.copy(alpha = 0.6f), Offset(end, 0f), Size(size.width - end, size.height))

            // 줄 이름은 왼쪽에 고정해 어느 줄인지 늘 보이게 한다.
            lanes.filter { it != TrackLane.VIDEO }.forEach { lane ->
                val label = labels[lane] ?: return@forEach
                val top = laneTops.getValue(lane)
                val h = laneHeight(lane).toPx()
                if (h < 16.dp.toPx()) return@forEach
                val layout = textMeasurer.measure(label, TextStyle(color = Color.White, fontSize = 10.sp), maxLines = 1)
                val pad = 4.dp.toPx()
                drawRoundRect(Color.Black.copy(alpha = 0.55f), Offset(pad, top + (h - layout.size.height) / 2 - 1.dp.toPx()), Size(layout.size.width + pad * 2, layout.size.height + 2.dp.toPx()), CornerRadius(4.dp.toPx()))
                drawText(layout, topLeft = Offset(pad * 2, top + (h - layout.size.height) / 2))
            }

            drawLine(Color.Black.copy(alpha = 0.5f), Offset(center, -4.dp.toPx()), Offset(center, size.height + 4.dp.toPx()), 4.dp.toPx())
            drawLine(Color.White, Offset(center, -4.dp.toPx()), Offset(center, size.height + 4.dp.toPx()), 2.dp.toPx())
        }
    }
}

private data class Snapshot(
    val clips: List<TimelineClip>,
    val selectedClipId: String,
    val clipHandles: Boolean,
    val originals: List<TimelineOriginal>,
    val originalSelected: Boolean,
    val audio: List<TimelineAudio>,
    val selectedAudioId: String?,
    val items: List<TimelineItem>,
    val muteSelection: TimeRangeUs?,
    val durationUs: Long,
    val positionUs: Long,
    val lanes: List<TrackLane>,
    val actions: TrackTimelineActions,
)

private enum class DragMode { NONE, SCRUB, CLIP_EDGE, AUDIO, MUTE_EDGE }

private sealed interface Hit {
    data class ClipEdge(val clipId: String, val edge: TrimEdge) : Hit
    data class AudioDrag(val id: String, val mode: AudioDragMode) : Hit
    data class MuteEdge(val edge: TrimEdge, val startUs: Long, val endUs: Long) : Hit
    data object None : Hit
}

// 손잡이·고른 조각 위를 눌렀는지. 끌기를 시작할 때 무엇을 고칠지 정한다.
private fun hitTarget(s: Snapshot, lane: TrackLane?, px: Float, handlePx: Float, slopPx: Float, x: (Long) -> Float, timeAt: (Float) -> Long): Hit {
    when (lane) {
        TrackLane.VIDEO -> if (s.clipHandles && s.selectedAudioId == null && !s.originalSelected) {
            val clip = s.clips.firstOrNull { it.id == s.selectedClipId } ?: return Hit.None
            val left = x(clip.outputStartUs)
            val right = x(clip.outputStartUs + clip.outputDurationUs)
            if (abs(px - left) <= handlePx) return Hit.ClipEdge(clip.id, TrimEdge.START)
            if (abs(px - right) <= handlePx) return Hit.ClipEdge(clip.id, TrimEdge.END)
        }
        TrackLane.ORIGINAL -> s.muteSelection?.let { selection ->
            if (abs(px - x(selection.startUs)) <= handlePx) return Hit.MuteEdge(TrimEdge.START, selection.startUs, selection.endExclusiveUs)
            if (abs(px - x(selection.endExclusiveUs)) <= handlePx) return Hit.MuteEdge(TrimEdge.END, selection.startUs, selection.endExclusiveUs)
        }
        TrackLane.MUSIC, TrackLane.VOICE -> {
            val selected = s.audio.firstOrNull { it.id == s.selectedAudioId && laneOf(it.role) == lane } ?: return Hit.None
            val left = x(selected.range.startUs)
            val right = x(selected.range.endExclusiveUs)
            // 짧은 조각은 가운데를 옮기기로 남기려고 손잡이 폭을 조각 폭의 1/3로 줄인다.
            val edge = handlePx.coerceAtMost((right - left) / 3)
            if (abs(px - left) <= edge) return Hit.AudioDrag(selected.id, AudioDragMode.START)
            if (abs(px - right) <= edge) return Hit.AudioDrag(selected.id, AudioDragMode.END)
            if (px > left - slopPx && px < right + slopPx) return Hit.AudioDrag(selected.id, AudioDragMode.MOVE)
        }
        else -> Unit
    }
    if (timeAt(px) < 0) return Hit.None
    return Hit.None
}

private fun tap(s: Snapshot, lane: TrackLane?, timeUs: Long, slopUs: Float) {
    val slop = slopUs.toLong()
    when (lane) {
        TrackLane.VIDEO -> s.clips.firstOrNull { timeUs >= it.outputStartUs && timeUs < it.outputStartUs + it.outputDurationUs }?.let { s.actions.onSelectClip(it.id) }
        TrackLane.ORIGINAL -> s.originals.firstOrNull { timeUs in it.range }?.let { s.actions.onSelectOriginal(it.clipId) }
        TrackLane.MUSIC, TrackLane.VOICE -> {
            val role = if (lane == TrackLane.MUSIC) AudioRole.MUSIC else AudioRole.VOICE
            val hit = s.audio.lastOrNull { it.role == role && timeUs >= it.range.startUs - slop && timeUs < it.range.endExclusiveUs + slop }
            if (hit != null) s.actions.onSelectAudio(hit.id) else s.actions.onAddAudio(role)
        }
        TrackLane.OVERLAY, TrackLane.MASK -> {
            val kind = if (lane == TrackLane.OVERLAY) TimelineItemKind.OVERLAY else TimelineItemKind.MASK
            s.items.lastOrNull { it.kind == kind && timeUs >= it.range.startUs - slop && timeUs < it.range.endExclusiveUs + slop }?.let { s.actions.onSelectItem(it.kind, it.id) }
        }
        null -> Unit
    }
}

private fun laneOf(role: AudioRole) = if (role == AudioRole.MUSIC) TrackLane.MUSIC else TrackLane.VOICE

private fun laneAt(y: Float, tops: Map<TrackLane, Float>, density: androidx.compose.ui.unit.Density): TrackLane? =
    tops.entries.firstOrNull { (lane, top) -> y >= top && y < top + with(density) { (laneHeight(lane) + LANE_GAP).toPx() } }?.key

private fun laneHeight(lane: TrackLane): Dp = when (lane) {
    TrackLane.VIDEO -> 52.dp
    TrackLane.ORIGINAL -> 20.dp
    TrackLane.MUSIC, TrackLane.VOICE -> 26.dp
    TrackLane.OVERLAY, TrackLane.MASK -> 14.dp
}

// 양 끝에 잡을 수 있는 손잡이(세로 막대)를 그린다.
private fun DrawScope.drawHandles(left: Float, right: Float, top: Float, height: Float, color: Color) {
    val w = 7.dp.toPx()
    listOf(left - w / 2, right - w / 2).forEach { x0 ->
        drawRoundRect(color, Offset(x0, top - 1.dp.toPx()), Size(w, height + 2.dp.toPx()), CornerRadius(w / 2))
        drawLine(Color.Black.copy(alpha = 0.5f), Offset(x0 + w / 2, top + height * 0.3f), Offset(x0 + w / 2, top + height * 0.7f), 1.5.dp.toPx())
    }
}

// 파형을 세로 막대로 그린다. 화면에 보이는 부분만, 몇 픽셀 간격으로 원본 위치를 찾아 그 시각의 소리 크기만큼 그린다.
private fun DrawScope.drawWave(waveform: Waveform, left: Float, right: Float, top: Float, height: Float, color: Color, sourceAt: (Float) -> Long) {
    val step = 3.dp.toPx()
    val bar = 1.5.dp.toPx()
    var x0 = left.coerceAtLeast(0f)
    val end = right.coerceAtMost(size.width)
    val middle = top + height / 2
    while (x0 < end) {
        val peak = waveform.peakAt(sourceAt(x0))
        val half = (height * 0.42f * peak).coerceAtLeast(0.5f)
        drawLine(color, Offset(x0, middle - half), Offset(x0, middle + half), bar)
        x0 += step
    }
}

private fun DrawScope.drawHatched(left: Float, top: Float, width: Float, height: Float) {
    drawRect(MUTED, Offset(left, top), Size(width, height))
    clipRect(left, top, left + width, top + height) {
        var x0 = left - height
        val step = 6.dp.toPx()
        while (x0 < left + width) {
            drawLine(Color.Black.copy(alpha = 0.35f), Offset(x0, top + height), Offset(x0 + height, top), 1.dp.toPx())
            x0 += step
        }
    }
}

private fun DrawScope.drawWedge(edgeX: Float, top: Float, width: Float, height: Float, fromLeft: Boolean) {
    val path = Path().apply {
        if (fromLeft) {
            moveTo(edgeX, top); lineTo(edgeX + width, top); lineTo(edgeX, top + height); close()
        } else {
            moveTo(edgeX, top); lineTo(edgeX - width, top); lineTo(edgeX, top + height); close()
        }
    }
    drawPath(path, Color.Black.copy(alpha = 0.3f))
}

private fun DrawScope.drawLabel(measurer: androidx.compose.ui.text.TextMeasurer, text: String, left: Float, top: Float, height: Float, color: Color, maxWidth: Float) {
    if (maxWidth < 12.dp.toPx()) return
    val layout = measurer.measure(
        text,
        TextStyle(color = color, fontSize = 11.sp),
        overflow = TextOverflow.Ellipsis,
        maxLines = 1,
        constraints = Constraints(maxWidth = maxWidth.toInt().coerceAtLeast(1)),
    )
    drawText(layout, topLeft = Offset(left, top + (height - layout.size.height) / 2))
}

private data class TrackTile(val key: String, val clipId: String, val source: SourceId, val sourceTimeUs: Long, val left: Float, val width: Float)

private fun visibleTrackTiles(clips: List<TimelineClip>, positionUs: Long, pxPerUs: Float, center: Float, width: Float, stripPx: Float): List<TrackTile> =
    clips.flatMap { clip ->
        val left = center + (clip.outputStartUs - positionUs) * pxPerUs
        val clipWidth = clip.outputDurationUs * pxPerUs
        if (left + clipWidth < 0 || left > width) return@flatMap emptyList()
        val tileWidth = (stripPx * clip.frameAspect).coerceAtLeast(8f)
        val first = kotlin.math.floor((-left / tileWidth).coerceAtLeast(0f)).toInt()
        val last = kotlin.math.ceil(((width - left) / tileWidth).coerceAtMost(clipWidth / tileWidth)).toInt()
        (first until last.coerceAtLeast(first)).map { index ->
            val outputOffset = ((index + 0.5f) * tileWidth / pxPerUs).toLong().coerceIn(0, clip.outputDurationUs)
            val sourceTime = clip.sourceRange.startUs + (outputOffset * clip.speed).toLong()
            val bucket = sourceTime / BUCKET_US * BUCKET_US
            TrackTile("${clip.source.value}/$bucket", clip.id, clip.source, bucket, left + index * tileWidth, tileWidth)
        }
    }

private val MUTED = Color(0xFF55555E)
private val LANE_GAP = 6.dp
private val HANDLE_TOUCH = 18.dp
private const val MAX_FRAME_PX = 160
private const val BUCKET_US = 250_000L
private const val MAX_KEPT_FRAMES = 120
private const val MIN_DP_PER_SECOND = 8f
private const val DEFAULT_DP_PER_SECOND = 40f
private const val MAX_DP_PER_SECOND = 240f
