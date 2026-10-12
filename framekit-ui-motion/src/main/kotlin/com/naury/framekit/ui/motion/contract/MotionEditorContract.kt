package com.naury.framekit.ui.motion.contract

import android.content.Context
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContract
import com.naury.framekit.android.result.FrameKitResult
import com.naury.framekit.android.result.FrameKitResultCodec
import com.naury.framekit.ui.motion.editor.MotionEditorActivity

/**
 * 움직이는 이미지 편집기를 열고 정확히 하나의 [FrameKitResult]를 돌려준다. 결과는 MP4(`MediaType.VIDEO`)나
 * GIF·움직이는 WebP(`MediaType.ANIMATED_IMAGE`)다.
 *
 * ```
 * val launcher = registerForActivityResult(MotionEditorContract()) { result -> ... }
 * launcher.launch(MotionEditorRequest(EditorInput.Pick(MediaKind.IMAGE, maxItems = 10)))
 * ```
 */
public class MotionEditorContract : ActivityResultContract<MotionEditorRequest, FrameKitResult>() {

    override fun createIntent(context: Context, input: MotionEditorRequest): Intent =
        Intent(context, MotionEditorActivity::class.java).putExtra(EXTRA_REQUEST, input)

    override fun parseResult(resultCode: Int, intent: Intent?): FrameKitResult = FrameKitResultCodec.decode(resultCode, intent)

    internal companion object {
        const val EXTRA_REQUEST = "com.naury.framekit.extra.MOTION_REQUEST"
    }
}
