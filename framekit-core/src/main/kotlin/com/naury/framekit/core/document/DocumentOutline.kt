package com.naury.framekit.core.document

import com.naury.framekit.core.geometry.PointN
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 휘거나 접힌 종이의 실제 테두리. 네 모서리([quad])와, 모서리 사이를 따라가는 네 변의 곡선을 담는다. 좌표는 정방향 이미지 기준
 * 정규화 값(`0..1`)이다.
 *
 * 각 곡선은 양 끝이 모서리이며 같은 수의 점을 순서대로 담는다.
 * - [top]: 왼쪽 위 → 오른쪽 위
 * - [bottom]: 왼쪽 아래 → 오른쪽 아래
 * - [left]: 왼쪽 위 → 왼쪽 아래
 * - [right]: 오른쪽 위 → 오른쪽 아래
 *
 * 네 곡선으로 둘러싼 면을 [point]로 펼치면(Coons 면), 휜 종이를 직선 사각형으로 편 것보다 반듯하게 펼 수 있다.
 */
public data class DocumentOutline(
    val quad: DocumentQuad,
    val top: List<PointN>,
    val right: List<PointN>,
    val bottom: List<PointN>,
    val left: List<PointN>,
) {
    init {
        require(listOf(top, right, bottom, left).all { it.size >= 2 }) { "Each side needs at least two points" }
    }

    /** 어느 변이든 직선에서 [CURVE_EPSILON](정규화)보다 벗어나면 `true`. 아니면 직선 사각형으로 펴도 같다. */
    public val isCurved: Boolean
        get() = listOf(top, right, bottom, left).any { side ->
            val a = side.first()
            val b = side.last()
            val length = hypot(b.x - a.x, b.y - a.y)
            length > 0 && side.any { p -> abs((p.x - a.x) * (b.y - a.y) - (p.y - a.y) * (b.x - a.x)) / length > CURVE_EPSILON }
        }

    /**
     * 펼친 종이의 (u, v) 위치(`0..1`, 왼쪽 위가 원점)가 원본 이미지에서 놓인 곳. 네 변 곡선을 섞는 Coons 면이다.
     */
    public fun point(u: Double, v: Double): PointN {
        val t = along(top, u)
        val b = along(bottom, u)
        val l = along(left, v)
        val r = along(right, v)
        val tl = quad.topLeft
        val tr = quad.topRight
        val br = quad.bottomRight
        val bl = quad.bottomLeft
        val x = (1 - v) * t.x + v * b.x + (1 - u) * l.x + u * r.x -
            ((1 - u) * (1 - v) * tl.x + u * (1 - v) * tr.x + (1 - u) * v * bl.x + u * v * br.x)
        val y = (1 - v) * t.y + v * b.y + (1 - u) * l.y + u * r.y -
            ((1 - u) * (1 - v) * tl.y + u * (1 - v) * tr.y + (1 - u) * v * bl.y + u * v * br.y)
        return PointN(x, y)
    }

    /**
     * 펴낸 문서의 픽셀 크기. 마주 보는 두 곡선 중 긴 쪽의 길이를 쓴다(휜 종이는 직선 거리보다 길다).
     *
     * @param imageWidth 정방향 원본 폭(px).
     * @param imageHeight 정방향 원본 높이(px).
     */
    public fun rectifiedSize(imageWidth: Int, imageHeight: Int): Pair<Int, Int> {
        fun length(side: List<PointN>) = side.zipWithNext().sumOf { (a, b) -> hypot((a.x - b.x) * imageWidth, (a.y - b.y) * imageHeight) }
        val width = max(length(top), length(bottom))
        val height = max(length(left), length(right))
        return max(1, width.roundToInt()) to max(1, height.roundToInt())
    }

    public companion object {
        /** 직선과 다르다고 볼 최소 벗어남(정규화). */
        public const val CURVE_EPSILON: Double = 0.004

        /** [quad]의 네 변을 직선으로 둔 테두리. */
        public fun straight(quad: DocumentQuad, samples: Int = 2): DocumentOutline {
            fun line(a: PointN, b: PointN) = List(samples) { i ->
                val s = i.toDouble() / (samples - 1)
                PointN(a.x + (b.x - a.x) * s, a.y + (b.y - a.y) * s)
            }
            return DocumentOutline(quad, line(quad.topLeft, quad.topRight), line(quad.topRight, quad.bottomRight), line(quad.bottomLeft, quad.bottomRight), line(quad.topLeft, quad.bottomLeft))
        }

        // 곡선 위의 위치 s(`0..1`, 점 번호 기준으로 고르게 나눔)를 앞뒤 점 사이에서 선형으로 구한다.
        private fun along(side: List<PointN>, s: Double): PointN {
            val f = s.coerceIn(0.0, 1.0) * (side.size - 1)
            val i = f.toInt().coerceAtMost(side.size - 2)
            val k = f - i
            val a = side[i]
            val b = side[i + 1]
            return PointN(a.x + (b.x - a.x) * k, a.y + (b.y - a.y) * k)
        }
    }
}
