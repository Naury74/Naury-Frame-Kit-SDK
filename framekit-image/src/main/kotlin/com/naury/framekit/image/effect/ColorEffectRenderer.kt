package com.naury.framekit.image.effect

import android.graphics.Bitmap
import com.naury.framekit.core.effect.ColorEffectProcessor
import com.naury.framekit.core.effect.ColorEffectSpec

/**
 * Applies a [ColorEffectSpec] to a bitmap in place.
 *
 * Preview and export use the same renderer instance, so the same spec produces the same pixels at any
 * size up to the documented tolerance.
 */
public interface ColorEffectRenderer {

    /**
     * @param bitmap mutable ARGB_8888 bitmap; it is overwritten with the result.
     * @param includeCanvasEffects `false` skips vignette and grain for views that are not the final
     *   canvas.
     */
    public fun apply(bitmap: Bitmap, spec: ColorEffectSpec, includeCanvasEffects: Boolean = true)

    /** Extra memory in bytes that [apply] needs for a bitmap of this size, for export preflight. */
    public fun workingBytes(width: Int, height: Int, spec: ColorEffectSpec): Long

    /** Frees GPU resources. The renderer must not be used afterwards. */
    public fun release()
}

/** CPU reference renderer. Slower than the GPU path but available on every device. */
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
