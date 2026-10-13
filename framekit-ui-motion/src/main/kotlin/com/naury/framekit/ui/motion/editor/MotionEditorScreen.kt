package com.naury.framekit.ui.motion.editor

import com.naury.framekit.core.video.AudioRole
import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.naury.framekit.core.model.PixelSize
import com.naury.framekit.core.overlay.ImageOverlay
import com.naury.framekit.image.overlay.OverlayRenderer
import com.naury.framekit.motion.model.MotionKind
import com.naury.framekit.motion.render.MotionMedia
import com.naury.framekit.motion.render.MotionTimeline
import com.naury.framekit.ui.component.ApplyDraftDialog
import com.naury.framekit.ui.component.DiscardChangesDialog
import com.naury.framekit.ui.component.EditorErrorView
import com.naury.framekit.ui.component.EditorLoadingView
import com.naury.framekit.ui.component.EditorTopBar
import com.naury.framekit.ui.component.ExportErrorDialog
import com.naury.framekit.ui.component.ExportOverlay
import com.naury.framekit.ui.component.ExportStageUi
import com.naury.framekit.ui.component.FrameKitSnackbarHost
import com.naury.framekit.ui.component.HistoryControls
import com.naury.framekit.ui.component.ToolActionBar
import com.naury.framekit.ui.component.ToolGrid
import com.naury.framekit.ui.component.ToolRail
import com.naury.framekit.ui.component.ToolRailItem
import com.naury.framekit.ui.component.verticalFadingScroll
import com.naury.framekit.ui.design.FrameKitTheme
import com.naury.framekit.ui.layout.EditorLayout
import com.naury.framekit.ui.layout.EditorLayoutPolicy
import com.naury.framekit.ui.layout.FoldPosture
import com.naury.framekit.ui.motion.R
import com.naury.framekit.ui.motion.contract.MotionTool
import com.naury.framekit.ui.tool.AdjustToolPanel
import com.naury.framekit.ui.tool.FilterToolPanel
import com.naury.framekit.ui.tool.StickerToolPanel
import com.naury.framekit.ui.tool.TextToolPanel
import java.util.Locale
import kotlin.math.abs
import com.naury.framekit.android.input.MediaKind
import com.naury.framekit.ui.picker.MediaPickerContract
import com.naury.framekit.ui.picker.MediaPickerRequest
import com.naury.framekit.ui.picker.MediaPickerResult
import com.naury.framekit.ui.R as UiR

@Composable
internal fun MotionEditorScreen(viewModel: MotionEditorViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var pickerLaunched by rememberSaveable { mutableStateOf(false) }
    val ui = FrameKitTheme.config
    val picker = rememberLauncherForActivityResult(MediaPickerContract()) { result ->
        pickerLaunched = false
        viewModel.onPicked((result as? MediaPickerResult.Picked)?.items?.map { it.uri }.orEmpty())
    }
    BackHandler { viewModel.onBack() }
    Box(Modifier.fillMaxSize().background(FrameKitTheme.colors.background)) {
        when (val current = state) {
            is MotionEditorUiState.AwaitingSource -> LaunchedEffect(current) {
                if (!pickerLaunched) {
                    pickerLaunched = true
                    picker.launch(MediaPickerRequest(MediaKind.IMAGE, maxItems = current.maxItems.coerceAtLeast(1), style = ui.picker, ui = ui))
                }
            }
            MotionEditorUiState.Loading -> EditorLoadingView(Modifier.windowInsetsPadding(WindowInsets.safeDrawing))
            is MotionEditorUiState.LoadFailed -> EditorErrorView(
                code = current.code,
                onClose = viewModel::requestClose,
                onChooseAnother = if (current.canChooseAnother) viewModel::chooseAnother else null,
                modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing),
            )
            is MotionEditorUiState.Ready -> ReadyContent(current, viewModel)
        }
    }
}

