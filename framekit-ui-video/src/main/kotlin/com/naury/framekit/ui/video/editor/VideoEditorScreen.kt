package com.naury.framekit.ui.video.editor

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.naury.framekit.core.video.Timeline
import com.naury.framekit.ui.R as UiR
import com.naury.framekit.ui.component.ApplyCancelBar
import com.naury.framekit.ui.component.DiscardChangesDialog
import com.naury.framekit.ui.component.EditorErrorView
import com.naury.framekit.ui.component.EditorLoadingView
import com.naury.framekit.ui.component.EditorTopBar
import com.naury.framekit.ui.component.ExportErrorDialog
import com.naury.framekit.ui.component.ExportOverlay
import com.naury.framekit.ui.component.HistoryControls
import com.naury.framekit.ui.component.ToolGrid
import com.naury.framekit.ui.component.ToolRail
import com.naury.framekit.ui.component.ToolRailItem
import com.naury.framekit.ui.design.FrameKitTheme
import com.naury.framekit.ui.layout.EditorLayout
import com.naury.framekit.ui.layout.EditorLayoutPolicy
import com.naury.framekit.ui.layout.FoldPosture
import com.naury.framekit.ui.tool.AdjustToolPanel
import com.naury.framekit.ui.tool.CropToolPanel
import com.naury.framekit.ui.tool.FilterToolPanel
import com.naury.framekit.ui.tool.PrivacyShape
import com.naury.framekit.ui.tool.PrivacyToolPanel
import com.naury.framekit.ui.tool.RotateToolPanel
import com.naury.framekit.ui.video.R
import com.naury.framekit.ui.video.contract.VideoTool
import com.naury.framekit.ui.video.timeline.TrimSelection
import com.naury.framekit.ui.video.timeline.VideoTimeline
import com.naury.framekit.ui.video.timeline.formatTime
import com.naury.framekit.ui.video.tool.AudioToolPanel
import com.naury.framekit.ui.video.tool.MaskRangeRow
import com.naury.framekit.ui.video.tool.SpeedToolPanel

@Composable
internal fun VideoEditorScreen(viewModel: VideoEditorViewModel, posture: FoldPosture = FoldPosture.Flat) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var pickerLaunched by rememberSaveable { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        pickerLaunched = false
        viewModel.onPicked(uri)
    }
    BackHandler { viewModel.onBack() }

    Box(Modifier.fillMaxSize().background(FrameKitTheme.colors.background)) {
        when (val current = state) {
            VideoEditorUiState.AwaitingPick -> LaunchedEffect(Unit) {
                // 화면 회전으로 다시 그려질 때 picker가 두 번 열리지 않도록 실행 여부를 저장해 둔다.
                if (!pickerLaunched) {
                    pickerLaunched = true
                    picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
                }
            }
            VideoEditorUiState.Loading -> EditorLoadingView(Modifier.windowInsetsPadding(WindowInsets.safeDrawing))
            is VideoEditorUiState.LoadFailed -> EditorErrorView(
                code = current.code,
                onClose = viewModel::requestClose,
                onChooseAnother = if (current.canChooseAnother) viewModel::chooseAnother else null,
                modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing),
            )
            is VideoEditorUiState.Ready -> ReadyContent(current, viewModel, posture)
        }
    }
}

