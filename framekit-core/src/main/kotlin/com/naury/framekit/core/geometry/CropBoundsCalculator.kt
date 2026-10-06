package com.naury.framekit.core.geometry

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Computes crop rectangles that stay inside the rotated image area. */
public object CropBoundsCalculator {

    /** Smallest crop side in source pixels. Matches the 16 px minimum output size. */
    public const val MIN_CROP_SIDE_PX: Int = 16

    /**
     * Largest crop centered in G with the requested aspect that contains no empty corner.
     *
     * [CropAspectRatio.Free] and [CropAspectRatio.Original] both use the aspect of the image after
     * quarter turns, which removes exactly the triangles introduced by straighten.
     */
    public fun maxCrop(frame: GeometryFrame, aspect: CropAspectRatio): RectN =
        maxCropForPixelAspect(frame, resolvePixelAspect(frame, aspect))

    /** Pixel aspect for [aspect], or the rotated image aspect for free and original crops. */
    public fun resolvePixelAspect(frame: GeometryFrame, aspect: CropAspectRatio): Double = when (aspect) {
        CropAspectRatio.Free, CropAspectRatio.Original -> frame.rotatedSize.aspectRatio
        is CropAspectRatio.Fixed -> aspect.ratio
    }

    /** Largest centered crop whose width / height in pixels equals [pixelAspect]. */
    public fun maxCropForPixelAspect(frame: GeometryFrame, pixelAspect: Double): RectN {
        require(pixelAspect.isFinite() && pixelAspect > 0.0) { "Aspect must be positive: $pixelAspect" }
        val radians = Math.toRadians(frame.geometry.straightenDegrees)
        val c = abs(cos(radians))
        val s = abs(sin(radians))
        val rotated = frame.rotatedSize
        // 회전된 사각형 안에 중심을 맞춘 a:1 사각형이 들어가는 최대 높이.
        val height = min(
            rotated.width / (pixelAspect * c + s),
            rotated.height / (pixelAspect * s + c),
        )
        val width = pixelAspect * height
        val normalizedWidth = min(1.0, width / frame.bounds.width)
        val normalizedHeight = min(1.0, height / frame.bounds.height)
        return RectN.fromCenter(0.5, 0.5, normalizedWidth, normalizedHeight).clampToUnit()
    }

    /**
     * Returns [crop] when it is valid, otherwise the closest rectangle with the same aspect that
     * stays inside the image area, found by moving toward the largest centered crop.
     */
    public fun clampCrop(frame: GeometryFrame, crop: RectN): RectN {
        val unit = crop.clampToUnit()
        if (unit.width > 0.0 && unit.height > 0.0 && frame.containsRect(unit)) return unit
        val aspect = frame.pixelAspect(unit).takeIf { it.isFinite() && it > 0.0 } ?: frame.rotatedSize.aspectRatio
        val target = maxCropForPixelAspect(frame, aspect)
        return constrainBetween(frame, valid = target, candidate = unit)
    }

    /**
     * Point on the straight path from [valid] to [candidate] that is closest to [candidate] while
     * staying inside the image area.
     *
     * The image area is convex and each corner moves on a straight line, so the valid part of the
     * path is a single interval starting at [valid].
     */
    public fun constrainBetween(frame: GeometryFrame, valid: RectN, candidate: RectN): RectN {
        if (frame.containsRect(candidate)) return candidate
        var low = 0.0
        var high = 1.0
        repeat(BINARY_SEARCH_STEPS) {
            val mid = (low + high) / 2.0
            if (frame.containsRect(lerp(valid, candidate, mid))) low = mid else high = mid
        }
        return lerp(valid, candidate, low)
    }

    /** `true` when [crop] is inside the image area and at least [MIN_CROP_SIDE_PX] on each side. */
    public fun isValidCrop(frame: GeometryFrame, crop: RectN): Boolean =
        crop.isValidUnitRect && frame.containsRect(crop) && meetsMinimumSize(frame, crop)

    public fun meetsMinimumSize(frame: GeometryFrame, crop: RectN): Boolean {
        val size = frame.cropPixelSize(crop)
        return size.width + SIZE_EPSILON_PX >= minimumSide(frame) &&
            size.height + SIZE_EPSILON_PX >= minimumSide(frame)
    }

    /** Minimum crop side, reduced for sources smaller than [MIN_CROP_SIDE_PX]. */
    public fun minimumSide(frame: GeometryFrame): Double =
        min(MIN_CROP_SIDE_PX.toDouble(), min(frame.rotatedSize.width, frame.rotatedSize.height))

    internal fun lerp(from: RectN, to: RectN, t: Double): RectN = RectN(
        from.left + (to.left - from.left) * t,
        from.top + (to.top - from.top) * t,
        from.right + (to.right - from.right) * t,
        from.bottom + (to.bottom - from.bottom) * t,
    )

    private fun RectN.clampToUnit(): RectN = RectN(
        max(0.0, left),
        max(0.0, top),
        min(1.0, right),
        min(1.0, bottom),
    )

    private const val BINARY_SEARCH_STEPS = 40
    private const val SIZE_EPSILON_PX = 1e-6
}
