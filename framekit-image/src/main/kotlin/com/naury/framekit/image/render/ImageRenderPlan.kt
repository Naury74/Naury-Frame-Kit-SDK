package com.naury.framekit.image.render

import com.naury.framekit.core.geometry.Affine2D
import com.naury.framekit.core.model.PixelSize

/**
 * renderer가 프로젝트 스냅샷 하나를 한 크기로 그리는 데 필요한 모든 정보다.
 *
 * 미리보기와 내보내기는 같은 factory로 이 plan을 만든다. 어느 쪽도 자체 보정을 더하지 않으므로
 * 둘 사이의 차이는 plan 차이가 아니라 renderer 버그를 뜻한다.
 *
 * @property sourceToOutput 전체 해상도 정방향 원본 픽셀에서 출력 픽셀로의 변환.
 */
public data class ImageRenderPlan(
    val projectRevision: Long,
    val outputSize: PixelSize,
    val sourceToOutput: Affine2D,
)
