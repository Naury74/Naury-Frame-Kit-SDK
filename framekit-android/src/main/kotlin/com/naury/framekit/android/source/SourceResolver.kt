package com.naury.framekit.android.source

import android.net.Uri
import com.naury.framekit.android.result.FrameKitException
import com.naury.framekit.core.model.SourceId
import java.io.File
import java.io.InputStream

/** 스트림 대신 파일이나 Uri에서 직접 읽는 디코더를 위한 원본 위치. */
public sealed interface SourceLocation {
    public data class Content(val uri: Uri) : SourceLocation
    public data class LocalFile(val file: File) : SourceLocation
}

/**
 * [SourceId]가 가리키는 바이트를 연다.
 *
 * 호출할 때마다 처음 위치에서 시작하는 새 스트림을 반환한다. 스트림은 호출자가 닫는다.
 * 구현체는 하나의 스트림을 reset해서 메타데이터 읽기와 디코딩에 재사용할 수 있다고 가정하면 안 된다.
 */
public interface SourceResolver {

    /**
     * @throws FrameKitException 원본을 열 수 없을 때 `PERMISSION_DENIED`, `SOURCE_UNAVAILABLE`
     *   또는 `INVALID_SOURCE` 코드로 던진다.
     */
    public fun openInputStream(id: SourceId): InputStream

    /** provider가 보고한 MIME 타입. 알 수 없으면 `null`. */
    public fun reportedMimeType(id: SourceId): String?

    /**
     * 원본의 직접 위치. 스트림만 사용할 수 있으면 `null`이다. 디코더는 큰 파일을 메모리에 통째로
     * 올리지 않기 위해 이 값을 사용한다.
     */
    public fun location(id: SourceId): SourceLocation? = null
}
