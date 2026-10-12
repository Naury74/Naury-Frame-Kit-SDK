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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.naury.framekit.core.model.SourceId
import com.naury.framekit.core.video.TimeRangeUs
import com.naury.framekit.ui.design.FrameKitTheme
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/** 타임라인에 그릴 클립. 시간은 모두 µs다. */
internal data class TimelineClip(
    val id: String,
    val source: SourceId,
    val sourceRange: TimeRangeUs,
    val speed: Double,
    val outputStartUs: Long,
    val outputDurationUs: Long,
    val frameAspect: Float,
)

/** 클립 아래 줄에 그리는 구간 항목의 종류. 줄 순서이기도 하다. */
internal enum class TimelineItemKind { OVERLAY, MASK, MUSIC }

/** 출력 시간 [range] 동안 보이는 텍스트·스티커·마스크나 배경 음악. */
internal data class TimelineItem(val kind: TimelineItemKind, val id: String, val range: TimeRangeUs, val selected: Boolean)

/**
 * 가운데 재생 헤드가 고정되고 내용이 움직이는 여러 클립 타임라인.
 *
 * 한 손가락으로 끌면 재생 위치를 옮기고, 두 손가락으로 벌리거나 좁히면 1초당 8..240dp 사이로 확대한다.
 * 탭하면 그 위치의 클립이나 구간 항목을 고른다. 썸네일은 화면에 보이는 칸만 읽는다.
 */
