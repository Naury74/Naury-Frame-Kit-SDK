package com.naury.framekit.android.result

/**
 * Final result of an editor launch. Delivered exactly once per launch.
 *
 * Cancelling a running export returns to the editor and does not produce [Cancelled]; only closing
 * the editor or dismissing the picker does.
 */
public sealed interface FrameKitResult {
    public data class Success(val output: EditedMedia) : FrameKitResult
    public data object Cancelled : FrameKitResult
    public data class Failure(val error: EditorError) : FrameKitResult
}
