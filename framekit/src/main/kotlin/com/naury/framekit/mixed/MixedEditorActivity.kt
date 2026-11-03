package com.naury.framekit.mixed

import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.os.Parcelable
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.IntentCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import com.naury.framekit.FrameKitRequest
import com.naury.framekit.R
import com.naury.framekit.android.capture.CaptureFiles
import com.naury.framekit.android.input.EditorInput
import com.naury.framekit.android.output.FrameKitOutputs
import com.naury.framekit.android.result.EditedMedia
import com.naury.framekit.android.result.EditorError
import com.naury.framekit.android.result.EditorErrorCode
import com.naury.framekit.android.result.FrameKitResult
import com.naury.framekit.android.result.FrameKitResultCodec
import com.naury.framekit.chooser.SourceInspector
import com.naury.framekit.core.validation.ValidationResult
import com.naury.framekit.image.catalog.CatalogAssets
import com.naury.framekit.ui.catalog.CatalogUi
import com.naury.framekit.ui.catalog.LocalCatalogUi
import com.naury.framekit.ui.component.DialogAction
import com.naury.framekit.ui.component.DialogActionStyle
import com.naury.framekit.ui.component.DiscardChangesDialog
import com.naury.framekit.ui.component.EmbeddedEditor
import com.naury.framekit.ui.component.EmbeddedEditorState
import com.naury.framekit.ui.component.ExportErrorDialog
import com.naury.framekit.ui.component.ExportOverlay
import com.naury.framekit.ui.component.ExportStageUi
import com.naury.framekit.ui.component.FrameKitDialog
import com.naury.framekit.ui.component.FrameKitNavigationBar
import com.naury.framekit.ui.component.LocalEditorEmbedded
import com.naury.framekit.ui.component.NavigationIcon
import com.naury.framekit.ui.config.ThemeMode
import com.naury.framekit.ui.design.FrameKitTheme
import com.naury.framekit.ui.design.ProvideEditorLocale
import com.naury.framekit.ui.image.editor.ImageEditorEmbedding
import com.naury.framekit.ui.layout.FoldPosture
import com.naury.framekit.ui.layout.foldPostureFlow
import com.naury.framekit.ui.video.editor.VideoEditorEmbedding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.parcelize.Parcelize
import com.naury.framekit.ui.R as UiR

/**
 * 섞어 고른 사진·영상. [videos]는 [items]와 같은 순서로 영상인지를 담는다.
 */
@Parcelize
internal data class MixedEditorRequest(
    val request: FrameKitRequest,
    val items: List<EditorInput>,
    val videos: List<Boolean>,
) : Parcelable {
    companion object {
        /** 한 번에 편집할 수 있는 최대 수. */
        const val MAX_ITEMS = 20
    }
}

internal class MixedEditorContract : ActivityResultContract<MixedEditorRequest, FrameKitResult>() {
    override fun createIntent(context: Context, input: MixedEditorRequest): Intent =
        Intent(context, MixedEditorActivity::class.java).putExtra(EXTRA_REQUEST, input)

    override fun parseResult(resultCode: Int, intent: Intent?): FrameKitResult = FrameKitResultCodec.decode(resultCode, intent)

    companion object {
        const val EXTRA_REQUEST = "com.naury.framekit.extra.MIXED_REQUEST"
    }
}

/** 저장 진행. */
internal sealed interface MixedSave {
    data class Running(val step: Int, val total: Int) : MixedSave
    data object Cancelling : MixedSave
    data class Failed(val code: EditorErrorCode) : MixedSave
}

/**
 * 혼합 편집 상태. 항목마다 에디터를 하나씩 두고, 저장은 차례로 각 에디터의 저장을 불러 결과를 모은다. 화면 회전에도 저장이
 * 이어지도록 Activity가 아니라 여기서 돌린다.
 *
 * 저장을 마친 항목은 에디터 세션이 끝나 다시 저장할 수 없으므로 고칠 수 없게 잠근다. 저장한 결과는 [SavedStateHandle]에 두어
 * 프로세스가 다시 시작돼도 같은 파일을 결과에 넣고 다시 만들지 않는다. 원본을 열지 못한 항목은 빼고 저장한다.
 */
