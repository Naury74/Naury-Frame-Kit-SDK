package com.naury.framekit.core.document

import com.naury.framekit.core.geometry.PointN
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 사진 속 문서(종이·영수증·화이트보드)의 네 모서리. 좌표는 정방향 이미지 기준 정규화 값(`0..1`)이다.
 *
 * 원근 보정은 이 사각형을 반듯한 직사각형으로 펴서 비스듬히 찍은 문서를 정면에서 본 것처럼 만든다.
 */
public data class DocumentQuad(
    val topLeft: PointN,
    val topRight: PointN,
    val bottomRight: PointN,
    val bottomLeft: PointN,
) {
    /** 왼쪽 위부터 시계 방향 네 점. */
    public val corners: List<PointN> get() = listOf(topLeft, topRight, bottomRight, bottomLeft)

    /** 네 점이 모두 이미지 안에 있고, 볼록하며, 넓이가 [MIN_AREA] 이상이면 `true`. */
    public val isUsable: Boolean
        get() = corners.all { it.isFinite && it.x in 0.0..1.0 && it.y in 0.0..1.0 } && isConvex && area >= MIN_AREA

    /** 정규화 좌표 기준 넓이(이미지 전체가 1). */
    public val area: Double
        get() {
            val c = corners
            var sum = 0.0
            for (i in c.indices) {
                val a = c[i]
                val b = c[(i + 1) % c.size]
                sum += a.x * b.y - b.x * a.y
            }
            return abs(sum) / 2.0
        }

    private val isConvex: Boolean
        get() {
            val c = corners
            var sign = 0
            for (i in c.indices) {
                val a = c[i]
                val b = c[(i + 1) % 4]
                val d = c[(i + 2) % 4]
                val cross = (b.x - a.x) * (d.y - b.y) - (b.y - a.y) * (d.x - b.x)
                if (cross == 0.0) return false
                val s = if (cross > 0) 1 else -1
                if (sign == 0) sign = s else if (s != sign) return false
            }
            return true
        }

    /**
     * 펴낸 문서의 픽셀 크기. 마주 보는 두 변 중 긴 쪽을 쓰므로 원래 해상도를 거의 잃지 않는다.
     *
     * @param imageWidth 정방향 원본 폭(px).
     * @param imageHeight 정방향 원본 높이(px).
     */
    public fun rectifiedSize(imageWidth: Int, imageHeight: Int): Pair<Int, Int> {
        fun length(a: PointN, b: PointN) = hypot((a.x - b.x) * imageWidth, (a.y - b.y) * imageHeight)
        val width = max(length(topLeft, topRight), length(bottomLeft, bottomRight))
        val height = max(length(topLeft, bottomLeft), length(topRight, bottomRight))
        return max(1, width.roundToInt()) to max(1, height.roundToInt())
    }

    public companion object {
        /** 문서로 볼 최소 넓이(이미지의 5%). 그보다 작으면 잘못 찾은 것으로 본다. */
        public const val MIN_AREA: Double = 0.05

        /** 문서를 찾지 못했을 때 쓰는 기본 사각형. 가장자리에서 [inset]만큼 들어온 직사각형이다. */
        public fun inset(inset: Double = 0.08): DocumentQuad = DocumentQuad(
            PointN(inset, inset),
            PointN(1 - inset, inset),
            PointN(1 - inset, 1 - inset),
            PointN(inset, 1 - inset),
        )
    }
}
