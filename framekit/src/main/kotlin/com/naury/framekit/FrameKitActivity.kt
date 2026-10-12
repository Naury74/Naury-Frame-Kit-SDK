package com.naury.framekit

import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.os.BundleCompat
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import com.naury.framekit.android.input.EditorInput
import com.naury.framekit.android.input.MediaKind
import com.naury.framekit.android.input.isSingleSource
import com.naury.framekit.android.result.EditorError
import com.naury.framekit.android.result.EditorErrorCode
import com.naury.framekit.android.result.FrameKitResult
import com.naury.framekit.android.result.FrameKitResultCodec
import com.naury.framekit.chooser.ChooserOptions
import com.naury.framekit.chooser.ChooserRules
import com.naury.framekit.chooser.ChooserSheet
import com.naury.framekit.chooser.SourceInspector
import com.naury.framekit.chooser.SourceKind
import com.naury.framekit.core.validation.ValidationResult
import com.naury.framekit.motion.codec.AnimatedImageProbe
import com.naury.framekit.ui.design.FrameKitTheme
import com.naury.framekit.ui.design.ProvideEditorLocale
import com.naury.framekit.ui.image.contract.ImageEditorContract
import com.naury.framekit.ui.motion.contract.MotionEditorContract
import com.naury.framekit.ui.audioeditor.contract.AudioEditorContract
import com.naury.framekit.ui.video.contract.VideoEditorContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * [FrameKitContract] 뒤에서 동작하는 라우터다. 필요하면 picker를 띄우고, 원본에 맞는 편집기를 열거나([EditorChooser.AUTO])
 * 무엇을 할지 고르는 시트를 보여 준 뒤([EditorChooser.ASK]) 그 결과를 그대로 돌려준다. 재생성되어도 편집기를 두 번 띄우지 않는다.
 */
internal class FrameKitActivity : ComponentActivity() {

    private var delivered = false
    private var launched = false
    private lateinit var request: FrameKitRequest
    private val inspector by lazy { SourceInspector(applicationContext) }

    // 고르기 시트 상태. 화면이 다시 만들어지면 저장해 둔 원본으로 다시 살펴본다.
    private var chooserInput: EditorInput? = null
    private var chooser by mutableStateOf<ChooserUi?>(null)
    private val thumbnails = mutableStateListOf<Bitmap?>()
    private var pickLimit = EditorInput.MAX_PICK_ITEMS

    private class ChooserUi(val input: EditorInput, val kinds: List<SourceKind>, val options: ChooserOptions)