@Composable
private fun ReadyContent(state: VideoEditorUiState.Ready, viewModel: VideoEditorViewModel, posture: FoldPosture) {
    val snackbar = remember { SnackbarHostState() }
    val applyHint = stringResource(UiR.string.framekit_apply_first)
    LaunchedEffect(state.showApplyHint) {
        if (state.showApplyHint) {
            snackbar.showSnackbar(applyHint)
            viewModel.dismissApplyHint()
        }
    }
    val noticeText = when (state.notice) {
        VideoNotice.MASK_LIMIT -> pluralStringResource(R.plurals.framekit_mask_limit, Timeline.MAX_PRIVACY_MASKS, Timeline.MAX_PRIVACY_MASKS)
        VideoNotice.SPEED_TOO_LONG -> stringResource(R.string.framekit_speed_too_long)
        VideoNotice.SPEED_TOO_SHORT -> stringResource(R.string.framekit_speed_too_short)
        VideoNotice.RESTORED -> stringResource(UiR.string.framekit_session_restored)
        VideoNotice.EXPORT_INTERRUPTED -> stringResource(UiR.string.framekit_export_interrupted)
        null -> null
    }
    LaunchedEffect(state.notice) {
        if (noticeText != null) {
            viewModel.dismissNotice()
            snackbar.showSnackbar(noticeText)
        }
    }
    val view = LocalView.current
    val exporting = state.export is ExportUiState.Running
    DisposableEffect(exporting) {
        view.keepScreenOn = exporting
        onDispose { view.keepScreenOn = false }
    }

    val density = LocalDensity.current
    // 힌지 좌표는 창 기준이므로 루트는 창 전체를 덮고, system bar 인셋은 각 영역 안에서 처리한다.
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val topBar = @Composable {
            EditorTopBar(onClose = viewModel::requestClose, onSave = viewModel::save, saveEnabled = state.export == null)
        }
        when (val layout = EditorLayoutPolicy.decide(maxWidth.value, maxHeight.value, posture)) {
            is EditorLayout.Stacked -> Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                topBar()
                CanvasArea(state, viewModel, Modifier.weight(1f).fillMaxWidth())
                ControlArea(state, viewModel, Modifier.fillMaxWidth(), layout.maxControlsWidthDp)
            }
            is EditorLayout.SidePanel -> Row(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                PreviewColumn(state, viewModel, Modifier.weight(1f).fillMaxHeight())
                SidePanel(state, viewModel, topBar, Modifier.width(layout.panelWidthDp.dp).fillMaxHeight())
            }
            is EditorLayout.SplitAtHorizontalHinge -> Column(Modifier.fillMaxSize()) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .height(with(density) { layout.hingeTopPx.toDp() })
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
                ) {
                    topBar()
                    CanvasArea(state, viewModel, Modifier.weight(1f).fillMaxWidth())
                }
                Spacer(Modifier.height(with(density) { (layout.hingeBottomPx - layout.hingeTopPx).toDp() }))
                Column(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)),
                    verticalArrangement = Arrangement.Center,
                ) {
                    ControlArea(state, viewModel, Modifier.fillMaxWidth(), EditorLayoutPolicy.MAX_CONTROLS_WIDTH_DP)
                }
            }
            is EditorLayout.SplitAtVerticalHinge -> Row(Modifier.fillMaxSize()) {
                PreviewColumn(
                    state,
                    viewModel,
                    Modifier
                        .width(with(density) { layout.hingeLeftPx.toDp() })
                        .fillMaxHeight()
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical + WindowInsetsSides.Start)),
                )
                Spacer(Modifier.width(with(density) { (layout.hingeRightPx - layout.hingeLeftPx).toDp() }))
                SidePanel(
                    state,
                    viewModel,
                    topBar,
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical + WindowInsetsSides.End)),
                )
            }
        }
        SnackbarHost(
            snackbar,
            Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.safeDrawing).padding(bottom = 96.dp),
        )
    }

    when (val export = state.export) {
        is ExportUiState.Running -> ExportOverlay(stage = export.stage, onCancel = viewModel::cancelExport, progress = export.progress)
        is ExportUiState.Failed -> ExportErrorDialog(code = export.code, onRetry = {
            viewModel.dismissExportError()
            viewModel.save()
        }, onDismiss = viewModel::dismissExportError)
        null -> Unit
    }
    if (state.showDiscardDialog) {
        DiscardChangesDialog(onDiscard = viewModel::confirmDiscard, onKeepEditing = viewModel::dismissDiscard)
    }
}

@Composable
private fun CanvasArea(state: VideoEditorUiState.Ready, viewModel: VideoEditorViewModel, modifier: Modifier) {
    val playback by viewModel.playback.collectAsStateWithLifecycle()
    val previewSize by viewModel.previewSize.collectAsStateWithLifecycle()
    Box(modifier) {
        VideoCanvas(
            state = state,
            previewSize = previewSize,
            positionUs = playback.positionUs,
            actions = viewModel,
            onAttachSurface = { viewModel.attachSurface(it.holder) },
            onDetachSurface = { viewModel.detachSurface(it.holder) },
            onTap = viewModel::togglePlayback,
        )
        if (state.activeTool?.isDraft != true) {
            val history = state.transaction.history
            HistoryControls(
                canUndo = history.canUndo,
                canRedo = history.canRedo,
                showUndo = viewModel.config.allowUndo,
                showRedo = viewModel.config.allowRedo,
                onUndo = viewModel::undo,
                onRedo = viewModel::redo,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 12.dp),
            )
        }
    }
}

