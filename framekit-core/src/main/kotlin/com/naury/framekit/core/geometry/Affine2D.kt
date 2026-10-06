package com.naury.framekit.core.geometry

import kotlin.math.cos
import kotlin.math.sin

/**
 * 열 벡터 기준 2D 아핀 변환.
 *
 * ```
 * | scaleX  skewX   translateX |
 * | skewY   scaleY  translateY |
 * | 0       0       1          |
 * ```
 *
 * 배치가 `android.graphics.Matrix`와 같으므로 어댑터가 여섯 값을 그대로 복사할 수 있다.
 * `a * b`는 `b`를 먼저, 그다음 `a`를 적용한다. 모든 회전은 y가 아래로 향하는 화면 좌표에서 시계 방향이다.
 */
public data class Affine2D(
    val scaleX: Double,
    val skewX: Double,
    val translateX: Double,
    val skewY: Double,
    val scaleY: Double,
    val translateY: Double,
) {
    public val determinant: Double get() = scaleX * scaleY - skewX * skewY

    public operator fun times(other: Affine2D): Affine2D = Affine2D(
        scaleX = scaleX * other.scaleX + skewX * other.skewY,
        skewX = scaleX * other.skewX + skewX * other.scaleY,
        translateX = scaleX * other.translateX + skewX * other.translateY + translateX,
        skewY = skewY * other.scaleX + scaleY * other.skewY,
        scaleY = skewY * other.skewX + scaleY * other.scaleY,
        translateY = skewY * other.translateX + scaleY * other.translateY + translateY,
    )

    /** 점을 변환한다. 결과 단위는 변환이 만들어내는 단위를 따른다. */
    public fun map(x: Double, y: Double): PointN = PointN(
        scaleX * x + skewX * y + translateX,
        skewY * x + scaleY * y + translateY,
    )

    public fun map(point: PointN): PointN = map(point.x, point.y)

    /**
     * 역변환을 반환한다.
     *
     * @throws IllegalStateException 변환이 특이(singular)일 때.
     */
    public fun inverted(): Affine2D {
        val det = determinant
        check(det != 0.0 && det.isFinite()) { "Transform is not invertible" }
        val invScaleX = scaleY / det
        val invSkewX = -skewX / det
        val invSkewY = -skewY / det
        val invScaleY = scaleX / det
        return Affine2D(
            scaleX = invScaleX,
            skewX = invSkewX,
            translateX = -(invScaleX * translateX + invSkewX * translateY),
            skewY = invSkewY,
            scaleY = invScaleY,
            translateY = -(invSkewY * translateX + invScaleY * translateY),
        )
    }

    public companion object {
        public val Identity: Affine2D = Affine2D(1.0, 0.0, 0.0, 0.0, 1.0, 0.0)

        public fun translate(dx: Double, dy: Double): Affine2D = Affine2D(1.0, 0.0, dx, 0.0, 1.0, dy)

        public fun scale(sx: Double, sy: Double = sx): Affine2D = Affine2D(sx, 0.0, 0.0, 0.0, sy, 0.0)

        /** 원점 기준 시계 방향 회전. */
        public fun rotate(degrees: Double): Affine2D {
            val radians = Math.toRadians(degrees)
            val c = cos(radians)
            val s = sin(radians)
            return Affine2D(c, -s, 0.0, s, c, 0.0)
        }

        /** 삼각함수 반올림 오차 없이 정확한 `turns × 90°` 시계 방향 회전. */
        public fun quarterTurns(turns: Int): Affine2D = when (Math.floorMod(turns, 4)) {
            0 -> Identity
            1 -> Affine2D(0.0, -1.0, 0.0, 1.0, 0.0, 0.0)
            2 -> Affine2D(-1.0, 0.0, 0.0, 0.0, -1.0, 0.0)
            else -> Affine2D(0.0, 1.0, 0.0, -1.0, 0.0, 0.0)
        }
    }
}

/** 소수 성분을 갖는 크기. 픽셀 단위의 중간 기하 계산에 쓴다. */
public data class Size2D(val width: Double, val height: Double) {
    public val aspectRatio: Double get() = width / height
}
