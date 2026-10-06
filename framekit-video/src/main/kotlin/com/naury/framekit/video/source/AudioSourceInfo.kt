package com.naury.framekit.video.source

import com.naury.framekit.core.model.SourceMetadata

/**
 * 배경 음악 원본에 대해 읽은 정보.
 *
 * @property metadata `mediaType = AUDIO`, 크기는 0×0, 길이(µs)와 `hasAudio = true`.
 * @property audioMimeType 오디오 트랙의 코덱 MIME 형식.
 */
public data class AudioSourceInfo(
    val metadata: SourceMetadata,
    val audioMimeType: String,
)