internal class MixedEditorViewModel(application: Application, private val savedState: SavedStateHandle) : AndroidViewModel(application) {
    private var editors: List<EmbeddedEditor> = emptyList()
    private val outputs = mutableMapOf<Int, List<EditedMedia>>().apply {
        for (index in 0 until MixedEditorRequest.MAX_ITEMS) {
            savedState.get<ArrayList<EditedMedia>>(outputKey(index))?.let { put(index, it) }
        }
    }

    val selected: StateFlow<Int> = savedState.getStateFlow(KEY_SELECTED, 0)
    private val _save = MutableStateFlow<MixedSave?>(null)
    val save: StateFlow<MixedSave?> = _save.asStateFlow()
    private val _saved = MutableStateFlow(outputs.keys.toSet())
    val saved: StateFlow<Set<Int>> = _saved.asStateFlow()
    private val _showDiscard = MutableStateFlow(false)
    val showDiscard: StateFlow<Boolean> = _showDiscard.asStateFlow()

    /** 열지 못한 항목을 빼고 저장할지 묻는 중이다. */
    private val _confirmSkip = MutableStateFlow(false)
    val confirmSkip: StateFlow<Boolean> = _confirmSkip.asStateFlow()
    private val _result = MutableStateFlow<FrameKitResult?>(null)
    val result: StateFlow<FrameKitResult?> = _result.asStateFlow()

    /** 처음 한 번만 붙인다. 화면 회전 뒤에도 같은 에디터를 계속 쓴다. */
    fun attach(created: List<EmbeddedEditor>) {
        if (editors.isEmpty()) editors = created
    }

    fun select(index: Int) {
        if (_save.value != null || index !in editors.indices) return
        editors.getOrNull(selected.value)?.onStop()
        savedState[KEY_SELECTED] = index
    }

    /** 저장을 시작한다. 열지 못한 항목이 있으면 먼저 빼고 저장할지 묻는다. */
    fun saveAll() {
        if (_save.value != null || editors.getOrNull(selected.value)?.state?.value?.toolOpen == true) return
        if (editors.indices.any { it !in outputs && editors[it].state.value.failed }) {
            _confirmSkip.value = true
            return
        }
        startSave()
    }

    /** 열지 못한 항목을 빼고 저장한다. 모두 열지 못했으면 첫 항목의 실패 이유로 끝낸다. */
    fun confirmSkip() {
        _confirmSkip.value = false
        val failed = editors.filter { it.state.value.failed }
        if (failed.size == editors.size) {
            val code = failed.firstNotNullOfOrNull { it.state.value.failure } ?: EditorErrorCode.SOURCE_UNAVAILABLE
            return finish(FrameKitResult.Failure(EditorError(code)))
        }
        startSave()
    }

    fun dismissSkip() {
        _confirmSkip.value = false
    }

    private fun startSave() {
        if (_save.value != null) return
        editors.getOrNull(selected.value)?.onStop()
        viewModelScope.launch {
            val pending = editors.indices.filter { it !in outputs && !editors[it].state.value.failed }
            for ((step, index) in pending.withIndex()) {
                _save.value = MixedSave.Running(step + 1, pending.size)
                when (val outcome = saveOne(editors[index])) {
                    is Outcome.Saved -> {
                        outputs[index] = outcome.outputs
                        savedState[outputKey(index)] = ArrayList(outcome.outputs)
                        _saved.value = outputs.keys.toSet()
                    }
                    Outcome.Cancelled -> {
                        _save.value = null
                        return@launch
                    }
                    is Outcome.Failed -> {
                        savedState[KEY_SELECTED] = index
                        _save.value = MixedSave.Failed(outcome.code)
                        return@launch
                    }
                }
            }
            val all = editors.indices.flatMap { outputs[it].orEmpty() }
            _save.value = null
            finish(if (all.isEmpty()) FrameKitResult.Failure(EditorError(EditorErrorCode.UNKNOWN)) else FrameKitResult.Success(all.first(), all))
        }
    }

