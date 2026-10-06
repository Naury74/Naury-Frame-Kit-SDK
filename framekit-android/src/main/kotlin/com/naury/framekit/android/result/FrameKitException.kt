package com.naury.framekit.android.result

/**
 * Failure with a stable [EditorErrorCode], thrown by FrameKit engines and resolvers.
 *
 * Coroutine cancellation is never wrapped in this type.
 */
public class FrameKitException(
    public val code: EditorErrorCode,
    message: String? = null,
    cause: Throwable? = null,
) : Exception(message ?: code.name, cause) {

    /** Converts this failure into the DTO returned to the host. */
    public fun toEditorError(): EditorError = EditorError(code)
}