@Composable
internal fun MultiClipTimeline(
    clips: List<TimelineClip>,
    selectedClipId: String,
    items: List<TimelineItem>,
    durationUs: Long,
    positionUs: Long,
    loadFrame: suspend (SourceId, Long, Int) -> Bitmap?,
    onScrub: (Long) -> Unit,
    onScrubEnd: () -> Unit,
    onSelectClip: (String) -> Unit,
    onSelectItem: (TimelineItemKind, String) -> Unit,
    description: String,
    modifier: Modifier = Modifier,
) {
    val colors = FrameKitTheme.colors
    val density = LocalDensity.current
    val rows = TimelineItemKind.entries.filter { kind -> items.any { it.kind == kind } }
    val height = STRIP_HEIGHT + (ROW_HEIGHT + ROW_GAP) * rows.size
    val haptics = LocalHapticFeedback.current
    val hapticsEnabled = FrameKitTheme.config.enableHaptics
    val currentScrubForA11y by rememberUpdatedState(onScrub)
    val currentScrubEndForA11y by rememberUpdatedState(onScrubEnd)
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .height(height)
            // 화면 읽기 프로그램에서는 재생 위치 조절 막대로 보이고, 볼륨 키 등으로 위치를 옮길 수 있다.
            .semantics {
                contentDescription = description
                stateDescription = "${formatTime(positionUs)} / ${formatTime(durationUs)}"
                progressBarRangeInfo = ProgressBarRangeInfo(positionUs.toFloat(), 0f..durationUs.coerceAtLeast(1).toFloat(), steps = 0)
                setProgress { target ->
                    currentScrubForA11y(target.toLong().coerceIn(0, durationUs))
                    currentScrubEndForA11y()
                    true
                }
            },
    ) {
        val widthPx = constraints.maxWidth.toFloat()
        val stripPx = with(density) { STRIP_HEIGHT.toPx() }
        val thumbPx = stripPx.roundToInt().coerceAtMost(MAX_FRAME_PX)
        // 처음에는 결과 전체가 화면 폭의 80% 안팎으로 보이게 하되 1초당 40..240dp로 제한한다.
        var dpPerSecond by remember(durationUs > 0) {
            val fit = with(density) { (widthPx * 0.8f).toDp().value } / (durationUs / 1_000_000f).coerceAtLeast(0.1f)
            mutableFloatStateOf(fit.coerceIn(DEFAULT_DP_PER_SECOND, MAX_DP_PER_SECOND))
        }
        val pxPerUs = with(density) { dpPerSecond.dp.toPx() } / 1_000_000f
        val center = widthPx / 2

        // 화면에 보이는 썸네일 칸만 계산해 읽는다. 원본·시간 칸이 같으면 로더 캐시를 그대로 쓴다.
        val tiles = visibleTiles(clips, positionUs, pxPerUs, center, widthPx, stripPx)
        val frames = remember { mutableStateMapOf<String, Bitmap>() }
        val currentLoad by rememberUpdatedState(loadFrame)
        LaunchedEffect(tiles.map { it.key }.toSet()) {
            // 오래 편집해도 메모리가 늘지 않게, 많이 쌓이면 지금 보이는 칸만 남긴다(로더 캐시가 다시 채운다).
            if (frames.size > MAX_KEPT_FRAMES) frames.keys.retainAll(tiles.map { it.key }.toSet())
            tiles.filter { it.key !in frames }.forEach { tile ->
                currentLoad(tile.source, tile.sourceTimeUs, thumbPx)?.let { frames[tile.key] = it }
            }
        }

        val currentPosition by rememberUpdatedState(positionUs)
        val currentDuration by rememberUpdatedState(durationUs)
        val currentPxPerUs by rememberUpdatedState(pxPerUs)
        val currentClips by rememberUpdatedState(clips)
        val currentItems by rememberUpdatedState(items)
        val currentRows by rememberUpdatedState(rows)
        val currentScrub by rememberUpdatedState(onScrub)
        val currentScrubEnd by rememberUpdatedState(onScrubEnd)
        val currentSelectClip by rememberUpdatedState(onSelectClip)
        val currentSelectItem by rememberUpdatedState(onSelectItem)
        val rowPx = with(density) { ROW_HEIGHT.toPx() }
        val rowGapPx = with(density) { ROW_GAP.toPx() }
        val tapSlopPx = with(density) { 12.dp.toPx() }

        Canvas(
            Modifier
                .matchParentSize()
                .systemGestureExclusion()
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        val startPosition = currentPosition
                        var lastTarget = startPosition
                        var moved = false
                        var scrubbing = false
                        var totalDx = 0f
                        do {
                            val event = awaitPointerEvent()
                            val pressed = event.changes.filter { it.pressed }
                            if (pressed.size >= 2) {
                                // 두 손가락: 재생 위치를 고정한 채 확대·축소한다.
                                val zoom = event.calculateZoom()
                                if (zoom != 1f) {
                                    dpPerSecond = (dpPerSecond * zoom).coerceIn(MIN_DP_PER_SECOND, MAX_DP_PER_SECOND)
                                    moved = true
                                }
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            } else {
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                totalDx += change.position.x - change.previousPosition.x
                                if (!moved && abs(totalDx) > viewConfiguration.touchSlop) moved = true
                                if (moved && pressed.size == 1) {
                                    scrubbing = true
                                    val target = (startPosition - totalDx / currentPxPerUs).toLong().coerceIn(0, currentDuration)
                                    // 클립 경계나 처음·끝을 지날 때 한 번 진동해 손을 보지 않고도 위치를 알 수 있게 한다.
                                    val boundaries = currentClips.map { it.outputStartUs } + currentDuration
                                    val crossed = boundaries.any { b -> (lastTarget < b && target >= b) || (lastTarget > b && target <= b) }
                                    if (crossed && hapticsEnabled) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                    lastTarget = target
                                    currentScrub(target)
                                    change.consume()
                                }
                            }
                        } while (event.changes.any { it.pressed })
                        if (scrubbing) currentScrubEnd()
                        if (!moved) {
                            val timeUs = (currentPosition + (down.position.x - center) / currentPxPerUs).toLong()
                            val y = down.position.y
                            if (y <= stripPx) {
                                currentClips.firstOrNull { timeUs >= it.outputStartUs && timeUs < it.outputStartUs + it.outputDurationUs }
                                    ?.let { currentSelectClip(it.id) }
                            } else {
                                val row = ((y - stripPx) / (rowPx + rowGapPx)).toInt()
                                val kind = currentRows.getOrNull(row)
                                // 짧은 항목도 누를 수 있도록 좌우로 손가락 반 폭만큼 여유를 둔다.
                                val slopUs = (tapSlopPx / currentPxPerUs).toLong()
                                currentItems.lastOrNull { it.kind == kind && timeUs >= it.range.startUs - slopUs && timeUs < it.range.endExclusiveUs + slopUs }
                                    ?.let { currentSelectItem(it.kind, it.id) }
                            }
                        }
                    }
                },
        ) {
            val radius = CornerRadius(6.dp.toPx())
            val gap = 2.dp.toPx()
            fun x(timeUs: Long) = center + (timeUs - positionUs) * pxPerUs

            // 클립 띠
            clips.forEach { clip ->
                val left = x(clip.outputStartUs) + gap / 2
                val right = x(clip.outputStartUs + clip.outputDurationUs) - gap / 2
                if (right < 0 || left > size.width) return@forEach
                drawRoundRect(colors.surface, Offset(left, 0f), Size(right - left, stripPx), radius)
                clipRect(left, 0f, right, stripPx) {
                    tiles.filter { it.clipId == clip.id }.forEach { tile ->
                        val bitmap = frames[tile.key]?.takeIf { !it.isRecycled } ?: return@forEach
                        drawImage(
                            bitmap.asImageBitmap(),
                            dstOffset = IntOffset(tile.left.roundToInt(), 0),
                            dstSize = IntSize(tile.width.roundToInt().coerceAtLeast(1), stripPx.roundToInt()),
                        )
                    }
                }
                if (clip.id == selectedClipId && clips.size > 1) {
                    drawRoundRect(colors.accent, Offset(left, 0f), Size(right - left, stripPx), radius, style = Stroke(2.5.dp.toPx()))
                }
            }

            // 구간 항목 줄
            rows.forEachIndexed { row, kind ->
                val top = stripPx + rowGapPx + row * (rowPx + rowGapPx)
                val color = when (kind) {
                    TimelineItemKind.OVERLAY -> colors.accent.copy(alpha = 0.75f)
                    TimelineItemKind.MASK -> Color(0xFF8E8E99)
                    TimelineItemKind.MUSIC -> Color(0xFF2EB67D)
                }
                items.filter { it.kind == kind }.forEach { item ->
                    val left = x(item.range.startUs).coerceAtLeast(-10f)
                    val right = x(item.range.endExclusiveUs).coerceAtMost(size.width + 10f)
                    if (right <= left) return@forEach
                    drawRoundRect(color, Offset(left, top), Size(right - left, rowPx), CornerRadius(rowPx / 2))
                    if (item.selected) drawRoundRect(Color.White, Offset(left, top), Size(right - left, rowPx), CornerRadius(rowPx / 2), style = Stroke(1.5.dp.toPx()))
                }
            }

            // 결과 바깥은 어둡게 보여 끝을 알 수 있게 한다.
            val start = x(0)
            val end = x(durationUs)
            if (start > 0) drawRect(Color.Black.copy(alpha = 0.25f), Offset.Zero, Size(start, size.height))
            if (end < size.width) drawRect(Color.Black.copy(alpha = 0.25f), Offset(end, 0f), Size(size.width - end, size.height))

            drawLine(Color.Black.copy(alpha = 0.5f), Offset(center, -4.dp.toPx()), Offset(center, size.height + 4.dp.toPx()), 4.dp.toPx())
            drawLine(Color.White, Offset(center, -4.dp.toPx()), Offset(center, size.height + 4.dp.toPx()), 2.dp.toPx())
        }
    }
}

