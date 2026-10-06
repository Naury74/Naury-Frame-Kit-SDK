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
import com.naury.framekit.android.result.FrameKitResultCodec
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

    // 사진·영상을 함께 여러 개 고르는 picker. 최대 개수는 요청을 읽은 뒤 정하므로 실행할 때 계약을 만든다.
    private val multiPicker = registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(EditorInput.MAX_PICK_ITEMS)) { uris: List<Uri> ->
        when {
            uris.isEmpty() -> deliver(FrameKitResult.Cancelled)
            uris.size == 1 -> open(EditorInput.UriSource(uris.single()))
            else -> open(EditorInput.Multiple(uris.map(EditorInput::UriSource)))
        }
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
            val request = PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
            if (input.maxItems > 1) multiPicker.launch(request) else picker.launch(request)
            return
        }
        val kind = MediaKindResolver.resolve(input, contentResolver)
        if (input is EditorInput.Multiple && kind != null) {
            val limit = if (kind == MediaKind.IMAGE) request.image.maxImageCount else request.video.maxClipCount
            val limited = if (input.items.size > limit) EditorInput.Multiple(input.items.take(limit)) else input
            return launchEditor(kind, limited)
        }
        when {
            kind != null -> launchEditor(kind, input)
            // 사진과 영상을 한 번에 편집할 수는 없다.
            input is EditorInput.Multiple -> deliver(FrameKitResult.Failure(EditorError(EditorErrorCode.UNSUPPORTED_OPERATION)))
            else -> deliver(FrameKitResult.Failure(EditorError(EditorErrorCode.UNSUPPORTED_FORMAT)))
        }
    }

    private fun launchEditor(kind: MediaKind, input: EditorInput) {
        try {
            when (kind) {
                MediaKind.IMAGE -> imageEditor.launch(request.forImage(input))
                else -> videoEditor.launch(request.forVideo(input))
            }
        } catch (error: RuntimeException) {
            // 요청이 Binder 한도를 넘는 등 편집기를 열 수 없는 경우. 앱을 멈추지 않고 실패로 돌려준다.
            deliver(FrameKitResult.Failure(EditorError(EditorErrorCode.INVALID_CONFIGURATION)))
        }
    }

    private fun deliver(result: FrameKitResult) {
        if (delivered) return
        delivered = true
        val (code, data) = FrameKitResultCodec.encode(result)
        setResult(code, data)
        finish()
    }

    private companion object {
        const val KEY_LAUNCHED = "framekit_router_launched"
    }
}
