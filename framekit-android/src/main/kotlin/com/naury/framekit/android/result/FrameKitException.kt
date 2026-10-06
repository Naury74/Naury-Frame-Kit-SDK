package com.naury.framekit.android.result

/**
 * 안정적인 [EditorErrorCode]를 담은 실패로, FrameKit 엔진과 resolver가 던진다.
 *
 * 코루틴 취소는 절대 이 타입으로 감싸지 않는다.
 */
public class FrameKitException(
    public val code: EditorErrorCode,
    message: String? = null,
    cause: Throwable? = null,
) : Exception(message ?: code.name, cause) {

    /** 이 실패를 호스트에 반환할 DTO로 변환한다. */
    public fun toEditorError(): EditorError = EditorError(code)
}
