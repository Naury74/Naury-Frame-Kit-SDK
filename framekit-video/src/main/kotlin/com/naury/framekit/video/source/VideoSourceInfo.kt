package com.naury.framekit.video.source

import com.naury.framekit.core.model.PixelSize
import com.naury.framekit.core.model.SourceMetadata

/**
 * FrameKit이 재생 전에 파악한 영상 원본 정보다.
 *
 * @property metadata 정방향 크기(컨테이너 회전 적용), 길이(마이크로초), 오디오 트랙 유무.
 * @property encodedSize 회전 전 저장된 프레임 크기.
 * @property rotationDegrees 컨테이너 회전값. `0`, `90`, `180`, `270` 중 하나.
 * @property frameRate 컨테이너가 선언한 frame rate. 모르면 `null`.
 * @property isHdr HDR transfer function이면 `true`. FrameKit은 기본으로 SDR로 내보낸다.
 */
public data class VideoSourceInfo(
    val metadata: SourceMetadata,
    val encodedSize: PixelSize,
    val rotationDegrees: Int,
    val videoMimeType: String,
    val audioMimeType: String?,
    val frameRate: Float?,
    val isHdr: Boolean,
)
