package com.naury.framekit.image.decode

import com.naury.framekit.core.geometry.ExifOrientation
import com.naury.framekit.core.model.PixelSize
import com.naury.framekit.core.model.SourceMetadata

/**
 * 픽셀을 디코딩하지 않고 decoder가 알아낸 이미지 정보다.
 *
 * @property encodedSize [orientation]을 적용하기 전 저장된 픽셀의 크기.
 * @property isSrgb 이미지가 다른 색 공간을 선언해 변환될 예정이면 `false`.
 * @property hasGainMap 에디터가 gain map을 보존하지 않는 Ultra HDR 이미지면 `true`.
 */
public data class ImageSourceInfo(
    val metadata: SourceMetadata,
    val encodedSize: PixelSize,
    val orientation: ExifOrientation,
    val isSrgb: Boolean,
    val hasGainMap: Boolean = false,
)
