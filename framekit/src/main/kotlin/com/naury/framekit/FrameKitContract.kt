package com.naury.framekit

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContract
import androidx.core.content.IntentCompat
import com.naury.framekit.android.result.EditedMedia
import com.naury.framekit.android.result.EditorError
import com.naury.framekit.android.result.EditorErrorCode
import com.naury.framekit.android.result.FrameKitResult

/**
 * Opens the photo or the video editor, whichever matches the source, and returns exactly one
 * [FrameKitResult]. `EditedMedia.mediaType` tells which kind came back.
 *
 * ```
 * val launcher = registerForActivityResult(FrameKitContract()) { result -> ... }
 * launcher.launch(FrameKitRequest(EditorInput.Pick(MediaKind.ANY)))
 * ```
 *
 * Apps that only edit one kind can depend on `framekit-ui-image` or `framekit-ui-video` alone and
 * use their contracts directly.
 */
public class FrameKitContract : ActivityResultContract<FrameKitRequest, FrameKitResult>() {

    override fun createIntent(context: Context, input: FrameKitRequest): Intent =
        Intent(context, FrameKitActivity::class.java).putExtra(EXTRA_REQUEST, input)

    override fun parseResult(resultCode: Int, intent: Intent?): FrameKitResult = when (resultCode) {
        Activity.RESULT_OK -> intent?.let { IntentCompat.getParcelableExtra(it, EXTRA_OUTPUT, EditedMedia::class.java) }
            ?.let(FrameKitResult::Success)
            ?: FrameKitResult.Failure(EditorError(EditorErrorCode.RESULT_UNAVAILABLE))
        RESULT_FAILURE -> intent?.let { IntentCompat.getParcelableExtra(it, EXTRA_ERROR, EditorError::class.java) }
            ?.let(FrameKitResult::Failure)
            ?: FrameKitResult.Failure(EditorError(EditorErrorCode.RESULT_UNAVAILABLE))
        else -> FrameKitResult.Cancelled
    }

    internal companion object {
        const val EXTRA_REQUEST = "com.naury.framekit.extra.FRAMEKIT_REQUEST"
        const val EXTRA_OUTPUT = "com.naury.framekit.extra.OUTPUT"
        const val EXTRA_ERROR = "com.naury.framekit.extra.ERROR"
        const val RESULT_FAILURE = Activity.RESULT_FIRST_USER + 1
    }
}
