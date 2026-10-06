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
 * 소스에 맞춰 사진 편집기 또는 영상 편집기를 열고 [FrameKitResult]를 정확히 하나 반환한다.
 * 어떤 종류가 돌아왔는지는 `EditedMedia.mediaType`으로 알 수 있다.
 *
 * ```
 * val launcher = registerForActivityResult(FrameKitContract()) { result -> ... }
 * launcher.launch(FrameKitRequest(EditorInput.Pick(MediaKind.ANY)))
 * ```
 *
 * 한 종류만 편집하는 앱은 `framekit-ui-image` 또는 `framekit-ui-video`에만 의존하고
 * 해당 contract를 직접 사용해도 된다.
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