    private sealed interface Outcome {
        data class Saved(val outputs: List<EditedMedia>) : Outcome
        data object Cancelled : Outcome
        data class Failed(val code: EditorErrorCode) : Outcome
    }

    // 에디터의 저장을 부르고 끝날 때까지 상태를 지켜본다. 에디터는 저장을 마치면 결과를 내고 스스로 세션을 끝낸다.
    private suspend fun saveOne(editor: EmbeddedEditor): Outcome {
        if (!editor.state.value.ready) return Outcome.Failed(editor.state.value.failure ?: EditorErrorCode.SOURCE_UNAVAILABLE)
        editor.save()
        var started = false
        var waited = 0
        while (true) {
            when (val result = editor.result.value) {
                is FrameKitResult.Success -> return Outcome.Saved(result.outputs)
                is FrameKitResult.Failure -> return Outcome.Failed(result.error.code)
                FrameKitResult.Cancelled -> return Outcome.Cancelled
                null -> Unit
            }
            val state = editor.state.value
            state.exportFailure?.let { code ->
                editor.dismissExportError()
                return Outcome.Failed(code)
            }
            if (state.exporting) started = true
            // 저장이 시작됐다가 결과 없이 멈췄으면 취소된 것이다. 시작조차 안 하면(초안 적용 안내 등) 멈춘다.
            if ((started || waited > START_TIMEOUT_MS) && !state.exporting) return Outcome.Cancelled
            delay(POLL_MS)
            waited += POLL_MS.toInt()
        }
    }

    fun cancelSave() {
        if (_save.value !is MixedSave.Running) return
        _save.value = MixedSave.Cancelling
        editors.forEach(EmbeddedEditor::cancelExport)
    }

    fun dismissSaveError() {
        _save.value = null
    }

    fun requestClose() {
        when {
            _save.value is MixedSave.Running -> cancelSave()
            editors.any { it.state.value.dirty } || outputs.isNotEmpty() -> _showDiscard.value = true
            else -> finish(FrameKitResult.Cancelled)
        }
    }

    fun dismissDiscard() {
        _showDiscard.value = false
    }

    fun confirmDiscard() {
        _showDiscard.value = false
        // 이미 저장한 파일은 결과로 넘기지 않으므로 지운다.
        outputs.values.flatten().forEach { FrameKitOutputs.deleteOutput(getApplication(), it.uri) }
        outputs.keys.forEach { savedState.remove<ArrayList<EditedMedia>>(outputKey(it)) }
        outputs.clear()
        finish(FrameKitResult.Cancelled)
    }

    fun onStop() = editors.forEach(EmbeddedEditor::onStop)

    private fun finish(result: FrameKitResult) {
        if (_result.value != null) return
        editors.filter { it.result.value == null }.forEach(EmbeddedEditor::discard)
        // 실패로 끝나면 결과로 넘기지 않는 저장 파일을 지운다.
        if (result !is FrameKitResult.Success) {
            outputs.values.flatten().forEach { FrameKitOutputs.deleteOutput(getApplication(), it.uri) }
        }
        _result.value = result
    }

    private companion object {
        fun outputKey(index: Int) = "framekit_mixed_output_$index"
        const val KEY_SELECTED = "framekit_mixed_selected"
        const val POLL_MS = 100L
        const val START_TIMEOUT_MS = 2_000
    }
}

