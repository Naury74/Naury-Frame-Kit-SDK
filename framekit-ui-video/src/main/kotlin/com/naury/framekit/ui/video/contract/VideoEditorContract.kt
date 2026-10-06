package com.naury.framekit.ui.video.contract

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContract
import androidx.core.content.IntentCompat
import com.naury.framekit.android.result.EditedMedia
import com.naury.framekit.android.result.EditorError
import com.naury.framekit.android.result.EditorErrorCode
import com.naury.framekit.android.result.FrameKitResult
import com.naury.framekit.ui.video.editor.VideoEditorActivity

/**
 * 영상 에디터를 열고 정확히 하나의 [FrameKitResult]를 반환한다.
 *
 * ```
 * val launcher = registerForActivityResult(VideoEditorContract()) { result -> ... }
 * launcher.launch(VideoEditorRequest(EditorInput.Pick(MediaKind.VIDEO)))
 * ```
 */
public class VideoEditorContract : ActivityResultContract<VideoEditorRequest, FrameKitResult>() {

    override fun createIntent(context: Context, input: VideoEditorRequest): Intent =
        Intent(context, VideoEditorActivity::class.java).putExtra(EXTRA_REQUEST, input)

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
        const val EXTRA_REQUEST = "com.naury.framekit.extra.VIDEO_REQUEST"
        const val EXTRA_OUTPUT = "com.naury.framekit.extra.OUTPUT"
        const val EXTRA_ERROR = "com.naury.framekit.extra.ERROR"
        const val RESULT_FAILURE = Activity.RESULT_FIRST_USER + 1
    }
}
