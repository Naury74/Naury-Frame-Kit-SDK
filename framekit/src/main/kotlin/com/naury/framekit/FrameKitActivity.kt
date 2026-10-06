package com.naury.framekit

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.IntentCompat
import com.naury.framekit.android.input.EditorInput
import com.naury.framekit.android.input.MediaKind
import com.naury.framekit.android.result.EditorError
import com.naury.framekit.android.result.EditorErrorCode
import com.naury.framekit.android.result.FrameKitResult
import com.naury.framekit.core.validation.ValidationResult
import com.naury.framekit.ui.image.contract.ImageEditorContract
import com.naury.framekit.ui.video.contract.VideoEditorContract

/**
 * [FrameKitContract] 뒤에서 동작하는 보이지 않는 라우터다. 필요하면 picker를 띄우고, 소스에 맞는
 * 편집기를 연 뒤 그 결과를 그대로 돌려준다. 재생성되어도 편집기를 두 번 띄우지 않는다.
 */
internal class FrameKitActivity : ComponentActivity() {

    private var delivered = false
    private var launched = false
    private lateinit var request: FrameKitRequest

    private val imageEditor = registerForActivityResult(ImageEditorContract(), ::deliver)
    private val videoEditor = registerForActivityResult(VideoEditorContract(), ::deliver)
    private val picker = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri == null) deliver(FrameKitResult.Cancelled) else open(EditorInput.UriSource(uri))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val parsed = IntentCompat.getParcelableExtra(intent, FrameKitContract.EXTRA_REQUEST, FrameKitRequest::class.java)
        if (parsed == null || parsed.validate() is ValidationResult.Invalid) {
            deliver(FrameKitResult.Failure(EditorError(EditorErrorCode.INVALID_CONFIGURATION)))
            return
        }
        request = parsed
        launched = savedInstanceState?.getBoolean(KEY_LAUNCHED) == true
        if (!launched) {
            launched = true
            open(parsed.input)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_LAUNCHED, launched)
    }

    private fun open(input: EditorInput) {
        if (input is EditorInput.Pick && input.kind == MediaKind.ANY) {
            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
            return
        }
        when (MediaKindResolver.resolve(input, contentResolver)) {
            MediaKind.IMAGE -> imageEditor.launch(request.forImage(input))
            MediaKind.VIDEO -> videoEditor.launch(request.forVideo(input))
            else -> deliver(FrameKitResult.Failure(EditorError(EditorErrorCode.UNSUPPORTED_FORMAT)))
        }
    }

    private fun deliver(result: FrameKitResult) {
        if (delivered) return
        delivered = true
        when (result) {
            is FrameKitResult.Success -> setResult(RESULT_OK, Intent().putExtra(FrameKitContract.EXTRA_OUTPUT, result.output))
            is FrameKitResult.Failure -> setResult(FrameKitContract.RESULT_FAILURE, Intent().putExtra(FrameKitContract.EXTRA_ERROR, result.error))
            FrameKitResult.Cancelled -> setResult(RESULT_CANCELED)
        }
        finish()
    }

    private companion object {
        const val KEY_LAUNCHED = "framekit_router_launched"
    }
}
