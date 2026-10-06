package com.naury.framekit.image.render

import android.graphics.Bitmap
import com.naury.framekit.core.geometry.OutputSizeCalculator
import com.naury.framekit.core.geometry.Size2D
import com.naury.framekit.core.model.ImageProject
import com.naury.framekit.core.model.SourceMetadata
import com.naury.framekit.image.decode.DecodedImage
import com.naury.framekit.image.effect.ColorEffectRenderer
import com.naury.framekit.image.overlay.PrivacyRenderer

/** 미리보기에 표시할 대상이다. */
public enum class PreviewMode {
    /** 내보낼 때와 같은 편집 결과. */
    RESULT,

    /** geometry 도구용으로 crop 없이 회전된 이미지 전체. 비네트와 그레인은 건너뛴다. */
    UNCROPPED,
}

/**
 * 내보내기와 같은 plan factory, geometry·color·privacy renderer로 화면 크기의 미리보기를 렌더링하므로
 * 사용자가 보는 그대로 저장된다. 텍스트·스티커·그리기는 호출자가 같은 overlay renderer로
 * 그 위에 그린다.
 */
public class ImagePreviewRenderer(private val colorRenderer: ColorEffectRenderer) {

    /**
     * @param maxWidth 미리보기 최대 너비(px). 보통 viewport 너비다.
     * @param maxHeight 미리보기 최대 높이(px).
     * @return 호출자가 소유하는 새 bitmap.
     */
    public fun render(
        source: DecodedImage,
        project: ImageProject,
        metadata: SourceMetadata,
        mode: PreviewMode,
        maxWidth: Int,
        maxHeight: Int,
        cutoutMask: Bitmap? = null,
    ): Bitmap {
        val plan = when (mode) {
            PreviewMode.RESULT -> {
                val full = ImageRenderPlanFactory.outputSize(project, metadata, Long.MAX_VALUE)
                val size = OutputSizeCalculator.compute(
                    Size2D(full.width.toDouble(), full.height.toDouble()),
                    Long.MAX_VALUE,
                    maxWidth.coerceAtLeast(1),
                    maxHeight.coerceAtLeast(1),
                )
                ImageRenderPlanFactory.create(project, metadata, size)
            }
            PreviewMode.UNCROPPED -> ImageRenderPlanFactory.uncropped(project, metadata, maxOf(maxWidth, maxHeight))
        }
        val bitmap = CanvasGeometryRenderer.render(source, plan, backgroundArgb = null, cutoutMask = cutoutMask)
        colorRenderer.apply(bitmap, project.colorSpec, includeCanvasEffects = mode == PreviewMode.RESULT)
        // 가리기는 출력 캔버스 기준이라 crop 전체 보기에서는 적용하지 않는다.
        if (mode == PreviewMode.RESULT) PrivacyRenderer.apply(bitmap, project.privacyMasks)
        return bitmap
    }
}
