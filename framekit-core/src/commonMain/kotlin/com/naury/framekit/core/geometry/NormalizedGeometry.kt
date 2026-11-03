package com.naury.framekit.core.geometry

/**
 * 정규화 좌표 공간의 점. `0.0..1.0`이 공간의 너비와 높이에 해당한다.
 *
 * 공간(S, G, C)은 점 자체가 아니라 그 점을 담는 프로퍼티가 정한다.
 */
public data class PointN(val x: Double, val y: Double) {
    public val isFinite: Boolean get() = x.isFinite() && y.isFinite()
}

/**
 * 정규화 좌표 공간의 축 정렬 사각형.
 *
 * 유효한 사각형은 `0 <= left < right <= 1`이고 `0 <= top < bottom <= 1`이다. 호스트 입력을 검증
 * 단계에서 명확한 오류로 거부할 수 있도록 생성 시에는 이를 강제하지 않는다.
 */
public data class RectN(val left: Double, val top: Double, val right: Double, val bottom: Double) {
    public val width: Double get() = right - left
    public val height: Double get() = bottom - top
    public val centerX: Double get() = (left + right) / 2.0
    public val centerY: Double get() = (top + bottom) / 2.0

    public val isFinite: Boolean
        get() = left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite()

    /** 사각형이 유한하고 비어 있지 않으며 단위 정사각형 안에 있으면 `true`. */
    public val isValidUnitRect: Boolean
        get() = isFinite && left >= 0.0 && top >= 0.0 && right <= 1.0 && bottom <= 1.0 &&
            left < right && top < bottom

    public fun corners(): List<PointN> = listOf(
        PointN(left, top),
        PointN(right, top),
        PointN(right, bottom),
        PointN(left, bottom),
    )

    public companion object {
        /** 공간 전체. */
        public val Full: RectN = RectN(0.0, 0.0, 1.0, 1.0)

        public fun fromCenter(centerX: Double, centerY: Double, width: Double, height: Double): RectN =
            RectN(centerX - width / 2.0, centerY - height / 2.0, centerX + width / 2.0, centerY + height / 2.0)

        /**
         * 두 점을 대각선 꼭짓점으로 하는 단위 정사각형 안의 사각형. 두 점이 같거나 한 축으로만 떨어져 있어도
         * 가로·세로가 최소 [minSize]가 되도록 넓혀, 끌기 도중의 사각형이 "비어 있음"으로 검증에 걸리지 않게 한다.
         *
         * @param minSize 정규화 단위 최소 변 길이, `0 < minSize <= 1`.
         */
        public fun spanning(a: PointN, b: PointN, minSize: Double = MIN_SPAN): RectN {
            require(minSize > 0.0 && minSize <= 1.0) { "minSize must be in (0, 1]" }
            fun axis(p: Double, q: Double): Pair<Double, Double> {
                var low = minOf(p, q).coerceIn(0.0, 1.0)
                var high = maxOf(p, q).coerceIn(0.0, 1.0)
                if (high - low < minSize) {
                    high = (low + minSize).coerceAtMost(1.0)
                    low = high - minSize
                }
                return low to high
            }
            val (left, right) = axis(a.x, b.x)
            val (top, bottom) = axis(a.y, b.y)
            return RectN(left, top, right, bottom)
        }

        /** [spanning]의 기본 최소 변 길이. 화면에서는 보이지 않을 만큼 작다. */
        public const val MIN_SPAN: Double = 1e-4
    }
}
