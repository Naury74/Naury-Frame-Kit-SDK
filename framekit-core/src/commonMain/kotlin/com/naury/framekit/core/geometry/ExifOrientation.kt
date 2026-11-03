package com.naury.framekit.core.geometry

import com.naury.framekit.core.model.PixelSize

/**
 * EXIF `Orientation` 태그 값 1~8.
 *
 * 미러링된 방향은 회전과 같지 않으므로 회전으로 근사해서는 안 된다.
 */
public enum class ExifOrientation(public val exifValue: Int) {
    NORMAL(1),
    FLIP_HORIZONTAL(2),
    ROTATE_180(3),
    FLIP_VERTICAL(4),
    TRANSPOSE(5),
    ROTATE_90(6),
    TRANSVERSE(7),
    ROTATE_270(8),
    ;

    /** 바로 세운 이미지의 가로·세로가 인코딩된 픽셀과 뒤바뀌면 `true`. */
    public val swapsDimensions: Boolean get() = exifValue >= 5

    public fun uprightSize(encoded: PixelSize): PixelSize = if (swapsDimensions) encoded.transposed() else encoded

    /**
     * 인코딩된 픽셀 좌표에서 바로 세운 픽셀 좌표로의 변환.
     *
     * @param encodedWidth 방향 적용 전 저장된 픽셀의 너비.
     * @param encodedHeight 방향 적용 전 저장된 픽셀의 높이.
     */
    public fun encodedToUpright(encodedWidth: Double, encodedHeight: Double): Affine2D {
        val w = encodedWidth
        val h = encodedHeight
        return when (this) {
            NORMAL -> Affine2D.Identity
            FLIP_HORIZONTAL -> Affine2D(-1.0, 0.0, w, 0.0, 1.0, 0.0)
            ROTATE_180 -> Affine2D(-1.0, 0.0, w, 0.0, -1.0, h)
            FLIP_VERTICAL -> Affine2D(1.0, 0.0, 0.0, 0.0, -1.0, h)
            TRANSPOSE -> Affine2D(0.0, 1.0, 0.0, 1.0, 0.0, 0.0)
            ROTATE_90 -> Affine2D(0.0, -1.0, h, 1.0, 0.0, 0.0)
            TRANSVERSE -> Affine2D(0.0, -1.0, h, -1.0, 0.0, w)
            ROTATE_270 -> Affine2D(0.0, 1.0, 0.0, -1.0, 0.0, w)
        }
    }

    public companion object {
        /** 원시 태그 값을 매핑한다. 없거나 0이거나 알 수 없는 값은 [NORMAL]로 취급한다. */
        public fun fromExifValue(value: Int?): ExifOrientation = entries.firstOrNull { it.exifValue == value } ?: NORMAL
    }
}
