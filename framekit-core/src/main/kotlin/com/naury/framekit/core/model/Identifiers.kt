package com.naury.framekit.core.model

/**
 * 편집 세션에 등록된 미디어 소스의 키.
 *
 * core 모델은 `Uri`나 `Bitmap` 같은 플랫폼 객체를 절대 보관하지 않는다. 이 키에서 읽을 수 있는
 * 바이트와 접근 권한으로의 매핑은 Android 계층이 관리한다.
 */
@JvmInline
public value class SourceId(public val value: String) {
    init {
        require(value.isNotBlank()) { "SourceId must not be blank" }
    }
}

/** 편집 프로젝트 하나의 안정적인 식별자. */
@JvmInline
public value class ProjectId(public val value: String) {
    init {
        require(value.isNotBlank()) { "ProjectId must not be blank" }
    }
}
