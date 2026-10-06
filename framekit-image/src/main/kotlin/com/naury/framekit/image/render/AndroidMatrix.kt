package com.naury.framekit.image.render

import android.graphics.Matrix
import com.naury.framekit.core.geometry.Affine2D

/** affine 값 6개를 `android.graphics.Matrix`로 복사한다. 두 타입의 배치는 같다. */
public fun Affine2D.toAndroidMatrix(): Matrix = Matrix().apply {
    setValues(
        floatArrayOf(
            scaleX.toFloat(), skewX.toFloat(), translateX.toFloat(),
            skewY.toFloat(), scaleY.toFloat(), translateY.toFloat(),
            0f, 0f, 1f,
        ),
    )
}
