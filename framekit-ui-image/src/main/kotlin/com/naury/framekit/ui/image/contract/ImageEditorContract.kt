package com.naury.framekit.ui.image.contract

import android.content.Context
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContract
import com.naury.framekit.android.result.FrameKitResult
import com.naury.framekit.android.result.FrameKitResultCodec
import com.naury.framekit.ui.image.editor.ImageEditorActivity

/**
 * 이미지 에디터를 열고 정확히 하나의 [FrameKitResult]를 반환한다.
 *
 * ```
 * val launcher = registerForActivityResult(ImageEditorContract()) { result -> ... }
 * launcher.launch(ImageEditorRequest(EditorInput.Pick()))
 * ```
 */
public class ImageEditorContract : ActivityResultContract<ImageEditorRequest, FrameKitResult>() {

    override fun createIntent(context: Context, input: ImageEditorRequest): Intent =
        Intent(context, ImageEditorActivity::class.java).putExtra(EXTRA_REQUEST, input)

    override fun parseResult(resultCode: Int, intent: Intent?): FrameKitResult = FrameKitResultCodec.decode(resultCode, intent)

    internal companion object {
        const val EXTRA_REQUEST = "com.naury.framekit.extra.IMAGE_REQUEST"
    }
}
