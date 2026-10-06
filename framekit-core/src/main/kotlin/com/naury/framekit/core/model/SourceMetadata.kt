package com.naury.framekit.core.model

/**
 * 방향 정규화를 마친 등록 소스의 불변 정보.
 *
 * @property uprightSize EXIF 또는 컨테이너 회전을 적용한 뒤의 표시 크기.
 *   S 공간의 모든 정규화 좌표는 이 크기를 기준으로 한다.
 * @property mimeType 파일 이름이 아니라 내용에서 감지한 MIME 타입.
 * @property durationUs 미디어 길이(마이크로초). 정지 이미지면 `null`.
 * @property hasAudio 소스에 오디오 트랙이 있는지 여부. 이미지는 항상 `false`.
 */
public data class SourceMetadata(
    val id: SourceId,
    val mediaType: MediaType,
    val mimeType: String,
    val uprightSize: PixelSize,
    val durationUs: Long? = null,
    val hasAudio: Boolean = false,
)