/** 혼합 편집 화면. [MixedEditorContract]로 실행하며 exported되지 않는다. */
internal class MixedEditorActivity : ComponentActivity() {
    private var delivered = false
    private val host: MixedEditorViewModel by viewModels()
    private val thumbnails = mutableStateListOf<Bitmap?>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mixed = IntentCompat.getParcelableExtra(intent, MixedEditorContract.EXTRA_REQUEST, MixedEditorRequest::class.java)
        if (mixed == null || mixed.items.isEmpty() || mixed.items.size != mixed.videos.size || mixed.request.validate() is ValidationResult.Invalid) {
            deliver(FrameKitResult.Failure(EditorError(EditorErrorCode.INVALID_CONFIGURATION)))
            return
        }
        val request = mixed.request
        CatalogAssets.install(this, request.catalog)
        val catalogUi = CatalogUi(request.catalog, CatalogAssets::sticker, CatalogAssets::typeface)
        lifecycleScope.launch(Dispatchers.IO) { runCatching { CaptureFiles.deleteStale(applicationContext) } }
        val darkBars = request.ui.themeMode != ThemeMode.LIGHT
        enableEdgeToEdge(
            statusBarStyle = if (darkBars) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = if (darkBars) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
        )

        // 항목마다 에디터 하나. 영상 항목은 일반 영상 편집기처럼 나누고 클립을 더할 수 있고, 결과는 항목마다 하나다.
        val items = mixed.items.take(MixedEditorRequest.MAX_ITEMS)
        val editors = items.mapIndexed { index, item ->
            val key = "framekit_mixed_item_$index"
            if (mixed.videos[index]) {
                VideoEditorEmbedding.create(this, request.forVideo(item), key)
            } else {
                ImageEditorEmbedding.create(this, request.forImage(item), key)
            }
        }
        host.attach(editors)
        lifecycleScope.launch { deliver(host.result.filterNotNull().first()) }

        val inspector = SourceInspector(applicationContext)
        repeat(items.size) { thumbnails.add(null) }
        lifecycleScope.launch {
            items.forEachIndexed { index, item ->
                thumbnails[index] = withContext(Dispatchers.IO) { inspector.thumbnail(item, THUMBNAIL_PX) }
            }
        }