@Composable
private fun ReadyContent(state: MotionEditorUiState.Ready, viewModel: MotionEditorViewModel) {
    val snackbar = remember { SnackbarHostState() }
    val noticeText = state.notice?.let {
        stringResource(
            when (it) {
                MotionNotice.SLIDE_LIMIT -> R.string.framekit_motion_notice_slide_limit
                MotionNotice.FRAMES_TRIMMED -> R.string.framekit_motion_notice_frames_trimmed
                MotionNotice.AUDIO_FAILED -> R.string.framekit_motion_notice_audio_failed
                MotionNotice.RESTORED -> R.string.framekit_motion_notice_restored
            },
        )
    }
    LaunchedEffect(state.notice) {
        if (noticeText != null) {
            snackbar.showSnackbar(noticeText)
            viewModel.dismissNotice()
        }
    }
    val topBar = @Composable {
        val tool = state.activeTool
        EditorTopBar(
            onClose = if (tool != null) viewModel::onBack else viewModel::requestClose,
            onSave = viewModel::requestSave,
            saveEnabled = state.export == null,
            toolTitle = tool?.label(),
        )
    }
    BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        val wide = EditorLayoutPolicy.decide(maxWidth.value, maxHeight.value, FoldPosture.Flat).let {
            it is EditorLayout.SidePanel || it is EditorLayout.SplitAtVerticalHinge
        }
        val available = maxHeight
        if (wide) {
            Row(Modifier.fillMaxSize()) {
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    PreviewArea(state, viewModel, snackbar, Modifier.weight(1f).fillMaxWidth())
                    PlaybackRow(state, viewModel)
                    AudioLanes(state, viewModel)
                }
                Column(Modifier.width(PANEL_WIDTH.dp).fillMaxHeight().background(FrameKitTheme.colors.background)) {
                    topBar()
                    ToolArea(state, viewModel, wide = true, modifier = Modifier.weight(1f))
                }
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                topBar()
                PreviewArea(state, viewModel, snackbar, Modifier.weight(1f).fillMaxWidth())
                PlaybackRow(state, viewModel)
                AudioLanes(state, viewModel)
                ToolArea(state, viewModel, wide = false, modifier = Modifier.fillMaxWidth().heightIn(max = available * MAX_TOOL_FRACTION))
            }
        }
    }

    when (val export = state.export) {
        is MotionExportUiState.Running -> ExportOverlay(
            stage = if (export.cancelling) ExportStageUi.CANCELLING else ExportStageUi.RENDERING,
            onCancel = viewModel::cancelExport,
            progress = export.progress,
        )
        is MotionExportUiState.Failed -> ExportErrorDialog(code = export.code, onRetry = {
            viewModel.dismissExportError()
            viewModel.requestSave()
        }, onDismiss = viewModel::dismissExportError)
        null -> Unit
    }
    if (state.showSaveSheet) {
        SaveSheet(viewModel.exportConfig, hasSound = state.displayed.audio.isNotEmpty(), onSave = viewModel::save, onDismiss = viewModel::dismissSaveSheet)
    }
    if (state.showDraftDialog) {
        ApplyDraftDialog(
            toolName = state.activeTool?.label().orEmpty(),
            onApply = viewModel::applyDraftFromDialog,
            onDiscard = viewModel::discardDraftFromDialog,
            onKeepEditing = viewModel::dismissDraftDialog,
        )
    }
    if (state.showDiscardDialog) DiscardChangesDialog(onDiscard = viewModel::confirmDiscard, onKeepEditing = viewModel::dismissDiscard)
}

@Composable
private fun PreviewArea(state: MotionEditorUiState.Ready, viewModel: MotionEditorViewModel, snackbar: SnackbarHostState, modifier: Modifier) {
    val frame by viewModel.frame.collectAsStateWithLifecycle()
    Box(modifier) {
        MotionCanvas(state, frame, viewModel, Modifier.fillMaxSize())
        if (state.activeTool != MotionTool.TEXT) {
            val history = state.transaction.history
            HistoryControls(
                canUndo = history.canUndo && !state.transaction.isActive,
                canRedo = history.canRedo && !state.transaction.isActive,
                showUndo = viewModel.config.allowUndo,
                showRedo = viewModel.config.allowRedo,
                onUndo = viewModel::undo,
                onRedo = viewModel::redo,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 12.dp),
            )
        }
        FrameKitSnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(8.dp))
    }
}

/**
 * 미리보기와 손가락 조작. 텍스트·스티커 도구에서는 항목을 눌러 고르고 끌기·두 손가락으로 크기·회전을 바꾼다.
 */
