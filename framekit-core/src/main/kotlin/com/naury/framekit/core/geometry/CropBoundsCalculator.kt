package com.naury.framekit.core.geometry

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** 회전된 이미지 영역 안에 머무는 자르기 사각형을 계산한다. */
public object CropBoundsCalculator {

    /** 원본 픽셀 기준 최소 자르기 변. 최소 출력 크기 16 px과 맞춘다. */
    public const val MIN_CROP_SIDE_PX: Int = 16

    /**
     * G 중앙에 놓이고 요청한 비율을 가지며 빈 모서리가 없는 가장 큰 자르기.
     *
     * [CropAspectRatio.Free]와 [CropAspectRatio.Original]은 둘 다 90° 회전 이후 이미지의 비율을 쓰며,
     * 이렇게 하면 수평 보정으로 생긴 삼각형만 정확히 잘려 나간다.
     */
    public fun maxCrop(frame: GeometryFrame, aspect: CropAspectRatio): RectN =
        maxCropForPixelAspect(frame, resolvePixelAspect(frame, aspect))

    /** [aspect]의 픽셀 비율. 자유·원본 자르기면 회전된 이미지의 비율이다. */
    public fun resolvePixelAspect(frame: GeometryFrame, aspect: CropAspectRatio): Double = when (aspect) {
        CropAspectRatio.Free, CropAspectRatio.Original -> frame.rotatedSize.aspectRatio
        is CropAspectRatio.Fixed -> aspect.ratio
    }

    /** 픽셀 기준 width / height가 [pixelAspect]와 같은 가장 큰 중앙 자르기. */
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
     * [crop]이 유효하면 그대로 반환하고, 아니면 가장 큰 중앙 자르기 쪽으로 이동해 찾은, 같은 비율로
     * 이미지 영역 안에 머무는 가장 가까운 사각형을 반환한다.
     */
    public fun clampCrop(frame: GeometryFrame, crop: RectN): RectN {
        val unit = crop.clampToUnit()
        if (unit.width > 0.0 && unit.height > 0.0 && frame.containsRect(unit)) return unit
        val aspect = frame.pixelAspect(unit).takeIf { it.isFinite() && it > 0.0 } ?: frame.rotatedSize.aspectRatio
        val target = maxCropForPixelAspect(frame, aspect)
        return constrainBetween(frame, valid = target, candidate = unit)
    }

    /**
     * [valid]에서 [candidate]로 가는 직선 경로 위에서 이미지 영역 안에 머물면서 [candidate]에 가장
     * 가까운 점.
     *
     * 이미지 영역은 볼록하고 각 모서리는 직선으로 움직이므로, 경로의 유효 구간은 [valid]에서 시작하는
     * 하나의 구간이다.
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

    /** [crop]이 이미지 영역 안에 있고 각 변이 [MIN_CROP_SIDE_PX] 이상이면 `true`. */
    public fun isValidCrop(frame: GeometryFrame, crop: RectN): Boolean =
        crop.isValidUnitRect && frame.containsRect(crop) && meetsMinimumSize(frame, crop)

    public fun meetsMinimumSize(frame: GeometryFrame, crop: RectN): Boolean {
        val size = frame.cropPixelSize(crop)
        return size.width + SIZE_EPSILON_PX >= minimumSide(frame) &&
            size.height + SIZE_EPSILON_PX >= minimumSide(frame)
    }

    /** 최소 자르기 변. 원본이 [MIN_CROP_SIDE_PX]보다 작으면 줄여서 쓴다. */
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
