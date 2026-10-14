package com.naury.framekit.ui.image.tool

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.naury.framekit.core.overlay.BrushKind
import com.naury.framekit.core.overlay.ShapeKind
import com.naury.framekit.ui.component.ChoiceChips
import com.naury.framekit.ui.component.ValueSlider
import com.naury.framekit.ui.component.horizontalFadingEdges
import com.naury.framekit.ui.design.FrameKitTheme
import com.naury.framekit.ui.image.R
import com.naury.framekit.ui.image.editor.BrushSettings
import com.naury.framekit.ui.image.editor.ShapeFill
import com.naury.framekit.ui.tool.ColorPalette
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 그리기 도구의 브러시·도형, 색상, 굵기, 불투명도. 획은 캔버스에 한 손가락으로 긋는다.
 *
 * 도형을 고르면 캔버스의 빈 곳을 끌어 도형을 그리고, 그린 도형을 눌러 옮기거나 크기·회전을 바꾼다.
 * 도형을 고른 채 색·굵기 등을 바꾸면 그 도형에 바로 적용된다.
 *
 * @param onBrush 첫 인자가 `false`면 슬라이더를 끄는 중이라 [onBrushFinished]가 불릴 때 커밋한다.
 */
@Composable
internal fun DrawToolPanel(
    brush: BrushSettings,
    onBrush: (Boolean, (BrushSettings) -> BrushSettings) -> Unit,
    onBrushFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        // 도형 모드는 브러시 종류와 함께 두되(null이 도형), 좁은 화면에서도 보이게 지우개 앞에 둔다.
        ChoiceChips(
            options = BrushKind.entries.filter { it != BrushKind.ERASER } + listOf(null, BrushKind.ERASER),
            selected = if (brush.shapes) null else brush.kind,
            label = { stringResource(it?.label() ?: R.string.framekit_draw_shapes) },
            onSelect = { kind -> onBrush(true) { if (kind == null) it.copy(shapes = true) else it.copy(shapes = false, kind = kind) } },
        )
        if (brush.shapes) {
            ShapeKindRow(selected = brush.shape, onSelect = { kind -> onBrush(true) { it.copy(shape = kind) } })
            val fills = if (brush.shape.isLine) listOf(ShapeFill.OUTLINE, ShapeFill.DASHED) else ShapeFill.entries
            ChoiceChips(
                options = fills,
                selected = brush.shapeFill.takeIf { it in fills } ?: ShapeFill.OUTLINE,
                label = { stringResource(it.label()) },
                onSelect = { fill -> onBrush(true) { it.copy(shapeFill = fill) } },
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        if (brush.shapes || brush.kind != BrushKind.ERASER) {
            ColorPalette(selected = brush.colorArgb, onSelect = { argb -> onBrush(true) { it.copy(colorArgb = argb) } }, modifier = Modifier.padding(top = 8.dp))
        }
        // 채운 상자 도형은 선을 긋지 않아 굵기가 의미 없다.
        if (!(brush.shapes && brush.shapeFill == ShapeFill.FILLED && !brush.shape.isLine)) {
            ValueSlider(
                label = stringResource(R.string.framekit_brush_width),
                value = widthToDisplay(brush.widthShortEdgeRatio),
                valueRange = 1f..100f,
                onValueChange = { value -> onBrush(false) { it.copy(widthShortEdgeRatio = displayToWidth(value)) } },
                onValueChangeFinished = onBrushFinished,
                formatValue = { it.roundToInt().toString() },
                resetValue = widthToDisplay(BrushSettings().widthShortEdgeRatio),
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        if (brush.shapes || brush.kind != BrushKind.ERASER) {
            ValueSlider(
                label = stringResource(R.string.framekit_brush_opacity),
                value = (brush.opacity * 100).toFloat(),
                valueRange = 10f..100f,
                onValueChange = { value -> onBrush(false) { it.copy(opacity = value / 100.0) } },
                onValueChangeFinished = onBrushFinished,
                formatValue = { "${it.roundToInt()}%" },
                resetValue = 100f,
            )
        }
        if (brush.shapes) {
            Text(
                stringResource(R.string.framekit_draw_shapes_help),
                color = FrameKitTheme.colors.foregroundMuted,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun ShapeKindRow(selected: ShapeKind, onSelect: (ShapeKind) -> Unit) {
    val listState = rememberLazyListState()
    LazyRow(
        state = listState,
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.padding(top = 10.dp).horizontalFadingEdges(listState).selectableGroup(),
    ) {
        items(ShapeKind.entries) { kind -> ShapeKindItem(kind, kind == selected) { onSelect(kind) } }
    }
}

@Composable
private fun ShapeKindItem(kind: ShapeKind, selected: Boolean, onClick: () -> Unit) {
    val colors = FrameKitTheme.colors
    val ring by animateColorAsState(if (selected) colors.foreground.copy(alpha = 0.85f) else colors.raised, label = "shape-ring")
    val glyph = if (selected) colors.foreground else colors.foregroundMuted
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(64.dp)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .padding(vertical = 4.dp),
    ) {
        Box(Modifier.size(52.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(52.dp)) {
                val ringWidth = 2.5.dp.toPx()
                drawCircle(ring, size.minDimension / 2f - ringWidth / 2, style = Stroke(ringWidth))
                drawShapeGlyph(kind, glyph)
            }
        }
        Text(
            stringResource(kind.label()),
            color = glyph,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

// 고리 안쪽 가운데에 도형 종류를 작게 그린다.
private fun DrawScope.drawShapeGlyph(kind: ShapeKind, color: Color) {
    val unit = size.minDimension
    val half = unit * 0.2f
    val cx = size.width / 2
    val cy = size.height / 2
    val line = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
    val left = cx - half
    val top = cy - half * 0.8f
    val boxSize = androidx.compose.ui.geometry.Size(half * 2, half * 1.6f)
    when (kind) {
        ShapeKind.RECTANGLE -> drawRect(color, Offset(left, top), boxSize, style = line)
        ShapeKind.ROUNDED_RECTANGLE -> drawRoundRect(color, Offset(left, top), boxSize, androidx.compose.ui.geometry.CornerRadius(half * 0.45f), style = line)
        ShapeKind.ELLIPSE -> drawCircle(color, half, Offset(cx, cy), style = line)
        ShapeKind.TRIANGLE -> drawPath(
            Path().apply {
                moveTo(cx, cy - half)
                lineTo(cx + half, cy + half * 0.8f)
                lineTo(cx - half, cy + half * 0.8f)
                close()
            },
            color,
            style = line,
        )
        ShapeKind.STAR -> drawPath(
            Path().apply {
                for (i in 0 until 10) {
                    val r = if (i % 2 == 0) half * 1.1f else half * 0.48f
                    val angle = Math.toRadians(-90.0 + i * 36.0)
                    val x = cx + (cos(angle) * r).toFloat()
                    val y = cy + (sin(angle) * r).toFloat() + half * 0.08f
                    if (i == 0) moveTo(x, y) else lineTo(x, y)
                }
                close()
            },
            color,
            style = line,
        )
        ShapeKind.LINE, ShapeKind.ARROW, ShapeKind.DOUBLE_ARROW -> {
            val start = Offset(cx - half, cy + half)
            val end = Offset(cx + half, cy - half)
            drawLine(color, start, end, strokeWidth = line.width, cap = StrokeCap.Round)
            val head = half * 0.6f
            fun arrowHead(tip: Offset, dx: Float, dy: Float) {
                drawPath(
                    Path().apply {
                        moveTo(tip.x, tip.y)
                        lineTo(tip.x - dx * head, tip.y + dy * head * 0.15f)
                        lineTo(tip.x - dx * head * 0.15f, tip.y + dy * head)
                        close()
                    },
                    color,
                    style = Fill,
                )
            }
            if (kind != ShapeKind.LINE) arrowHead(end, 1f, 1f)
            if (kind == ShapeKind.DOUBLE_ARROW) arrowHead(start, -1f, -1f)
        }
    }
}

// 굵기는 짧은 변 대비 0.2%~5%를 1~100에 대응시킨다.
private fun widthToDisplay(ratio: Double): Float = (((ratio - MIN_WIDTH) / (MAX_WIDTH - MIN_WIDTH)) * 99 + 1).toFloat().coerceIn(1f, 100f)

private fun displayToWidth(display: Float): Double = MIN_WIDTH + (display - 1) / 99.0 * (MAX_WIDTH - MIN_WIDTH)

private const val MIN_WIDTH = 0.002
private const val MAX_WIDTH = 0.05

private fun BrushKind.label(): Int = when (this) {
    BrushKind.PEN -> R.string.framekit_brush_pen
    BrushKind.MARKER -> R.string.framekit_brush_marker
    BrushKind.HIGHLIGHTER -> R.string.framekit_brush_highlighter
    BrushKind.ERASER -> R.string.framekit_brush_eraser
}

private fun ShapeKind.label(): Int = when (this) {
    ShapeKind.RECTANGLE -> R.string.framekit_shape_rectangle
    ShapeKind.ROUNDED_RECTANGLE -> R.string.framekit_shape_rounded
    ShapeKind.ELLIPSE -> R.string.framekit_shape_ellipse
    ShapeKind.TRIANGLE -> R.string.framekit_shape_triangle
    ShapeKind.STAR -> R.string.framekit_shape_star
    ShapeKind.LINE -> R.string.framekit_shape_line
    ShapeKind.ARROW -> R.string.framekit_shape_arrow
    ShapeKind.DOUBLE_ARROW -> R.string.framekit_shape_double_arrow
}

private fun ShapeFill.label(): Int = when (this) {
    ShapeFill.OUTLINE -> R.string.framekit_shape_outline
    ShapeFill.DASHED -> R.string.framekit_shape_dashed
    ShapeFill.FILLED -> R.string.framekit_shape_filled
}
