package com.naury.framekit.ui.tool

import com.naury.framekit.core.geometry.PointN
import com.naury.framekit.core.overlay.OverlayTransform
import kotlin.math.abs
import kotlin.math.roundToInt

/** 텍스트·스티커를 옮기고 키우고 돌리는 제스처 계산. 사진·영상 편집기가 함께 쓴다. */
public object OverlayGestures {

    /**
     * 제스처 시작 시점의 변환인 [start]에 제스처를 적용한다.
     *
     * @param panX 정규화 캔버스 단위의 가로 이동량.
     * @param zoom 시작 이후의 확대 배율.
     * @param rotationDegrees 시작 이후의 회전 각도(도, 시계 방향).
     * @param snapDistanceX 중심이 캔버스 중앙으로 붙는 거리(정규화 단위).
     */
    public fun transform(
        start: OverlayTransform,
        panX: Double,
        panY: Double,
        zoom: Double,
        rotationDegrees: Double,
        snapDistanceX: Double,
        snapDistanceY: Double,
    ): OverlayTransform {
        var x = (start.center.x + panX).coerceIn(0.0, 1.0)
        var y = (start.center.y + panY).coerceIn(0.0, 1.0)
        if (abs(x - 0.5) <= snapDistanceX) x = 0.5
        if (abs(y - 0.5) <= snapDistanceY) y = 0.5
        val rotation = snapRotation(start.rotationDegrees + rotationDegrees)
        val scale = (start.scale * zoom).coerceIn(OverlayTransform.MIN_SCALE, OverlayTransform.MAX_SCALE)
        return start.copy(center = PointN(x, y), scale = scale, rotationDegrees = rotation)
    }

    /** ±3° 이내면 0, 90, 180, 270도로 붙이고 -180..180으로 정규화한다. */
    public fun snapRotation(degrees: Double): Double {
        var normalized = ((degrees % 360) + 540) % 360 - 180
        val nearest = (normalized / 90).roundToInt() * 90.0
        if (abs(normalized - nearest) <= ROTATION_SNAP_DEGREES) normalized = nearest
        return if (normalized == -180.0) 180.0 else normalized
    }

    private const val ROTATION_SNAP_DEGREES = 3.0
}
