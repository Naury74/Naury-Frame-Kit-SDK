package com.naury.framekit.image.effect

import android.graphics.Bitmap
import com.naury.framekit.core.effect.ColorEffectProcessor
import com.naury.framekit.core.effect.ColorEffectSpec

/**
 * bitmap에 [ColorEffectSpec]을 제자리에서 적용한다.
 *
 * 미리보기와 내보내기가 같은 renderer 인스턴스를 쓰므로, 같은 spec은 크기와 관계없이 문서화된
 * 허용 오차 안에서 같은 픽셀을 만든다.
 */
public interface ColorEffectRenderer {

    /**
     * @param bitmap 변경 가능한 ARGB_8888 bitmap. 결과로 덮어쓴다.
     * @param includeCanvasEffects `false`면 최종 캔버스가 아닌 화면을 위해 비네트와 그레인을
     *   건너뛴다.
     */
    public fun apply(bitmap: Bitmap, spec: ColorEffectSpec, includeCanvasEffects: Boolean = true)

    /** 이 크기의 bitmap에 [apply]가 추가로 필요로 하는 메모리(바이트). 내보내기 사전 점검에 쓴다. */
    public fun workingBytes(width: Int, height: Int, spec: ColorEffectSpec): Long

    /** GPU 자원을 해제한다. 이후에는 renderer를 사용하면 안 된다. */
    public fun release()
}

/** CPU 기준 renderer. GPU 경로보다 느리지만 모든 기기에서 동작한다. */
public object CpuColorEffectRenderer : ColorEffectRenderer {

    override fun apply(bitmap: Bitmap, spec: ColorEffectSpec, includeCanvasEffects: Boolean) {
        if (spec.isIdentity) return
        require(bitmap.isMutable && bitmap.config == Bitmap.Config.ARGB_8888) { "Bitmap must be mutable ARGB_8888" }
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        ColorEffectProcessor.process(pixels, bitmap.width, bitmap.height, spec, includeCanvasEffects)
        bitmap.setPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    }

    override fun workingBytes(width: Int, height: Int, spec: ColorEffectSpec): Long {
        if (spec.isIdentity) return 0L
        val plane = width.toLong() * height * 4
        // 픽셀 배열 하나, 선명도를 쓰면 가로 blur와 결과 배열이 더 필요하다.
        return if (spec.sharpenAmount != 0f) plane * 3 else plane
    }

    override fun release(): Unit = Unit
}