/**
 * 넓은 레이아웃. 미리보기와 재생 행 아래에 전체 폭 타임라인을 두어 타임라인이 영상만큼
 * 공간을 갖게 한다.
 */
@Composable
private fun PreviewColumn(state: VideoEditorUiState.Ready, viewModel: VideoEditorViewModel, modifier: Modifier) {
    Column(modifier) {
        CanvasArea(state, viewModel, Modifier.weight(1f).fillMaxWidth())
        if (state.activeTool?.isGeometry != true) {
            PlaybackRow(state, viewModel)
            TimelineRow(state, viewModel)
        }
    }
}

/** 넓은 레이아웃. 상단 바, 그 바로 아래에 도구 격자 또는 열린 도구를 둔다. */
@Composable
private fun SidePanel(state: VideoEditorUiState.Ready, viewModel: VideoEditorViewModel, topBar: @Composable () -> Unit, modifier: Modifier) {
    Column(modifier.background(FrameKitTheme.colors.background)) {
        topBar()
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            ToolArea(state, viewModel, wide = true)
        }
    }
}

/** 미리보기 아래의 재생 행, 타임라인, 도구 영역. */
@Composable
private fun ControlArea(state: VideoEditorUiState.Ready, viewModel: VideoEditorViewModel, modifier: Modifier, maxWidthDp: Int?) {
    Column(
        modifier.background(FrameKitTheme.colors.background).wrapContentWidth(Alignment.CenterHorizontally),
    ) {
        Column(if (maxWidthDp != null) Modifier.widthIn(max = maxWidthDp.dp).fillMaxWidth() else Modifier.fillMaxWidth()) {
            if (state.activeTool?.isGeometry != true) {
                PlaybackRow(state, viewModel)
                TimelineRow(state, viewModel)
            }
            ToolArea(state, viewModel, wide = false)
        }
    }
}

@Composable
private fun PlaybackRow(state: VideoEditorUiState.Ready, viewModel: VideoEditorViewModel) {
    val colors = FrameKitTheme.colors
    val playback by viewModel.playback.collectAsStateWithLifecycle()
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = viewModel::togglePlayback) {
            Icon(
                painterResource(if (playback.isPlaying) UiR.drawable.framekit_ic_pause else UiR.drawable.framekit_ic_play),
                contentDescription = stringResource(if (playback.isPlaying) R.string.framekit_pause else R.string.framekit_play),
                tint = colors.foreground,
            )
        }
        Text(
            stringResource(R.string.framekit_time_position, formatTime(playback.positionUs), formatTime(state.durationUs)),
            color = colors.foregroundMuted,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.weight(1f),
        )
        // 소리 도구를 열지 않고도 바로 음소거할 수 있게 재생 줄에 둔다. 한 번 누를 때마다 실행 취소 한 단계다.
        if (state.source.metadata.hasAudio && VideoTool.AUDIO in viewModel.config.enabledTools && state.export == null) {
            val muted = state.clip.muted
            IconButton(onClick = { viewModel.setMuted(!muted) }, enabled = !state.transaction.isActive) {
                Icon(
                    painterResource(if (muted) UiR.drawable.framekit_ic_volume_off else UiR.drawable.framekit_ic_volume),
                    contentDescription = stringResource(if (muted) R.string.framekit_audio_unmute else R.string.framekit_audio_mute),
                    tint = if (muted) colors.accent else colors.foreground,
                )
            }
        }
    }
}