@Composable
private fun MotionCanvas(state: MotionEditorUiState.Ready, frame: Bitmap?, viewModel: MotionEditorViewModel, modifier: Modifier) {
    var boxSize by remember { mutableStateOf(PixelSize(1, 1)) }
    val touchSlop = LocalViewConfiguration.current.touchSlop
    val accent = FrameKitTheme.colors.accent
    val overlayRenderer = remember { OverlayRenderer() }
    // 미리보기 그림이 상자 안에서 차지하는 자리(px). 프레임마다 비트맵이 바뀌어도 크기가 같으면 같은 값을 쓴다.
    val frameWidth = frame?.width ?: 0
    val frameHeight = frame?.height ?: 0
    val frameRect = remember(boxSize, frameWidth, frameHeight) { if (frameWidth > 0) fitRect(boxSize, frameWidth, frameHeight) else null }
    // 제스처 처리 중에 상태가 바뀌어도 손가락 처리가 다시 시작되지 않도록 최신 값을 따로 읽는다.
    val currentRect by rememberUpdatedState(frameRect)
    val currentState by rememberUpdatedState(state)
    Box(
        modifier
            .onSizeChanged {
                boxSize = PixelSize(it.width.coerceAtLeast(1), it.height.coerceAtLeast(1))
                viewModel.onViewport(it.width, it.height)
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { position ->
                        val rect = currentRect ?: return@detectTapGestures
                        viewModel.tapCanvas(((position.x - rect[0]) / rect[2]).toDouble(), ((position.y - rect[1]) / rect[3]).toDouble(), PixelSize(rect[2].toInt(), rect[3].toInt()))
                    },
                    onDoubleTap = { position ->
                        val rect = currentRect ?: return@detectTapGestures
                        val canvas = PixelSize(rect[2].toInt(), rect[3].toInt())
                        val x = ((position.x - rect[0]) / rect[2]).toDouble() * canvas.width
                        val y = ((position.y - rect[1]) / rect[3]).toDouble() * canvas.height
                        val texts = currentState.displayed.overlays.map { it.overlay }
                        overlayRenderer.hitTest(texts, x, y, canvas, 24.0)?.let(viewModel::editText)
                    },
                )
            }
            .pointerInput(state.activeTool) {
                if (state.activeTool != MotionTool.TEXT && state.activeTool != MotionTool.STICKER) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val rect = currentRect ?: return@awaitEachGesture
                    var started = false
                    var travelled = 0f
                    var panX = 0f
                    var panY = 0f
                    var zoom = 1f
                    var rotation = 0f
                    // 제스처가 중간에 끊겨도(화면 회전 등) 초안이 열린 채 남지 않게 끝을 꼭 알린다.
                    try {
                    do {
                        val event = awaitPointerEvent()
                        val pan = event.calculatePan()
                        val z = event.calculateZoom()
                        val r = event.calculateRotation()
                        if (!started) {
                            travelled += event.changes.firstOrNull()?.positionChange()?.getDistance() ?: 0f
                            if (travelled > touchSlop || abs(z - 1f) > 0.02f || abs(r) > 2f) {
                                val canvas = PixelSize(rect[2].toInt(), rect[3].toInt())
                                started = viewModel.beginOverlayGesture(((down.position.x - rect[0]) / rect[2]).toDouble(), ((down.position.y - rect[1]) / rect[3]).toDouble(), canvas)
                                if (!started) break
                            }
                        }
                        if (started) {
                            panX += pan.x
                            panY += pan.y
                            zoom *= z
                            rotation += r
                            viewModel.updateOverlayGesture((panX / rect[2]).toDouble(), (panY / rect[3]).toDouble(), zoom.toDouble(), rotation.toDouble())
                            event.changes.forEach { it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                    } finally {
                        if (started) viewModel.finishOverlayGesture()
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        if (frame != null && !frame.isRecycled) {
            Image(frame.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        }
        // 고른 텍스트·스티커 둘레에 테두리를 그려 무엇을 고쳤는지 보이게 한다.
        val selected = state.displayed.overlays.firstOrNull { it.overlay.id == state.selectedOverlayId }?.overlay
        if (selected != null && frameRect != null && (state.activeTool == MotionTool.STICKER || state.activeTool == MotionTool.TEXT)) {
            Canvas(Modifier.fillMaxSize()) {
                val canvas = PixelSize(frameRect[2].toInt().coerceAtLeast(1), frameRect[3].toInt().coerceAtLeast(1))
                val corners = overlayRenderer.corners(selected, canvas)
                if (corners.size == 4) {
                    val path = Path().apply {
                        corners.forEachIndexed { i, p ->
                            val point = Offset(frameRect[0] + p.x.toFloat(), frameRect[1] + p.y.toFloat())
                            if (i == 0) moveTo(point.x, point.y) else lineTo(point.x, point.y)
                        }
                        close()
                    }
                    drawPath(path, accent, style = Stroke(width = 2.dp.toPx()))
                }
            }
        }
    }
}

// 상자 안에 비율을 지켜 가운데 맞춘 사각형 [left, top, width, height].
private fun fitRect(box: PixelSize, width: Int, height: Int): FloatArray {
    val scale = minOf(box.width.toFloat() / width, box.height.toFloat() / height)
    val w = width * scale
    val h = height * scale
    return floatArrayOf((box.width - w) / 2, (box.height - h) / 2, w.coerceAtLeast(1f), h.coerceAtLeast(1f))
}

/** 소리를 넣었거나 소리 도구를 열면 재생 줄 아래에 배경 음악·음성 줄을 보여 준다. */
@Composable
private fun AudioLanes(state: MotionEditorUiState.Ready, viewModel: MotionEditorViewModel) {
    if (state.displayed.audio.isEmpty() && state.activeTool != MotionTool.AUDIO) return
    if (MotionTool.AUDIO !in viewModel.config.enabledTools) return
    val waveforms by viewModel.waveforms.collectAsStateWithLifecycle()
    val musicPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> viewModel.addMusic(uri) }
    // 소리 조각은 소리 도구에서 고친다. 다른 도구가 열려 있으면 줄은 보여 주기만 한다.
    val editable = state.activeTool == null || state.activeTool == MotionTool.AUDIO
    fun openAudioTool() {
        if (state.activeTool == null) viewModel.selectTool(MotionTool.AUDIO)
    }
    MotionAudioLanes(
        audio = state.displayed.audio,
        names = state.audioSources.mapValues { it.value.name },
        waveforms = waveforms,
        selectedId = if (state.activeTool == MotionTool.AUDIO) state.selectedAudioId else null,
        durationMs = durationOf(state),
        positionMs = state.positionMs,
        labels = mapOf(AudioRole.MUSIC to stringResource(UiR.string.framekit_track_music), AudioRole.VOICE to stringResource(UiR.string.framekit_track_voice)),
        addLabels = mapOf(AudioRole.MUSIC to stringResource(UiR.string.framekit_track_music_add), AudioRole.VOICE to stringResource(UiR.string.framekit_track_voice_add)),
        onSelect = { id ->
            if (editable) {
                openAudioTool()
                viewModel.selectAudio(id)
            }
        },
        onAdd = { role ->
            if (editable) {
                // 음악은 바로 고르고, 음성은 녹음도 할 수 있도록 소리 도구를 연다.
                if (role == AudioRole.MUSIC) musicPicker.launch(arrayOf("audio/*")) else openAudioTool()
            }
        },
        onBeginDrag = { id, mode -> editable && state.activeTool == MotionTool.AUDIO && viewModel.beginAudioDrag(id, mode) },
        onDrag = viewModel::dragAudio,
        onEndDrag = viewModel::endAudioDrag,
        onSeek = viewModel::seekTo,
    )
}

@Composable
private fun PlaybackRow(state: MotionEditorUiState.Ready, viewModel: MotionEditorViewModel) {
    val colors = FrameKitTheme.colors
    val duration = remember(state.displayed, state.sourceDurationMs) { durationOf(state) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = viewModel::togglePlayback) {
            Icon(
                painterResource(if (state.playing) UiR.drawable.framekit_ic_pause else UiR.drawable.framekit_ic_play),
                contentDescription = stringResource(if (state.playing) R.string.framekit_motion_pause else R.string.framekit_motion_play),
                tint = colors.foreground,
            )
        }
        Slider(
            value = state.positionMs.toFloat().coerceIn(0f, duration.toFloat().coerceAtLeast(1f)),
            onValueChange = { viewModel.seekTo(it.toLong()) },
            valueRange = 0f..duration.toFloat().coerceAtLeast(1f),
            colors = SliderDefaults.colors(thumbColor = colors.accent, activeTrackColor = colors.accent, inactiveTrackColor = colors.raised),
            modifier = Modifier.weight(1f),
        )
        Text(
            stringResource(R.string.framekit_motion_time, seconds(state.positionMs), seconds(duration)),
            color = colors.foregroundMuted,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(start = 8.dp, end = 8.dp),
        )
    }
}

// 미리보기 길이는 원본 비트맵 없이 비율만으로 계산한다.
private fun durationOf(state: MotionEditorUiState.Ready): Long {
    val project = state.displayed
    return when (project.kind) {
        MotionKind.SLIDESHOW -> MotionTimeline.durationMs(project, MotionMedia(project.kind, project.sourceIds.map { 1.0 }, { error("unused") }, null))
        MotionKind.ANIMATION -> {
            val end = (project.trimEndMs ?: state.sourceDurationMs).coerceAtMost(state.sourceDurationMs)
            val base = ((end - project.trimStartMs).coerceAtLeast(1) / project.speed).toLong()
            if (project.loop == com.naury.framekit.motion.model.LoopMode.BOOMERANG) base * 2 else base
        }
    }
}

internal fun seconds(ms: Long): String = String.format(Locale.ROOT, "%.1fs", ms / 1000.0)

@Composable
private fun ToolArea(state: MotionEditorUiState.Ready, viewModel: MotionEditorViewModel, wide: Boolean, modifier: Modifier) {
    val project = state.displayed
    val tools = viewModel.config.enabledTools.filter { tool ->
        when (tool) {
            MotionTool.MOTION -> project.kind == MotionKind.SLIDESHOW
            MotionTool.TRANSITION -> project.kind == MotionKind.SLIDESHOW && project.sourceIds.size > 1
            else -> true
        }
    }.sortedBy { it.ordinal }
    AnimatedContent(
        targetState = state.activeTool,
        modifier = modifier.background(FrameKitTheme.colors.background),
        transitionSpec = { fadeIn(tween(TOOL_TRANSITION_MS)) togetherWith fadeOut(tween(TOOL_TRANSITION_MS)) },
        label = "motion-tool-area",
    ) { tool ->
        Column(if (wide) Modifier.fillMaxSize() else Modifier.fillMaxWidth()) {
            when (tool) {
                null -> if (wide) {
                    Column(Modifier.weight(1f).verticalFadingScroll()) {
                        if (project.kind == MotionKind.SLIDESHOW) SlideStrip(state, viewModel)
                        ToolGrid(items = tools.map { it.railItem() }, onSelect = viewModel::selectTool)
                    }
                } else {
                    if (project.kind == MotionKind.SLIDESHOW) SlideStrip(state, viewModel)
                    ToolRail(items = tools.map { it.railItem() }, selected = null, onSelect = viewModel::selectTool)
                }
                MotionTool.MOTION -> PanelWithActions(viewModel, wide, resettable = true) { MotionPanel(project, viewModel::commit) }
                MotionTool.TRANSITION -> PanelWithActions(viewModel, wide, resettable = true) { TransitionPanel(project, viewModel) }
                MotionTool.TIMING -> PanelWithActions(viewModel, wide, resettable = true) { TimingPanel(project, state.sourceDurationMs, viewModel) }
                MotionTool.LOOP -> PanelWithActions(viewModel, wide, resettable = true) { LoopPanel(project, viewModel::commit) }
                MotionTool.CANVAS -> PanelWithActions(viewModel, wide, resettable = true) { CanvasPanel(project, viewModel::commit) }
                MotionTool.ADJUST -> PanelWithActions(viewModel, wide, resettable = true) {
                    AdjustToolPanel(
                        adjustments = project.adjustments,
                        selected = state.adjustKind,
                        onSelect = viewModel::selectAdjustment,
                        onChange = viewModel::changeAdjustment,
                        onChangeFinished = viewModel::finishDrag,
                    )
                }
                MotionTool.FILTER -> PanelWithActions(viewModel, wide, resettable = true) {
                    val thumbnails by viewModel.filterThumbnails.collectAsStateWithLifecycle()
                    FilterToolPanel(
                        selection = project.filter,
                        thumbnails = thumbnails,
                        onSelect = viewModel::selectFilter,
                        onIntensity = viewModel::changeFilterIntensity,
                        onIntensityFinished = viewModel::finishDrag,
                    )
                }
                MotionTool.TEXT -> PanelWithActions(viewModel, wide, resettable = false) {
                    val editing = project.overlays.firstOrNull { it.overlay.id == state.editingTextId }
                    val text = editing?.overlay as? ImageOverlay.Text
                    if (editing != null && text != null) {
                        OverlayAnimationChips(editing.animation, viewModel::setOverlayAnimation)
                        TextToolPanel(text = text, onText = viewModel::updateText, onStyle = viewModel::updateTextStyle, onOpacity = viewModel::updateTextOpacity)
                    }
                }
                MotionTool.AUDIO -> PanelWithActions(viewModel, wide, resettable = false) { AudioPanel(state, viewModel) }
                MotionTool.STICKER -> PanelWithActions(viewModel, wide, resettable = false) {
                    val selected = project.overlays.firstOrNull { it.overlay.id == state.selectedOverlayId }
                    StickerSelection(selected, viewModel)
                    StickerToolPanel(category = state.stickerCategory, onCategory = viewModel::selectStickerCategory, onAdd = viewModel::addSticker)
                }
            }
        }
    }
}

/** 도구 패널과 하단 초기화·적용 줄. 넓은 화면에서는 적용 줄을 패널 맨 아래에 고정한다. */
@Composable
private fun ColumnScope.PanelWithActions(viewModel: MotionEditorViewModel, wide: Boolean, resettable: Boolean, panel: @Composable () -> Unit) {
    Column(Modifier.weight(1f, fill = wide).verticalFadingScroll()) { panel() }
    ToolActionBar(onApply = viewModel::applyTool, onReset = if (resettable) viewModel::resetTool else null)
}

@Composable
private fun MotionTool.label(): String = stringResource(
    when (this) {
        MotionTool.MOTION -> R.string.framekit_motion_tool_motion
        MotionTool.TRANSITION -> R.string.framekit_motion_tool_transition
        MotionTool.TIMING -> R.string.framekit_motion_tool_timing
        MotionTool.LOOP -> R.string.framekit_motion_tool_loop
        MotionTool.CANVAS -> R.string.framekit_motion_tool_ratio
        MotionTool.ADJUST -> UiR.string.framekit_tool_adjust
        MotionTool.FILTER -> UiR.string.framekit_tool_filter
        MotionTool.TEXT -> UiR.string.framekit_tool_text
        MotionTool.STICKER -> UiR.string.framekit_tool_sticker
        MotionTool.AUDIO -> R.string.framekit_motion_tool_audio
    },
)

@Composable
private fun MotionTool.railItem(): ToolRailItem<MotionTool> = ToolRailItem(
    this,
    label(),
    painterResource(
        when (this) {
            MotionTool.MOTION -> R.drawable.framekit_ic_motion
            MotionTool.TRANSITION -> R.drawable.framekit_ic_transition
            MotionTool.TIMING -> R.drawable.framekit_ic_timer
            MotionTool.LOOP -> R.drawable.framekit_ic_loop
            MotionTool.CANVAS -> UiR.drawable.framekit_ic_aspect
            MotionTool.ADJUST -> UiR.drawable.framekit_ic_adjust
            MotionTool.FILTER -> UiR.drawable.framekit_ic_filter
            MotionTool.TEXT -> UiR.drawable.framekit_ic_text
            MotionTool.STICKER -> UiR.drawable.framekit_ic_sticker
            MotionTool.AUDIO -> UiR.drawable.framekit_ic_volume
        },
    ),
)

private const val PANEL_WIDTH = 360
private const val MAX_TOOL_FRACTION = 0.5f
private const val TOOL_TRANSITION_MS = 200
