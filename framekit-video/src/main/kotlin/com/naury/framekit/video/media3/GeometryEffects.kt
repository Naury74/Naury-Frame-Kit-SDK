// Media3의 composition·effect·Transformer API는 @UnstableApi다. FrameKit은 이 모듈 안의 어댑터에서만 쓰고
// 버전을 고정(1.11.1)해 API 변경을 빌드 단계에서 확인한다.
@file:OptIn(UnstableApi::class)

package com.naury.framekit.video.media3

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.Effect
import androidx.media3.effect.Crop
import androidx.media3.effect.ScaleAndRotateTransformation
import com.naury.framekit.core.geometry.GeometryEdit
import com.naury.framekit.core.geometry.RectN

/**
 * Maps a [GeometryEdit] to Media3 matrix effects.
 *
 * The model rotates clockwise in y-down image space and flips after rotating. Media3 rotates
 * counter-clockwise in y-up GL space and scales (flips) before rotating. Flipping one axis reverses
 * the rotation direction, so with an odd number of flips the angle keeps its sign.
 */
internal object GeometryEffects {

    fun effects(geometry: GeometryEdit): List<Effect> = buildList {
        val clockwise = geometry.quarterTurns * 90.0 + geometry.straightenDegrees
        if (clockwise != 0.0 || geometry.flipX || geometry.flipY) {
            val oddFlip = geometry.flipX != geometry.flipY
            add(
                ScaleAndRotateTransformation.Builder()
                    .setScale(if (geometry.flipX) -1f else 1f, if (geometry.flipY) -1f else 1f)
                    .setRotationDegrees((if (oddFlip) clockwise else -clockwise).toFloat())
                    .build(),
            )
        }
        if (geometry.crop != RectN.Full) add(crop(geometry.crop))
    }

    // Crop은 GL NDC(-1..1, y 위쪽 양수) 좌표를 받는다.
    private fun crop(rect: RectN): Crop = Crop(
        (rect.left * 2 - 1).toFloat(),
        (rect.right * 2 - 1).toFloat(),
        (1 - rect.bottom * 2).toFloat(),
        (1 - rect.top * 2).toFloat(),
    )
}
