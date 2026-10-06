package com.naury.framekit.image.render

import android.graphics.Matrix
import com.naury.framekit.core.geometry.Affine2D

/** Copies the six affine values into an `android.graphics.Matrix`; both use the same layout. */
public fun Affine2D.toAndroidMatrix(): Matrix = Matrix().apply {
    setValues(
        floatArrayOf(
            scaleX.toFloat(), skewX.toFloat(), translateX.toFloat(),
            skewY.toFloat(), scaleY.toFloat(), translateY.toFloat(),
            0f, 0f, 1f,
        ),
    )
}
