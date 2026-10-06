package com.naury.framekit.ui.video.contract

import android.content.Context
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContract
import com.naury.framekit.android.result.FrameKitResult
import com.naury.framekit.android.result.FrameKitResultCodec
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

    override fun parseResult(resultCode: Int, intent: Intent?): FrameKitResult = FrameKitResultCodec.decode(resultCode, intent)

    internal companion object {
        const val EXTRA_REQUEST = "com.naury.framekit.extra.VIDEO_REQUEST"
    }
}
