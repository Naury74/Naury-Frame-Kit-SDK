package com.naury.framekit.ui.tool

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import com.naury.framekit.core.effect.AdjustmentKind
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * 보정 항목 아이콘. 이미지 리소스 없이 단순한 도형으로 그려 테마 색을 그대로 따른다.
 * [center]를 중심으로 지름 [diameter] 안에 그린다.
 */
internal fun DrawScope.drawAdjustmentGlyph(kind: AdjustmentKind, center: Offset, diameter: Float, color: Color) {
    val r = diameter / 2f
    val stroke = diameter * 0.09f
    val line = Stroke(stroke, cap = StrokeCap.Round)
    when (kind) {
        AdjustmentKind.BRIGHTNESS -> {
            // 해: 가운데 원과 여덟 개의 빛살
            drawCircle(color, r * 0.36f, center, style = line)
            repeat(8) { i ->
                val a = Math.PI * i / 4
                val inner = r * 0.62f
                val outer = r * 0.9f
                drawLine(color, center + polar(a, inner), center + polar(a, outer), stroke, StrokeCap.Round)
            }
        }
        AdjustmentKind.EXPOSURE -> {
            // 노출: 원을 대각선으로 나누고 +/− 표시
            drawCircle(color, r * 0.82f, center, style = line)
            drawLine(color, center + Offset(-r * 0.58f, r * 0.58f), center + Offset(r * 0.58f, -r * 0.58f), stroke)
            val m = r * 0.2f
            drawLine(color, center + Offset(-r * 0.36f - m, -r * 0.3f), center + Offset(-r * 0.36f + m, -r * 0.3f), stroke, StrokeCap.Round)
            drawLine(color, center + Offset(-r * 0.36f, -r * 0.3f - m), center + Offset(-r * 0.36f, -r * 0.3f + m), stroke, StrokeCap.Round)
            drawLine(color, center + Offset(r * 0.36f - m, r * 0.32f), center + Offset(r * 0.36f + m, r * 0.32f), stroke, StrokeCap.Round)
        }
        AdjustmentKind.CONTRAST -> {
            // 대비: 반쪽이 채워진 원
            drawCircle(color, r * 0.82f, center, style = line)
            drawArc(color, 90f, 180f, true, center - Offset(r * 0.82f, r * 0.82f), Size(r * 1.64f, r * 1.64f))
        }
        AdjustmentKind.HIGHLIGHTS -> {
            // 밝은 영역: 원 위쪽 절반을 채움
            drawCircle(color, r * 0.82f, center, style = line)
            drawArc(color, 180f, 180f, true, center - Offset(r * 0.82f, r * 0.82f), Size(r * 1.64f, r * 1.64f))
        }
        AdjustmentKind.SHADOWS -> {
            // 어두운 영역: 원 아래쪽 절반을 채움
            drawCircle(color, r * 0.82f, center, style = line)
            drawArc(color, 0f, 180f, true, center - Offset(r * 0.82f, r * 0.82f), Size(r * 1.64f, r * 1.64f))
        }
        AdjustmentKind.SATURATION -> {
            // 채도: 물방울
            drawPath(droplet(center, r * 0.85f), color, style = line)
            drawPath(droplet(center + Offset(0f, r * 0.25f), r * 0.45f), color)
        }
        AdjustmentKind.TEMPERATURE -> {
            // 색온도: 온도계
            val top = center + Offset(0f, -r * 0.8f)
            val bulb = center + Offset(0f, r * 0.5f)
            drawLine(color, top, bulb, stroke * 2.2f, StrokeCap.Round)
            drawCircle(color, r * 0.32f, bulb)
            drawLine(Color.Black.copy(alpha = 0.35f), top + Offset(0f, r * 0.1f), bulb, stroke * 0.8f, StrokeCap.Round)
        }
        AdjustmentKind.TINT -> {
            // 색조: 초록·자홍이 반씩 섞인 원
            drawCircle(color, r * 0.82f, center, style = line)
            drawArc(Brush.linearGradient(listOf(color, color.copy(alpha = 0.2f)), center - Offset(r, 0f), center + Offset(r, 0f)), 0f, 360f, true, center - Offset(r * 0.55f, r * 0.55f), Size(r * 1.1f, r * 1.1f))
        }
        AdjustmentKind.SHARPNESS -> {
            // 선명도: 삼각형
            val path = Path().apply {
                moveTo(center.x, center.y - r * 0.78f)
                lineTo(center.x + r * 0.78f, center.y + r * 0.6f)
                lineTo(center.x - r * 0.78f, center.y + r * 0.6f)
                close()
            }
            drawPath(path, color, style = line)
        }
        AdjustmentKind.FADE -> {
            // 페이드: 흐려지는 원
            drawCircle(Brush.verticalGradient(listOf(color, color.copy(alpha = 0.1f)), center.y - r, center.y + r), r * 0.8f, center)
        }
        AdjustmentKind.VIGNETTE -> {
            // 비네트: 모서리가 어두운 사각형
            val rect = Rect(center - Offset(r * 0.85f, r * 0.68f), Size(r * 1.7f, r * 1.36f))
            drawRect(color, rect.topLeft, rect.size, style = line)
            drawOval(color, rect.topLeft + Offset(r * 0.3f, r * 0.25f), Size(rect.width - r * 0.6f, rect.height - r * 0.5f), style = Stroke(stroke * 0.8f))
        }
        AdjustmentKind.GRAIN -> {
            // 그레인: 흩어진 점
            val dots = listOf(-0.5f to -0.5f, 0.1f to -0.6f, 0.6f to -0.2f, -0.2f to 0f, 0.35f to 0.35f, -0.6f to 0.4f, 0.0f to 0.65f)
            dots.forEach { (dx, dy) -> drawCircle(color, stroke * 0.9f, center + Offset(dx * r, dy * r)) }
        }
    }
}

private fun polar(angle: Double, radius: Float) = Offset((cos(angle) * radius).toFloat(), (sin(angle) * radius).toFloat())

private fun droplet(center: Offset, r: Float): Path = Path().apply {
    val bottom = center + Offset(0f, r * 0.55f)
    val radius = min(r * 0.6f, r)
    moveTo(center.x, center.y - r)
    cubicTo(center.x + r * 0.2f, center.y - r * 0.55f, bottom.x + radius, bottom.y - radius * 0.6f, bottom.x + radius, bottom.y - radius * 0.1f)
    cubicTo(bottom.x + radius, bottom.y + radius * 0.6f, bottom.x - radius, bottom.y + radius * 0.6f, bottom.x - radius, bottom.y - radius * 0.1f)
    cubicTo(bottom.x - radius, bottom.y - radius * 0.6f, center.x - r * 0.2f, center.y - r * 0.55f, center.x, center.y - r)
    close()
}
