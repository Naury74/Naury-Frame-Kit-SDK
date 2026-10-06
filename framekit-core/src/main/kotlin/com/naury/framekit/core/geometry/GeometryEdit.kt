package com.naury.framekit.core.geometry

/**
 * 바로 세운 원본에 적용하는 기하 편집.
 *
 * 변환 순서는 고정이다: 90° 회전, 수평 보정, 뒤집기, 자르기 순. 열 벡터 기준으로
 * `M = crop × flip × straighten × quarterTurn`이다.
 *
 * @property quarterTurns 시계 방향 90° 단위 회전 수, `0..3`.
 * @property straightenDegrees 추가 시계 방향 회전 각도(도), `-45.0..45.0`.
 * @property flipX 회전된 이미지 경계의 중심을 기준으로 좌우 반전한다.
 * @property flipY 회전된 이미지 경계의 중심을 기준으로 상하 반전한다.
 * @property crop G 공간의 자르기 사각형. 회전·뒤집기된 이미지의 축 정렬 경계로 정규화된다. 빈 모서리가
 *   내보내지지 않도록 이미지 영역 안에 있어야 한다.
 */
public data class GeometryEdit(
    val quarterTurns: Int = 0,
    val straightenDegrees: Double = 0.0,
    val flipX: Boolean = false,
    val flipY: Boolean = false,
    val crop: RectN = RectN.Full,
) {
    /** 편집이 원본을 바꾸지 않으면 `true`. */
    public val isIdentity: Boolean
        get() = quarterTurns == 0 && straightenDegrees == 0.0 && !flipX && !flipY && crop == RectN.Full

    public companion object {
        public const val MIN_STRAIGHTEN_DEGREES: Double = -45.0
        public const val MAX_STRAIGHTEN_DEGREES: Double = 45.0
    }
}
