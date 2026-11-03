package com.naury.framekit.core.geometry

/** 포인터가 잡은 자르기 프레임의 부분. */
public enum class CropHandle(
    internal val movesLeft: Boolean,
    internal val movesTop: Boolean,
    internal val movesRight: Boolean,
    internal val movesBottom: Boolean,
) {
    TOP_LEFT(true, true, false, false),
    TOP(false, true, false, false),
    TOP_RIGHT(false, true, true, false),
    RIGHT(false, false, true, false),
    BOTTOM_RIGHT(false, false, true, true),
    BOTTOM(false, false, false, true),
    BOTTOM_LEFT(true, false, false, true),
    LEFT(true, false, false, false),
    MOVE(true, true, true, true),
}

/** 드래그에 따라 자르기 사각형의 크기를 바꾸거나 이동하며, 항상 유효하게 유지한다. */
public object CropHandleDrag {

    /**
     * 제스처 시작점부터 [handle]을 ([dx], [dy])만큼 드래그한 뒤의 자르기를 반환한다.
     *
     * @param start 제스처가 시작될 때의 자르기. 유효해야 하며, 포인터가 이미지 영역을 벗어나면
     *   대체 위치로 쓰인다.
     * @param dx 정규화된 G 단위의 총 가로 이동량.
     * @param dy 정규화된 G 단위의 총 세로 이동량.
     * @param pixelAspect 픽셀 기준으로 고정된 width / height 비율. 자유 자르기면 `null`.
     */
    public fun drag(
        frame: GeometryFrame,
        start: RectN,
        handle: CropHandle,
        dx: Double,
        dy: Double,
        pixelAspect: Double?,
    ): RectN {
        if (handle == CropHandle.MOVE) return move(frame, start, dx, dy)

        val minWidth = CropBoundsCalculator.minimumSide(frame) / frame.bounds.width
        val minHeight = CropBoundsCalculator.minimumSide(frame) / frame.bounds.height
        var left = if (handle.movesLeft) start.left + dx else start.left
        var top = if (handle.movesTop) start.top + dy else start.top
        var right = if (handle.movesRight) start.right + dx else start.right
        var bottom = if (handle.movesBottom) start.bottom + dy else start.bottom

        var width = maxOf(right - left, minWidth)
        var height = maxOf(bottom - top, minHeight)
        if (pixelAspect != null) {
            val normalizedAspect = pixelAspect * frame.bounds.height / frame.bounds.width
            val horizontal = handle.movesLeft || handle.movesRight
            val vertical = handle.movesTop || handle.movesBottom
            when {
                horizontal && vertical -> if (width / normalizedAspect >= height) {
                    height = width / normalizedAspect
                } else {
                    width = height * normalizedAspect
                }
                horizontal -> height = width / normalizedAspect
                else -> width = height * normalizedAspect
            }
            if (width < minWidth) {
                width = minWidth
                height = width / normalizedAspect
            }
            if (height < minHeight) {
                height = minHeight
                width = height * normalizedAspect
            }
            if (!handle.movesTop && !handle.movesBottom) {
                top = start.centerY - height / 2.0
                bottom = start.centerY + height / 2.0
            }
            if (!handle.movesLeft && !handle.movesRight) {
                left = start.centerX - width / 2.0
                right = start.centerX + width / 2.0
            }
        }
        if (handle.movesLeft) left = right - width else if (handle.movesRight) right = left + width
        if (handle.movesTop) top = bottom - height else if (handle.movesBottom) bottom = top + height

        return CropBoundsCalculator.constrainBetween(frame, valid = start, candidate = RectN(left, top, right, bottom))
    }

    // 가장자리에 닿으면 막힌 축만 멈추고 다른 축으로는 계속 미끄러지게 한다.
    private fun move(frame: GeometryFrame, start: RectN, dx: Double, dy: Double): RectN {
        val horizontal = CropBoundsCalculator.constrainBetween(frame, start, start.offset(dx, 0.0))
        return CropBoundsCalculator.constrainBetween(frame, horizontal, horizontal.offset(0.0, dy))
    }

    private fun RectN.offset(dx: Double, dy: Double) = RectN(left + dx, top + dy, right + dx, bottom + dy)
}