        val posture = foldPostureFlow(this)
        setContent {
            val currentPosture by posture.collectAsState(FoldPosture.Flat)
            CompositionLocalProvider(LocalCatalogUi provides catalogUi) {
                ProvideEditorLocale(request.ui.localeTag) {
                    FrameKitTheme(request.ui) {
                        MixedEditorScreen(host, editors, mixed.videos, thumbnails, currentPosture)
                    }
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        host.onStop()
    }

    private fun deliver(result: FrameKitResult) {
        if (delivered) return
        delivered = true
        val (code, data) = FrameKitResultCodec.encode(result)
        setResult(code, data)
        finish()
    }

    private companion object {
        const val THUMBNAIL_PX = 192
    }
}

@Composable
private fun MixedEditorScreen(
    host: MixedEditorViewModel,
    editors: List<EmbeddedEditor>,
    videos: List<Boolean>,
    thumbnails: List<Bitmap?>,
    posture: FoldPosture,
) {
    val colors = FrameKitTheme.colors
    val selected by host.selected.collectAsState()
    val save by host.save.collectAsState()
    val saved by host.saved.collectAsState()
    val showDiscard by host.showDiscard.collectAsState()
    val confirmSkip by host.confirmSkip.collectAsState()
    val states = editors.map { it.state.collectAsState().value }
    val current = editors.getOrNull(selected) ?: return
    val toolOpen = states.getOrNull(selected)?.toolOpen == true
    val holder = rememberSaveableStateHolder()

    BackHandler(enabled = !toolOpen) { host.requestClose() }
    // system bar 인셋은 여기서 한 번만 처리한다. 안의 에디터는 이미 처리된 인셋을 받는다.
    Column(Modifier.fillMaxSize().background(colors.background).windowInsetsPadding(WindowInsets.safeDrawing)) {
        if (!toolOpen) {
            FrameKitNavigationBar(
                title = "${selected + 1} / ${editors.size}",
                navigationIcon = NavigationIcon.CLOSE,
                onNavigate = host::requestClose,
                actionLabel = stringResource(UiR.string.framekit_action_save),
                actionEnabled = save == null,
                onAction = host::saveAll,
            )
        }
        val locked = selected in saved
        val lockedNotice = stringResource(R.string.framekit_mixed_saved_locked)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Box(if (locked) Modifier.fillMaxSize().clearAndSetSemantics { contentDescription = lockedNotice } else Modifier.fillMaxSize()) {
                holder.SaveableStateProvider("item-$selected") {
                    CompositionLocalProvider(LocalEditorEmbedded provides true) {
                        current.Content(posture)
                    }
                }
            }
            // 저장을 마친 항목은 에디터 세션이 끝나 고쳐도 결과에 담기지 않으므로 입력을 막는다.
            if (locked) {
                Box(Modifier.fillMaxSize().pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) awaitPointerEvent().changes.forEach { it.consume() }
                    }
                })
                Text(
                    lockedNotice,
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.foreground,
                    modifier = Modifier.align(Alignment.TopCenter).padding(12.dp)
                        .background(colors.surface, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
        if (!toolOpen) {
            ItemStrip(selected, videos, thumbnails, states, saved, host::select)
        }
    }

    when (val state = save) {
        is MixedSave.Running -> ExportOverlay(ExportStageUi.EXPORTING, onCancel = host::cancelSave, progress = (state.step - 1f) / state.total)
        MixedSave.Cancelling -> ExportOverlay(ExportStageUi.CANCELLING, onCancel = {})
        is MixedSave.Failed -> ExportErrorDialog(
            code = state.code,
            onRetry = {
                host.dismissSaveError()
                host.saveAll()
            },
            onDismiss = host::dismissSaveError,
        )
        null -> Unit
    }
    if (showDiscard) DiscardChangesDialog(onDiscard = host::confirmDiscard, onKeepEditing = host::dismissDiscard)
    if (confirmSkip) {
        FrameKitDialog(
            title = stringResource(R.string.framekit_mixed_skip_title),
            message = stringResource(R.string.framekit_mixed_skip_message),
            icon = Icons.Filled.Warning,
            onDismiss = host::dismissSkip,
            actions = listOf(
                DialogAction(stringResource(UiR.string.framekit_action_save), DialogActionStyle.PRIMARY, onClick = host::confirmSkip),
                DialogAction(stringResource(UiR.string.framekit_action_keep_editing), DialogActionStyle.SECONDARY, onClick = host::dismissSkip),
            ),
        )
    }
}

@Composable
private fun ItemStrip(
    selected: Int,
    videos: List<Boolean>,
    thumbnails: List<Bitmap?>,
    states: List<EmbeddedEditorState>,
    saved: Set<Int>,
    onSelect: (Int) -> Unit,
) {
    val colors = FrameKitTheme.colors
    LazyRow(
        Modifier.fillMaxWidth().background(colors.surface),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        itemsIndexed(videos) { index, video ->
            val isSelected = index == selected
            val label = stringResource(if (video) R.string.framekit_chooser_video else R.string.framekit_chooser_photo)
            Box(
                Modifier
                    .size(60.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(colors.raised)
                    .border(if (isSelected) 2.5.dp else 0.dp, colors.accent, RoundedCornerShape(10.dp))
                    .clickable { onSelect(index) }
                    .semantics {
                        contentDescription = "$label ${index + 1}/${videos.size}"
                        this.selected = isSelected
                    },
            ) {
                thumbnails.getOrNull(index)?.let {
                    Image(it.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
                // 영상에만 재생 표시를 붙여 사진과 구분한다.
                if (video) Icon(
                    Icons.Filled.PlayArrow,
                    contentDescription = null,
                    tint = ComposeColor.White,
                    modifier = Modifier.align(Alignment.BottomStart).padding(4.dp).size(18.dp)
                        .background(ComposeColor.Black.copy(alpha = 0.55f), CircleShape).padding(2.dp),
                )
                if (index in saved) {
                    Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = colors.accent, modifier = Modifier.align(Alignment.TopEnd).padding(3.dp).size(18.dp))
                } else if (states.getOrNull(index)?.dirty == true) {
                    Box(Modifier.align(Alignment.TopEnd).padding(5.dp).size(8.dp).background(colors.accent, CircleShape))
                }
            }
        }
    }
}
