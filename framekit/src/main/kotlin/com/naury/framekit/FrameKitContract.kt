package com.naury.framekit

import android.content.Context
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContract
import com.naury.framekit.android.result.FrameKitResult
import com.naury.framekit.android.result.FrameKitResultCodec

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

    override fun parseResult(resultCode: Int, intent: Intent?): FrameKitResult = FrameKitResultCodec.decode(resultCode, intent)

    internal companion object {
        const val EXTRA_REQUEST = "com.naury.framekit.extra.FRAMEKIT_REQUEST"
    }
}
