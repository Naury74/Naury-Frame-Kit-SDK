package com.naury.framekit.image.render

import com.naury.framekit.android.result.EditorErrorCode
import com.naury.framekit.android.result.FrameKitException
import com.naury.framekit.core.geometry.GeometryFrame
import com.naury.framekit.core.geometry.OutputSizeCalculator
import com.naury.framekit.core.geometry.RectN
import com.naury.framekit.core.model.ImageProject
import com.naury.framekit.core.model.PixelSize
import com.naury.framekit.core.model.SourceMetadata
import com.naury.framekit.core.validation.ImageProjectValidator
import com.naury.framekit.core.validation.ValidationResult

/** 프로젝트 스냅샷으로부터 [ImageRenderPlan]을 만든다. */
public object ImageRenderPlanFactory {

    /**
     * 편집 결과를 [outputSize]로 렌더링하는 plan이다.
     *
     * @throws FrameKitException 프로젝트 검증에 실패하면 `INVALID_PROJECT`.
     */
    public fun create(project: ImageProject, metadata: SourceMetadata, outputSize: PixelSize): ImageRenderPlan {
        requireValid(project, metadata)
        require(outputSize.isValid) { "Output size must be positive: $outputSize" }
        val frame = GeometryFrame(metadata.uprightSize, project.geometry)
        return ImageRenderPlan(project.revision, outputSize, frame.sourceToOutput(project.geometry.crop, outputSize))
    }

    /**
     * 주어진 제한 안에서 편집 결과의 출력 크기다. crop 영역을 확대하지는 않는다.
     *
     * @throws FrameKitException 프로젝트 검증에 실패하면 `INVALID_PROJECT`.
     */
    public fun outputSize(
        project: ImageProject,
        metadata: SourceMetadata,
        maxPixels: Long,
        maxWidth: Int? = null,
        maxHeight: Int? = null,
    ): PixelSize {
        requireValid(project, metadata)
        val frame = GeometryFrame(metadata.uprightSize, project.geometry)
        return OutputSizeCalculator.compute(frame.cropPixelSize(project.geometry.crop), maxPixels, maxWidth, maxHeight)
    }

    /**
     * crop 없이 회전된 이미지 전체(G 공간)를 렌더링하는 plan으로, crop 도구가 열려 있는 동안 쓴다.
     * 이미지 바깥 영역은 투명하게 남는다.
     */
    public fun uncropped(project: ImageProject, metadata: SourceMetadata, maxLongEdge: Int): ImageRenderPlan {
        val geometry = project.geometry.copy(crop = RectN.Full)
        val frame = GeometryFrame(metadata.uprightSize, geometry)
        val scale = minOf(1.0, maxLongEdge / maxOf(frame.bounds.width, frame.bounds.height))
        val size = PixelSize(
            maxOf(1, Math.round(frame.bounds.width * scale).toInt()),
            maxOf(1, Math.round(frame.bounds.height * scale).toInt()),
        )
        return ImageRenderPlan(project.revision, size, frame.sourceToOutput(RectN.Full, size))
    }

    private fun requireValid(project: ImageProject, metadata: SourceMetadata) {
        val result = ImageProjectValidator.validate(project, metadata)
        if (result is ValidationResult.Invalid) {
            throw FrameKitException(EditorErrorCode.INVALID_PROJECT, result.issues.joinToString { "${it.path}: ${it.code}" })
        }
    }
}
