package com.naury.framekit.image.decode

import android.graphics.Bitmap
import com.naury.framekit.core.model.PixelRect
import com.naury.framekit.core.model.PixelSize

/**
 * 원본을 정방향으로 세운 software bitmap이며, subsampling되었을 수 있다.
 *
 * bitmap의 소유권은 디코딩을 요청한 쪽에 있다. hardware bitmap이 아니므로 CPU Canvas
 * renderer가 읽을 수 있다. 더 이상 그리지 않을 때 [recycle]을 호출한다.
 *
 * @property uprightSize 원본의 전체 해상도 정방향 크기. render plan은 이 크기를 기준으로
 *   표현한다.
 * @property uprightRegion 정방향 원본 중 [bitmap]이 덮는 영역. 영역 디코딩으로 얻은 bitmap이
 *   아니면 이미지 전체이며, renderer는 bitmap을 이 사각형에 매핑한다.
 */
public class DecodedImage(
    public val bitmap: Bitmap,
    public val uprightSize: PixelSize,
    public val hasGainMap: Boolean,
    public val uprightRegion: PixelRect = PixelRect.of(uprightSize),
) {
    public fun recycle() {
        if (!bitmap.isRecycled) bitmap.recycle()
    }
}