private data class Tile(val key: String, val clipId: String, val source: SourceId, val sourceTimeUs: Long, val left: Float, val width: Float)

private fun visibleTiles(clips: List<TimelineClip>, positionUs: Long, pxPerUs: Float, center: Float, width: Float, stripPx: Float): List<Tile> =
    clips.flatMap { clip ->
        val left = center + (clip.outputStartUs - positionUs) * pxPerUs
        val clipWidth = clip.outputDurationUs * pxPerUs
        if (left + clipWidth < 0 || left > width) return@flatMap emptyList()
        val tileWidth = (stripPx * clip.frameAspect).coerceAtLeast(8f)
        val first = floor((-left / tileWidth).coerceAtLeast(0f)).toInt()
        val last = ceil(((width - left) / tileWidth).coerceAtMost(clipWidth / tileWidth)).toInt()
        (first until last.coerceAtLeast(first)).map { index ->
            val outputOffset = ((index + 0.5f) * tileWidth / pxPerUs).toLong().coerceIn(0, clip.outputDurationUs)
            val sourceTime = clip.sourceRange.startUs + (outputOffset * clip.speed).toLong()
            // 같은 원본의 가까운 시간은 같은 칸으로 묶어 확대·스크롤 중에도 다시 읽지 않게 한다.
            val bucket = sourceTime / BUCKET_US * BUCKET_US
            Tile("${clip.source.value}/$bucket", clip.id, clip.source, bucket, left + index * tileWidth, tileWidth)
        }
    }

private val STRIP_HEIGHT = 56.dp
private val ROW_HEIGHT = 14.dp
private val ROW_GAP = 8.dp
private const val MAX_FRAME_PX = 160
private const val BUCKET_US = 250_000L
private const val MAX_KEPT_FRAMES = 120
private const val MIN_DP_PER_SECOND = 8f
private const val DEFAULT_DP_PER_SECOND = 40f
private const val MAX_DP_PER_SECOND = 240f
