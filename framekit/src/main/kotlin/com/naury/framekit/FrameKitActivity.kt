package com.naury.framekit

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContract
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
import com.naury.framekit.mixed.MixedEditorContract
import com.naury.framekit.mixed.MixedEditorRequest
import com.naury.framekit.ui.design.FrameKitTheme
import com.naury.framekit.ui.design.ProvideEditorLocale
import com.naury.framekit.ui.image.contract.ImageEditorContract
import com.naury.framekit.ui.motion.contract.MotionEditorContract
import com.naury.framekit.ui.audioeditor.contract.AudioEditorContract
import com.naury.framekit.ui.video.contract.VideoEditorContract
import com.naury.framekit.ui.picker.MediaPickerContract
import com.naury.framekit.android.output.FrameKitOutputs
import com.naury.framekit.image.decode.SupportedImageFormats
import com.naury.framekit.image.export.ImageFormat
import com.naury.framekit.scanner.DocumentScanContract
import com.naury.framekit.scanner.DocumentScanRequest
import com.naury.framekit.ui.picker.MediaPickerRequest
import com.naury.framekit.ui.picker.MediaPickerResult
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

    // 원본을 살펴보는 비동기 단계. 화면이 다시 만들어지거나 프로세스가 다시 시작되면 코루틴이 끊기므로 저장해 두고 다시 한다.
    private enum class Step { INSPECT_MIXED, CHECK_ANIMATED, SEND_ORIGINAL }

    private var step: Step? = null
    private var stepInput: EditorInput? = null
    private var stepKinds: List<SourceKind> = emptyList()

    private val imageEditor = registerForActivityResult(ImageEditorContract(), ::deliver)
    private val videoEditor = registerForActivityResult(VideoEditorContract(), ::deliver)
    private val motionEditor = registerForActivityResult(MotionEditorContract(), ::deliver)
    private val audioEditor = registerForActivityResult(AudioEditorContract(), ::deliver)
    private val mixedEditor = registerForActivityResult(MixedEditorContract(), ::deliver)
    private val audioPicker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        val picked = uris.take(pickLimit)
        when {
            picked.isEmpty() -> deliver(FrameKitResult.Cancelled)
            picked.size == 1 -> open(EditorInput.UriSource(picked.single()))
            else -> open(EditorInput.Multiple(picked.map(EditorInput::UriSource)))
        }
    }
    // 사진·영상 고르기. 앱 안 갤러리(framekit-picker)나 시스템 선택기가 열리며, 고른 뒤 요청한 수만큼 남긴다.
    private val picker = registerForActivityResult(RouterPickerContract()) { result ->
        val picked = (result as? MediaPickerResult.Picked)?.items.orEmpty().map { it.uri }.take(pickLimit)
        when {
            // 고른 것이 모두 열 수 없는 형식이었다.
            result == null -> deliver(FrameKitResult.Failure(EditorError(EditorErrorCode.UNSUPPORTED_FORMAT)))
            picked.isEmpty() -> deliver(FrameKitResult.Cancelled)
            picked.size == 1 -> open(EditorInput.UriSource(picked.single()))
            else -> open(EditorInput.Multiple(picked.map(EditorInput::UriSource)))
        }
    }

    // 카메라로 스캔한 쪽. PDF 편집기에 넘긴 뒤에는 중간 파일이므로 편집이 끝나면 지운다.
    private var scannedPages = ArrayList<Uri>()

    private val scanner = registerForActivityResult(DocumentScanContract()) { result ->
        if (result is FrameKitResult.Success) {
            scannedPages = ArrayList(result.outputs.map { it.uri })
            val pages = result.outputs.map { EditorInput.UriSource(it.uri) }
            scannedPdfEditor.launch(request.forPdf(if (pages.size == 1) pages.single() else EditorInput.Multiple(pages)))
        } else {
            deliver(result)
        }
    }

    private val scannedPdfEditor = registerForActivityResult(ImageEditorContract()) { result ->
        scannedPages.forEach { FrameKitOutputs.deleteOutput(this, it) }
        scannedPages.clear()
        deliver(result)
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
        savedInstanceState?.let { BundleCompat.getParcelableArrayList(it, KEY_SCANNED, Uri::class.java) }?.let { scannedPages = it }
        val savedChooser = savedInstanceState?.let { BundleCompat.getParcelable(it, KEY_CHOOSER, EditorInput::class.java) }
        val savedStep = savedInstanceState?.getString(KEY_STEP)?.let { name -> Step.entries.firstOrNull { it.name == name } }
        val savedStepInput = savedInstanceState?.let { BundleCompat.getParcelable(it, KEY_STEP_INPUT, EditorInput::class.java) }
        val savedStepKinds = savedInstanceState?.getStringArrayList(KEY_STEP_KINDS).orEmpty()
            .mapNotNull { name -> SourceKind.entries.firstOrNull { it.name == name } }
        when {
            savedChooser != null -> showChooser(savedChooser)
            savedStep != null && savedStepInput != null -> resume(savedStep, savedStepInput, savedStepKinds)
            !launched -> {
                launched = true
                open(parsed.input)
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_LAUNCHED, launched)
        outState.putParcelableArrayList(KEY_SCANNED, scannedPages)
        chooserInput?.let { outState.putParcelable(KEY_CHOOSER, it) }
        val current = step
        val input = stepInput
        if (current != null && input != null) {
            outState.putString(KEY_STEP, current.name)
            outState.putParcelable(KEY_STEP_INPUT, input)
            outState.putStringArrayList(KEY_STEP_KINDS, ArrayList(stepKinds.map { it.name }))
        }
    }

    private fun resume(saved: Step, input: EditorInput, kinds: List<SourceKind>) {
        when (saved) {
            Step.INSPECT_MIXED -> (input as? EditorInput.Multiple)?.let(::inspectMixed)
                ?: deliver(FrameKitResult.Failure(EditorError(EditorErrorCode.UNSUPPORTED_OPERATION)))
            Step.CHECK_ANIMATED -> openImage(input)
            Step.SEND_ORIGINAL -> sendOriginals(input, kinds)
        }
    }

    private fun beginStep(next: Step, input: EditorInput, kinds: List<SourceKind> = emptyList()) {
        step = next
        stepInput = input
        stepKinds = kinds
    }

    private fun endStep() {
        step = null
        stepInput = null
        stepKinds = emptyList()
    }

    private fun open(input: EditorInput) {
        // 녹음은 음성 편집기가 바로 연다.
        if (input is EditorInput.Capture && input.kind == MediaKind.AUDIO) return launchEditor(MediaKind.AUDIO, input)
        // 사진을 찍어 PDF로 저장하면 문서 스캐너로 찍는다(테두리 자동 인식·여러 쪽).
        if (input is EditorInput.Capture && input.kind == MediaKind.IMAGE && request.imageExport.format == ImageFormat.PDF) {
            return scanner.launch(DocumentScanRequest(maxPages = DocumentScanRequest.MAX_PAGES, ui = request.ui))
        }
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
        val limit = maxItems.coerceIn(1, EditorInput.MAX_PICK_ITEMS)
        picker.launch(MediaPickerRequest(kind, maxItems = limit, mimeTypes = routerPickMimeTypes(request, kind, limit), style = request.ui.picker, ui = request.ui))
    }

    // 원본 종류를 보고 알맞은 편집기를 바로 연다(EditorChooser.AUTO).
    private fun openAutomatically(input: EditorInput) {
        val kind = MediaKindResolver.resolve(input, contentResolver)
        when {
            kind != null -> launchEditor(kind, input)
            // 사진과 영상이 섞였으면 혼합 편집으로 하나씩 편집한다. 소리가 섞이면 열지 않는다.
            input is EditorInput.Multiple && request.mixedSelection == MixedSelection.EDIT -> inspectMixed(input)
            input is EditorInput.Multiple -> deliver(FrameKitResult.Failure(EditorError(EditorErrorCode.UNSUPPORTED_OPERATION)))
            else -> deliver(FrameKitResult.Failure(EditorError(EditorErrorCode.UNSUPPORTED_FORMAT)))
        }
    }

    private fun inspectMixed(input: EditorInput.Multiple) {
        beginStep(Step.INSPECT_MIXED, input)
        lifecycleScope.launch {
            val kinds = withContext(Dispatchers.IO) { input.items.map { inspector.kind(it) } }
            endStep()
            val known = kinds.filterNotNull()
            val groups = known.map { it == SourceKind.VIDEO }.toSet()
            if (known.size == kinds.size && SourceKind.AUDIO !in known && groups.size == 2) {
                perform(EditorAction.EDIT_EACH, input, known)
            } else {
                deliver(FrameKitResult.Failure(EditorError(EditorErrorCode.UNSUPPORTED_OPERATION)))
            }
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
            val options = ChooserRules.options(known, request.chooserActions, mixedEdit = request.mixedSelection == MixedSelection.EDIT)
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
                EditorAction.EDIT_EACH -> {
                    val items = (if (input is EditorInput.Multiple) input.items else listOf(input)).take(MixedEditorRequest.MAX_ITEMS)
                    mixedEditor.launch(MixedEditorRequest(request, items, kinds.take(items.size).map { it == SourceKind.VIDEO }))
                }
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
        beginStep(Step.SEND_ORIGINAL, input, kinds)
        lifecycleScope.launch {
            val outputs = withContext(Dispatchers.IO) { items.zip(kinds).map { (item, kind) -> inspector.original(item, kind) } }
            endStep()
            if (outputs.any { it == null }) return@launch deliver(FrameKitResult.Failure(EditorError(EditorErrorCode.SOURCE_UNAVAILABLE)))
            val results = outputs.filterNotNull()
            deliver(FrameKitResult.Success(results.first(), results))
        }
    }

    private fun launchEditor(kind: MediaKind, input: EditorInput) {
        try {
            if (kind == MediaKind.IMAGE && request.openAnimatedInMotionEditor && input.isSingleSource) return openImage(input)
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

    // 파일을 읽어야 해서 메인 스레드 밖에서 움직이는 이미지인지 판단한 뒤 편집기를 연다.
    private fun openImage(input: EditorInput) {
        beginStep(Step.CHECK_ANIMATED, input)
        lifecycleScope.launch {
            val animated = withContext(Dispatchers.IO) { isAnimated(input) }
            endStep()
            try {
                if (animated) motionEditor.launch(request.forMotion(input)) else imageEditor.launch(request.forImage(input))
            } catch (_: RuntimeException) {
                deliver(FrameKitResult.Failure(EditorError(EditorErrorCode.INVALID_CONFIGURATION)))
            }
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
        endStep()
        val (code, data) = FrameKitResultCodec.encode(result)
        setResult(code, data)
        finish()
    }

    private companion object {
        const val KEY_LAUNCHED = "framekit_router_launched"
        const val KEY_SCANNED = "framekit_router_scanned"
        const val KEY_CHOOSER = "framekit_router_chooser"
        const val KEY_STEP = "framekit_router_step"
        const val KEY_STEP_INPUT = "framekit_router_step_input"
        const val KEY_STEP_KINDS = "framekit_router_step_kinds"
        const val MAX_THUMBNAILS = 6
        const val THUMBNAIL_PX = 192
    }
}

/**
 * [MediaPickerContract]와 같지만 고른 것이 모두 제한에 맞지 않아 빠졌으면 `null`을 돌려준다. 사용자가 닫은 경우와 구별해
 * 실패로 돌려주기 위해 쓴다.
 */
internal class RouterPickerContract : ActivityResultContract<MediaPickerRequest, MediaPickerResult?>() {
    private val delegate = MediaPickerContract()

    override fun createIntent(context: Context, input: MediaPickerRequest): Intent = delegate.createIntent(context, input)

    override fun parseResult(resultCode: Int, intent: Intent?): MediaPickerResult? {
        val result = delegate.parseResult(resultCode, intent)
        val skipped = intent?.getIntExtra(MediaPickerContract.EXTRA_SKIPPED, 0) ?: 0
        return if (result is MediaPickerResult.Cancelled && resultCode == Activity.RESULT_OK && skipped > 0) null else result
    }
}

/**
 * 라우터가 고르기 화면에 넘길 형식. 자동으로 열 편집기가 열 수 있는 사진 형식만 고르게 한다. 고르기 시트를 쓰면 동작마다
 * 받는 형식이 달라 거르지 않는다. 비어 있으면 거르지 않는다.
 */
internal fun routerPickMimeTypes(request: FrameKitRequest, kind: MediaKind, maxItems: Int): Set<String> {
    if (request.chooser == EditorChooser.ASK || kind == MediaKind.VIDEO || kind == MediaKind.AUDIO) return emptySet()
    // 한 장일 때만 GIF를 움직이는 이미지 편집기로 연다.
    val gif = if (maxItems == 1 && request.openAnimatedInMotionEditor) setOf("image/gif") else emptySet()
    val images = SupportedImageFormats.mimeTypes + gif
    return if (kind == MediaKind.ANY) images + "video/*" else images
}
