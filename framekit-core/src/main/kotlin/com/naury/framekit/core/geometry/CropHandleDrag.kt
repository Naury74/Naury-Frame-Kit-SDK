package com.naury.framekit.core.geometry

/** Part of the crop frame that a pointer grabbed. */
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

/** Resizes or moves a crop rectangle in response to a drag, keeping it valid. */
public object CropHandleDrag {

    /**
     * Returns the crop after dragging [handle] by ([dx], [dy]) from the gesture start.
     *
     * @param start crop when the gesture began. It must be valid; it is the fallback position when
     *   the pointer leaves the image area.
     * @param dx total horizontal movement in normalized G units.
     * @param dy total vertical movement in normalized G units.
     * @param pixelAspect locked width / height ratio in pixels, or `null` for a free crop.
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