@Composable
private fun TimelineRow(state: VideoEditorUiState.Ready, viewModel: VideoEditorViewModel) {
    val playback by viewModel.playback.collectAsStateWithLifecycle()
    val clip = state.clip
    val upright = state.source.metadata.uprightSize
    val aspect = upright.width.toFloat() / upright.height
    val description = stringResource(R.string.framekit_timeline)
    if (state.activeTool == VideoTool.TRIM) {
        // 구간 편집 중에는 원본 전체를 보여 주고, 남길 구간을 손잡이로 고른다.
        val total = state.sourceDurationUs.toDouble()
        val range = clip.sourceRange
        VideoTimeline(
            frameKey = state.sourceDurationUs,
            frameTimeAt = { fraction -> (fraction * total).toLong() },
            frameAspect = aspect,
            loadFrame = viewModel::timelineFrame,
            playhead = ((range.startUs + playback.positionUs * clip.speed) / total).toFloat(),
            onScrub = { fraction ->
                val output = ((fraction * total - range.startUs) / clip.speed).toLong()
                viewModel.seekTo(output, scrubbing = true)
            },
            onScrubEnd = viewModel::finishScrub,
            description = description,
            trim = TrimSelection(
                start = (range.startUs / total).toFloat(),
                end = (range.endExclusiveUs / total).toFloat(),
                onBegin = viewModel::beginTrim,
                onDrag = { fraction -> viewModel.dragTrim((fraction * total).toLong()) },
                onEnd = viewModel::endTrim,
            ),
        )
        Text(
            stringResource(R.string.framekit_trim_help),
            color = FrameKitTheme.colors.foregroundMuted,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
    } else {
        val duration = state.durationUs.toDouble().coerceAtLeast(1.0)
        VideoTimeline(
            frameKey = clip.sourceRange to clip.speed,
            frameTimeAt = { fraction -> clip.sourceRange.startUs + (fraction * clip.sourceRange.durationUs).toLong() },
            frameAspect = aspect,
            loadFrame = viewModel::timelineFrame,
            playhead = (playback.positionUs / duration).toFloat(),
            onScrub = { fraction -> viewModel.seekTo((fraction * duration).toLong(), scrubbing = true) },
            onScrubEnd = viewModel::finishScrub,
            description = description,
        )
    }
}

@Composable
private fun ToolArea(state: VideoEditorUiState.Ready, viewModel: VideoEditorViewModel, wide: Boolean) {
    val tools = viewModel.config.enabledTools.toList().sortedBy { it.ordinal }
    AnimatedContent(
        targetState = state.activeTool,
        modifier = Modifier.fillMaxWidth(),
        transitionSpec = { fadeIn(tween(TOOL_TRANSITION_MS)) togetherWith fadeOut(tween(TOOL_TRANSITION_MS)) },
        label = "video-tool-area",
    ) { tool ->
        Column(Modifier.fillMaxWidth()) {
            val geometry = state.clip.effects.geometry
            when (tool) {
                VideoTool.TRIM -> ToolPanelWithActions(R.string.framekit_tool_trim, viewModel, isDraft = true) {}
                VideoTool.CROP -> ToolPanelWithActions(UiR.string.framekit_tool_crop, viewModel, isDraft = true) {
                    CropToolPanel(aspect = state.cropAspect, onSelectAspect = viewModel::selectAspect)
                }
                VideoTool.ROTATE -> ToolPanelWithActions(UiR.string.framekit_tool_rotate, viewModel, isDraft = true) {
                    RotateToolPanel(
                        straightenDegrees = geometry.straightenDegrees,
                        onRotateLeft = viewModel::rotateLeft,
                        onRotateRight = viewModel::rotateRight,
                        onFlipHorizontal = viewModel::flipHorizontal,
                        onFlipVertical = viewModel::flipVertical,
                        onStraighten = viewModel::changeStraighten,
                        onStraightenFinished = viewModel::finishStraighten,
                    )
                }
                VideoTool.ADJUST -> ToolPanelWithActions(UiR.string.framekit_tool_adjust, viewModel, isDraft = false) {
                    AdjustToolPanel(
                        adjustments = state.clip.effects.adjustments,
                        selected = state.adjustKind,
                        onSelect = viewModel::selectAdjustment,
                        onChange = viewModel::changeAdjustment,
                        onChangeFinished = viewModel::finishGesture,
                    )
                }
                VideoTool.FILTER -> ToolPanelWithActions(UiR.string.framekit_tool_filter, viewModel, isDraft = false) {
                    val thumbnails by viewModel.filterThumbnails.collectAsStateWithLifecycle()
                    FilterToolPanel(
                        selection = state.clip.effects.filter,
                        thumbnails = thumbnails,
                        onSelect = viewModel::selectFilter,
                        onIntensity = viewModel::changeFilterIntensity,
                        onIntensityFinished = viewModel::finishGesture,
                    )
                }
                VideoTool.SPEED -> ToolPanelWithActions(R.string.framekit_tool_speed, viewModel, isDraft = false) {
                    SpeedToolPanel(speed = state.clip.speed, onSelect = viewModel::selectSpeed)
                }
                VideoTool.AUDIO -> ToolPanelWithActions(R.string.framekit_tool_audio, viewModel, isDraft = false) {
                    AudioToolPanel(
                        hasAudio = state.source.metadata.hasAudio,
                        muted = state.clip.muted,
                        volume = state.clip.volume,
                        onMuted = viewModel::setMuted,
                        onVolume = viewModel::changeVolume,
                        onVolumeFinished = viewModel::finishGesture,
                    )
                }
                VideoTool.PRIVACY -> ToolPanelWithActions(UiR.string.framekit_tool_privacy, viewModel, isDraft = false) {
                    val selected = state.displayed.timeline.privacyMasks.firstOrNull { it.mask.id == state.selectedMaskId }
                    MaskRangeRow(
                        mask = selected,
                        onStartHere = { selected?.let { viewModel.setMaskEdge(it.mask.id, start = true) } },
                        onEndHere = { selected?.let { viewModel.setMaskEdge(it.mask.id, start = false) } },
                        onDelete = { selected?.let { viewModel.deleteMask(it.mask.id) } },
                    )
                    PrivacyToolPanel(
                        settings = state.privacy,
                        onChange = viewModel::updatePrivacy,
                        shapes = listOf(PrivacyShape.RECTANGLE, PrivacyShape.ELLIPSE),
                    )
                }
                null -> when {
                    tools.isEmpty() -> Unit
                    wide -> ToolGrid(items = tools.map { it.railItem() }, onSelect = viewModel::selectTool)
                    else -> ToolRail(items = tools.map { it.railItem() }, selected = null, onSelect = viewModel::selectTool)
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.ToolPanelWithActions(title: Int, viewModel: VideoEditorViewModel, isDraft: Boolean, panel: @Composable () -> Unit) {
    panel()
    ApplyCancelBar(
        title = stringResource(title),
        onCancel = if (isDraft) viewModel::cancelTool else null,
        onApply = if (isDraft) viewModel::applyTool else viewModel::closeTool,
        onReset = viewModel::resetTool,
    )
}

@Composable
private fun VideoTool.railItem(): ToolRailItem<VideoTool> = when (this) {
    VideoTool.TRIM -> ToolRailItem(this, stringResource(R.string.framekit_tool_trim), painterResource(UiR.drawable.framekit_ic_trim))
    VideoTool.CROP -> ToolRailItem(this, stringResource(UiR.string.framekit_tool_crop), painterResource(UiR.drawable.framekit_ic_crop))
    VideoTool.ROTATE -> ToolRailItem(this, stringResource(UiR.string.framekit_tool_rotate), painterResource(UiR.drawable.framekit_ic_rotate_right))
    VideoTool.ADJUST -> ToolRailItem(this, stringResource(UiR.string.framekit_tool_adjust), painterResource(UiR.drawable.framekit_ic_adjust))
    VideoTool.FILTER -> ToolRailItem(this, stringResource(UiR.string.framekit_tool_filter), painterResource(UiR.drawable.framekit_ic_filter))
    VideoTool.SPEED -> ToolRailItem(this, stringResource(R.string.framekit_tool_speed), painterResource(UiR.drawable.framekit_ic_speed))
    VideoTool.AUDIO -> ToolRailItem(this, stringResource(R.string.framekit_tool_audio), painterResource(UiR.drawable.framekit_ic_volume))
    VideoTool.PRIVACY -> ToolRailItem(this, stringResource(UiR.string.framekit_tool_privacy), painterResource(UiR.drawable.framekit_ic_privacy))
}

private const val TOOL_TRANSITION_MS = 200
