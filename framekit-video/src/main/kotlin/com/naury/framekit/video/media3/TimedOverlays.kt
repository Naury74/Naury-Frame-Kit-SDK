// Media3의 composition·effect·Transformer API는 @UnstableApi다. FrameKit은 이 모듈 안의 어댑터에서만 쓰고
// 버전을 고정(1.11.1)해 API 변경을 빌드 단계에서 확인한다.
@file:OptIn(UnstableApi::class)

package com.naury.framekit.video.media3

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.annotation.OptIn
import androidx.core.graphics.createBitmap
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import com.naury.framekit.core.model.PixelSize
import com.naury.framekit.core.overlay.ImageOverlay
import com.naury.framekit.core.video.TimedOverlay
import com.naury.framekit.image.overlay.OverlayRenderer

/**
 * 출력 캔버스 크기의 비트맵 하나에 지금 보여야 할 텍스트·스티커를 그려 영상 위에 얹는다.
 *
 * 사진과 같은 [OverlayRenderer]를 쓰므로 위치·크기·글꼴이 사진 편집과 같다. 보이는 오버레이 묶음이 바뀔
 * 때만 다시 그리고, 그 사이 프레임은 같은 비트맵을 재사용한다.
 */
internal class TimedBitmapOverlay(private val overlays: List<TimedOverlay>) : BitmapOverlay() {

    private val renderer = OverlayRenderer()
    private var frameSize = Size(1, 1)
    private var cachedKey: List<ImageOverlay>? = null
    private var cached: Bitmap? = null

    override fun configure(videoSize: Size) {
        super.configure(videoSize)
        if (videoSize != frameSize) {
            frameSize = videoSize
            cachedKey = null
        }
    }

    override fun getBitmap(presentationTimeUs: Long): Bitmap {
        val active = overlays.filter { presentationTimeUs in it.range }.map { it.overlay }
        cached?.takeIf { active == cachedKey && !it.isRecycled }?.let { return it }
        val bitmap = createBitmap(frameSize.width, frameSize.height)
        renderer.draw(Canvas(bitmap), PixelSize(frameSize.width, frameSize.height), active, emptyList())
        // 이전 비트맵은 GL 텍스처로 이미 올라갔으므로 바로 정리해도 된다.
        cached?.recycle()
        cached = bitmap
        cachedKey = active
        return bitmap
    }

    override fun release() {
        super.release()
        cached?.recycle()
        cached = null
    }
}
