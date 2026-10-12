package com.naury.framekit.motion.render

import android.graphics.Bitmap
import com.naury.framekit.motion.codec.AnimatedFrames
import com.naury.framekit.motion.model.MotionKind

/**
 * 렌더러가 그릴 원본 그림. 슬라이드쇼는 사진을 필요할 때 [slide]로 받고, 움직이는 원본은 미리 푼 [animation]을 쓴다.
 *
 * @property slideAspects 사진마다 정방향 너비/높이 비율. 슬라이드쇼의 화면 비율 계산에 쓴다.
 * @property slide 사진 번호를 받아 정방향 비트맵을 돌려준다. 돌려준 비트맵은 [MotionMedia] 쪽이 갖고 있으며 호출자가 recycle하지 않는다.
 */
public class MotionMedia(
    public val kind: MotionKind,
    public val slideAspects: List<Double>,
    private val slide: (Int) -> Bitmap,
    public val animation: AnimatedFrames?,
) {
    init {
        require(kind == MotionKind.SLIDESHOW || animation != null) { "Animation media needs frames" }
    }

    public val slideCount: Int get() = slideAspects.size

    public fun slide(index: Int): Bitmap = slide.invoke(index)

    /** 첫 원본의 너비/높이 비율. */
    public val sourceAspect: Double
        get() = animation?.let { it.width.toDouble() / it.height } ?: slideAspects.firstOrNull() ?: 1.0
}