    private val imageEditor = registerForActivityResult(ImageEditorContract(), ::deliver)
    private val videoEditor = registerForActivityResult(VideoEditorContract(), ::deliver)
    private val motionEditor = registerForActivityResult(MotionEditorContract(), ::deliver)
    private val audioEditor = registerForActivityResult(AudioEditorContract(), ::deliver)
    private val audioPicker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        val picked = uris.take(pickLimit)
        when {
            picked.isEmpty() -> deliver(FrameKitResult.Cancelled)
            picked.size == 1 -> open(EditorInput.UriSource(picked.single()))
            else -> open(EditorInput.Multiple(picked.map(EditorInput::UriSource)))
        }
    }
    private val picker = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri == null) deliver(FrameKitResult.Cancelled) else open(EditorInput.UriSource(uri))
    }

    // 여러 개를 고르는 picker. 최대 개수는 요청을 읽은 뒤 정하므로 고른 뒤 그 수만큼 남긴다.
    private val multiPicker = registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(EditorInput.MAX_PICK_ITEMS)) { uris: List<Uri> ->
        val picked = uris.take(pickLimit)
        when {
            picked.isEmpty() -> deliver(FrameKitResult.Cancelled)
            picked.size == 1 -> open(EditorInput.UriSource(picked.single()))
            else -> open(EditorInput.Multiple(picked.map(EditorInput::UriSource)))
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
        pickLimit = (parsed.input as? EditorInput.Pick)?.maxItems ?: EditorInput.MAX_PICK_ITEMS
        setContent {
            val ui = chooser ?: return@setContent
            ProvideEditorLocale(request.ui.localeTag) {
                FrameKitTheme(request.ui) {
                    ChooserSheet(
                        count = ui.kinds.size,
                        thumbnails = thumbnails,
                        options = ui.options,
                        onChoose = { action -> perform(action, ui.input, ui.kinds) },
                        onDismiss = { deliver(FrameKitResult.Cancelled) },
                    )
                }
            }
        }
        launched = savedInstanceState?.getBoolean(KEY_LAUNCHED) == true
        val savedChooser = savedInstanceState?.let { BundleCompat.getParcelable(it, KEY_CHOOSER, EditorInput::class.java) }
        when {
            savedChooser != null -> showChooser(savedChooser)
            !launched -> {
                launched = true
                open(parsed.input)
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_LAUNCHED, launched)
        chooserInput?.let { outState.putParcelable(KEY_CHOOSER, it) }
    }

    private fun open(input: EditorInput) {
        // 녹음은 음성 편집기가 바로 연다.
        if (input is EditorInput.Capture && input.kind == MediaKind.AUDIO) return launchEditor(MediaKind.AUDIO, input)
        if (request.chooser == EditorChooser.ASK && input !is EditorInput.Capture) {
            // 고르기 시트를 쓰면 원본을 이 라우터가 먼저 받아야 하므로 picker도 여기서 띄운다.
            if (input is EditorInput.Pick) return launchPicker(input.kind, input.maxItems)
            return showChooser(input)
        }
        if (input is EditorInput.Pick && input.kind == MediaKind.ANY) return launchPicker(MediaKind.ANY, input.maxItems)
        // 사진 한 장을 고를 때는 직접 골라, 움직이는 이미지면 움직이는 이미지 편집기로 보낸다.
        if (input is EditorInput.Pick && input.kind == MediaKind.IMAGE && input.maxItems == 1 && request.openAnimatedInMotionEditor) {
            return launchPicker(MediaKind.IMAGE, 1)
        }
        openAutomatically(input)
    }

    private fun launchPicker(kind: MediaKind, maxItems: Int) {
        pickLimit = maxItems
        if (kind == MediaKind.AUDIO) return audioPicker.launch(arrayOf("audio/*"))
        val type = when (kind) {
            MediaKind.IMAGE -> ActivityResultContracts.PickVisualMedia.ImageOnly
            MediaKind.VIDEO -> ActivityResultContracts.PickVisualMedia.VideoOnly
            MediaKind.ANY, MediaKind.AUDIO -> ActivityResultContracts.PickVisualMedia.ImageAndVideo
        }
        val pick = PickVisualMediaRequest(type)
        if (maxItems > 1) multiPicker.launch(pick) else picker.launch(pick)
    }

    // 원본 종류를 보고 알맞은 편집기를 바로 연다(EditorChooser.AUTO).
    private fun openAutomatically(input: EditorInput) {
        val kind = MediaKindResolver.resolve(input, contentResolver)
        when {
            kind != null -> launchEditor(kind, input)
            // 사진과 영상을 한 번에 편집할 수는 없다.
            input is EditorInput.Multiple -> deliver(FrameKitResult.Failure(EditorError(EditorErrorCode.UNSUPPORTED_OPERATION)))
            else -> deliver(FrameKitResult.Failure(EditorError(EditorErrorCode.UNSUPPORTED_FORMAT)))
        }
    }

    // 원본을 살펴보고 고를 수 있는 동작을 정한다. 하나뿐이면 묻지 않고 바로 한다.
    private fun showChooser(input: EditorInput) {
        chooserInput = input
        val items = if (input is EditorInput.Multiple) input.items else listOf(input)
        lifecycleScope.launch {
            val kinds = withContext(Dispatchers.IO) { items.map { inspector.kind(it) } }
            if (kinds.any { it == null }) return@launch deliver(FrameKitResult.Failure(EditorError(EditorErrorCode.UNSUPPORTED_FORMAT)))
            val known = kinds.filterNotNull()
            val options = ChooserRules.options(known, request.chooserActions)
            when {
                options.actions.isEmpty() && options.mixed -> deliver(FrameKitResult.Failure(EditorError(EditorErrorCode.UNSUPPORTED_OPERATION)))
                options.actions.isEmpty() -> {
                    chooserInput = null
                    openAutomatically(input)
                }
                options.actions.size == 1 -> perform(options.actions.single(), input, known)
                else -> {
                    thumbnails.clear()
                    repeat(minOf(items.size, MAX_THUMBNAILS)) { thumbnails.add(null) }
                    chooser = ChooserUi(input, known, options)
                    items.take(MAX_THUMBNAILS).forEachIndexed { index, item ->
                        val bitmap = withContext(Dispatchers.IO) { inspector.thumbnail(item, THUMBNAIL_PX) }
                        if (index < thumbnails.size) thumbnails[index] = bitmap
                    }
                }
            }
        }
    }

    private fun perform(action: EditorAction, input: EditorInput, kinds: List<SourceKind>) {
        chooser = null
        chooserInput = null
        try {
            when (action) {
                EditorAction.EDIT_PHOTO -> imageEditor.launch(request.forImage(input))
                EditorAction.MAKE_PDF -> imageEditor.launch(request.forPdf(input))
                EditorAction.MOTION -> motionEditor.launch(request.forMotion(input))
                EditorAction.EDIT_VIDEO -> videoEditor.launch(request.forVideo(input))
                EditorAction.EDIT_AUDIO -> audioEditor.launch(request.forAudio(input))
                EditorAction.SEND_ORIGINAL -> sendOriginals(input, kinds)
            }
        } catch (_: RuntimeException) {
            deliver(FrameKitResult.Failure(EditorError(EditorErrorCode.INVALID_CONFIGURATION)))
        }
    }

    // 편집하지 않고 원본 정보를 그대로 돌려준다.
    private fun sendOriginals(input: EditorInput, kinds: List<SourceKind>) {
        val items = if (input is EditorInput.Multiple) input.items else listOf(input)
        lifecycleScope.launch {
            val outputs = withContext(Dispatchers.IO) { items.zip(kinds).map { (item, kind) -> inspector.original(item, kind) } }
            if (outputs.any { it == null }) return@launch deliver(FrameKitResult.Failure(EditorError(EditorErrorCode.SOURCE_UNAVAILABLE)))
            val results = outputs.filterNotNull()
            deliver(FrameKitResult.Success(results.first(), results))
        }
    }

    private fun launchEditor(kind: MediaKind, input: EditorInput) {
        try {
            if (kind == MediaKind.IMAGE && request.openAnimatedInMotionEditor && input.isSingleSource) {
                // 파일을 읽어야 해서 메인 스레드 밖에서 판단한 뒤 편집기를 연다.
                lifecycleScope.launch {
                    val animated = withContext(Dispatchers.IO) { isAnimated(input) }
                    try {
                        if (animated) motionEditor.launch(request.forMotion(input)) else imageEditor.launch(request.forImage(input))
                    } catch (_: RuntimeException) {
                        deliver(FrameKitResult.Failure(EditorError(EditorErrorCode.INVALID_CONFIGURATION)))
                    }
                }
                return
            }
            when (kind) {
                MediaKind.IMAGE -> imageEditor.launch(request.forImage(input))
                MediaKind.AUDIO -> audioEditor.launch(request.forAudio(input))
                else -> videoEditor.launch(request.forVideo(input))
            }
        } catch (_: RuntimeException) {
            // 요청이 Binder 한도를 넘는 등 편집기를 열 수 없는 경우. 앱을 멈추지 않고 실패로 돌려준다.
            deliver(FrameKitResult.Failure(EditorError(EditorErrorCode.INVALID_CONFIGURATION)))
        }
    }

    // 원본 앞부분만 읽어 판단한다. 읽지 못하면 움직이지 않는 사진으로 본다.
    private fun isAnimated(input: EditorInput): Boolean = runCatching {
        val stream = when (input) {
            is EditorInput.UriSource -> contentResolver.openInputStream(input.uri)
            is EditorInput.FileSource -> File(input.absolutePath).inputStream()
            else -> null
        } ?: return false
        AnimatedImageProbe.isAnimated(stream)
    }.getOrDefault(false)

    private fun deliver(result: FrameKitResult) {
        if (delivered) return
        delivered = true
        chooser = null
        chooserInput = null
        val (code, data) = FrameKitResultCodec.encode(result)
        setResult(code, data)
        finish()
    }

    private companion object {
        const val KEY_LAUNCHED = "framekit_router_launched"
        const val KEY_CHOOSER = "framekit_router_chooser"
        const val MAX_THUMBNAILS = 6
        const val THUMBNAIL_PX = 192
    }
}
