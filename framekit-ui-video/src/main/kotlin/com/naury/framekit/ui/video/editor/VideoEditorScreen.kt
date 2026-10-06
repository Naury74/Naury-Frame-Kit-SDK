package com.naury.framekit.ui.video.editor

import com.naury.framekit.ui.text.messageRes
import com.naury.framekit.ui.source.rememberSourceLaunchers
import com.naury.framekit.android.input.MediaKind
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import com.naury.framekit.core.overlay.ImageOverlay
import com.naury.framekit.core.video.TimeRangeUs
import com.naury.framekit.core.video.TimelineTimeMapper
import com.naury.framekit.ui.tool.StickerToolPanel
import com.naury.framekit.ui.tool.TextToolPanel
import com.naury.framekit.ui.video.contract.VideoEditorConfig
import com.naury.framekit.ui.video.timeline.MultiClipTimeline
import com.naury.framekit.ui.video.timeline.TimelineClip
import com.naury.framekit.ui.video.timeline.TimelineItem
import com.naury.framekit.ui.video.timeline.TimelineItemKind
import com.naury.framekit.ui.video.tool.MusicUi
import com.naury.framekit.ui.video.tool.TimedRangeRow
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
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.CompositionLocalProvider
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
import com.naury.framekit.ui.component.ApplyDraftDialog
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
import com.naury.framekit.ui.video.tool.CanvasToolPanel
import com.naury.framekit.ui.video.tool.SpeedToolPanel

@Composable
internal fun VideoEditorScreen(viewModel: VideoEditorViewModel, posture: FoldPosture = FoldPosture.Flat) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // 화면 회전으로 다시 그려질 때 picker·카메라가 두 번 열리지 않도록 실행 여부를 저장해 둔다.
    var sourceLaunched by rememberSaveable { mutableStateOf(false) }
    val launchers = rememberSourceLaunchers(
        maxItems = viewModel.config.maxClipCount,
        onPicked = { uris ->
            sourceLaunched = false
            viewModel.onPicked(uris)
        },
        onCaptured = { ok ->
            sourceLaunched = false
            viewModel.onCaptured(ok)
        },
        onFailed = viewModel::onCaptureFailed,
    )
    BackHandler { viewModel.onBack() }

    Box(Modifier.fillMaxSize().background(FrameKitTheme.colors.background)) {
        when (val current = state) {
            is VideoEditorUiState.AwaitingSource -> LaunchedEffect(current.mode) {
                if (!sourceLaunched) {
                    sourceLaunched = true
                    when (val mode = current.mode) {
                        is VideoSourceMode.Pick -> launchers.pick(MediaKind.VIDEO, mode.maxItems)
                        VideoSourceMode.Capture -> launchers.capture(MediaKind.VIDEO, viewModel::prepareCapture)
                    }
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
        VideoNotice.CLIP_LIMIT -> pluralStringResource(R.plurals.framekit_clip_limit, viewModel.config.maxClipCount, viewModel.config.maxClipCount)
        VideoNotice.TIMELINE_FULL -> stringResource(R.string.framekit_timeline_full)
        VideoNotice.SPLIT_UNAVAILABLE -> stringResource(R.string.framekit_split_unavailable)
        VideoNotice.ADD_FAILED -> stringResource(R.string.framekit_add_failed)
        VideoNotice.PARTIALLY_RESTORED -> stringResource(R.string.framekit_partially_restored)
        VideoNotice.CLEARED -> stringResource(R.string.framekit_cleared)
        null -> null
    }
    val undoLabel = stringResource(UiR.string.framekit_action_undo)
    LaunchedEffect(state.notice) {
        if (noticeText != null) {
            val offerUndo = state.notice == VideoNotice.CLEARED
            viewModel.dismissNotice()
            val result = snackbar.showSnackbar(noticeText, actionLabel = if (offerUndo) undoLabel else null, duration = SnackbarDuration.Short)
            if (offerUndo && result == SnackbarResult.ActionPerformed) viewModel.undo()
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
    CompositionLocalProvider(LocalEditorSnackbar provides snackbar) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val topBar = @Composable {
                EditorTopBar(onClose = viewModel::requestClose, onSave = viewModel::save, saveEnabled = state.export == null)
            }
            when (val layout = EditorLayoutPolicy.decide(maxWidth.value, maxHeight.value, posture)) {
                // 키보드 인셋을 뺀 실제 높이를 기준으로 비율을 잡아야 입력 중에도 캔버스가 남는다.
                is EditorLayout.Stacked -> BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                    val available = maxHeight
                    Column(Modifier.fillMaxSize()) {
                        topBar()
                        CanvasArea(state, viewModel, Modifier.weight(1f).fillMaxWidth())
                        // 패널이 길거나 키보드가 올라와도 미리보기가 사라지지 않도록 아래 영역 높이를 제한한다.
                        ControlArea(state, viewModel, Modifier.fillMaxWidth().heightIn(max = available * MAX_CONTROL_AREA_FRACTION), layout.maxControlsWidthDp)
                    }
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
        }
    }

    when (val export = state.export) {
        is ExportUiState.Running -> ExportOverlay(stage = export.stage, onCancel = viewModel::cancelExport, progress = export.progress)
        is ExportUiState.Failed -> ExportErrorDialog(code = export.code, onRetry = {
            viewModel.dismissExportError()
            viewModel.save()
        }, onDismiss = viewModel::dismissExportError)
        null -> Unit
    }
    if (state.showDraftDialog) {
        ApplyDraftDialog(onApply = viewModel::applyDraftFromDialog, onDiscard = viewModel::discardDraftFromDialog, onKeepEditing = viewModel::dismissDraftDialog)
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
        val previewFailed by viewModel.previewFailed.collectAsStateWithLifecycle()
        val previewError = playback.error
        if (previewFailed || previewError != null) {
            // 재생할 수 없어도(원본 삭제·권한 상실·디코더 부족) 편집과 저장 시도는 계속할 수 있다.
            Text(
                stringResource(R.string.framekit_preview_unavailable) + (previewError?.let { "\n" + stringResource(it.messageRes()) } ?: ""),
                color = FrameKitTheme.colors.foreground,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(24.dp)
                    .background(FrameKitTheme.colors.surface.copy(alpha = 0.9f), MaterialTheme.shapes.medium)
                    .padding(16.dp),
            )
        }
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
        // 안내 메시지는 캔버스 아래쪽에 띄워 레이아웃과 상관없이 도구 패널을 가리지 않게 한다.
        SnackbarHost(LocalEditorSnackbar.current, Modifier.align(Alignment.BottomCenter).padding(8.dp))
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
            if (state.activeTool == null) ClipActionBar(state, viewModel)
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
        // 텍스트를 입력하는 동안에는 키보드 자리를 확보하려고 재생 줄과 타임라인을 잠시 숨긴다.
        val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
        Column(if (maxWidthDp != null) Modifier.widthIn(max = maxWidthDp.dp).fillMaxWidth() else Modifier.fillMaxWidth()) {
            if (state.activeTool?.isGeometry != true && !imeVisible) {
                PlaybackRow(state, viewModel)
                TimelineRow(state, viewModel)
                if (state.activeTool == null) ClipActionBar(state, viewModel)
            }
            ToolArea(state, viewModel, wide = false, modifier = Modifier.weight(1f, fill = false), scrollPanel = true)
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
    val description = stringResource(R.string.framekit_timeline)
    if (state.activeTool == VideoTool.TRIM) {
        // 구간 편집 중에는 선택한 클립의 원본 전체를 보여 주고, 남길 구간을 손잡이로 고른다.
        val clip = state.clip
        val upright = state.source.metadata.uprightSize
        val total = state.sourceDurationUs.toDouble()
        val range = clip.sourceRange
        val clipStart = state.clipStartUs
        VideoTimeline(
            frameKey = clip.source to state.sourceDurationUs,
            frameTimeAt = { fraction -> (fraction * total).toLong() },
            frameAspect = upright.width.toFloat() / upright.height,
            loadFrame = { time, height -> viewModel.timelineFrame(clip.source, time, height) },
            playhead = ((range.startUs + (playback.positionUs - clipStart) * clip.speed) / total).toFloat(),
            onScrub = { fraction ->
                val output = clipStart + ((fraction * total - range.startUs) / clip.speed).toLong()
                viewModel.seekTo(output.coerceIn(clipStart, clipStart + clip.outputDurationUs), scrubbing = true)
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
        return
    }
    val project = state.displayed
    val starts = TimelineTimeMapper.clipStarts(project.timeline)
    val clips = project.timeline.videoClips.mapIndexed { index, clip ->
        val size = state.sources[clip.source]?.info?.metadata?.uprightSize
        TimelineClip(clip.id, clip.source, clip.sourceRange, clip.speed, starts[index], clip.outputDurationUs, size?.let { it.width.toFloat() / it.height } ?: 1f)
    }
    val items = buildList {
        project.timeline.overlays.forEach { add(TimelineItem(TimelineItemKind.OVERLAY, it.overlay.id, it.range, it.overlay.id == state.selectedOverlayId)) }
        project.timeline.privacyMasks.forEach { add(TimelineItem(TimelineItemKind.MASK, it.mask.id, it.range, it.mask.id == state.selectedMaskId)) }
        project.timeline.audioClips.forEach { music ->
            val end = (music.timelineStartUs + if (music.loop) state.durationUs else music.sourceRange.durationUs).coerceAtMost(state.durationUs)
            add(TimelineItem(TimelineItemKind.MUSIC, music.id, TimeRangeUs(music.timelineStartUs, end), false))
        }
    }
    MultiClipTimeline(
        clips = clips,
        selectedClipId = state.clip.id,
        items = items,
        durationUs = state.durationUs,
        positionUs = playback.positionUs,
        loadFrame = viewModel::timelineFrame,
        onScrub = { viewModel.seekTo(it, scrubbing = true) },
        onScrubEnd = {
            viewModel.finishScrub()
            viewModel.selectClipAtPlayhead()
        },
        onSelectClip = { viewModel.selectClip(it) },
        onSelectItem = { kind, id ->
            when (kind) {
                TimelineItemKind.OVERLAY -> viewModel.selectOverlay(id)
                TimelineItemKind.MASK -> viewModel.selectMask(id)
                TimelineItemKind.MUSIC -> Unit
            }
        },
        description = description,
    )
}

/** 여러 클립을 쓸 수 있을 때 타임라인 아래에 두는 클립 추가·나누기·이동·삭제 버튼. */
@Composable
private fun ClipActionBar(state: VideoEditorUiState.Ready, viewModel: VideoEditorViewModel) {
    if (!viewModel.multiClip) return
    val colors = FrameKitTheme.colors
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(maxOf(2, VideoEditorConfig.MAX_CLIPS)),
    ) { uris -> viewModel.addClips(uris) }
    val index = state.clips.indexOfFirst { it.id == state.clip.id }
    val enabled = state.export == null && !state.busy
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
        ClipAction(Icons.Filled.Add, R.string.framekit_clip_add, enabled && state.clips.size < viewModel.config.maxClipCount) {
            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
        }
        ClipAction(painterResource(UiR.drawable.framekit_ic_trim), R.string.framekit_clip_split, enabled, viewModel::splitAtPlayhead)
        ClipAction(Icons.AutoMirrored.Filled.ArrowBack, R.string.framekit_clip_move_left, enabled && index > 0) { viewModel.moveSelectedClip(-1) }
        ClipAction(Icons.AutoMirrored.Filled.ArrowForward, R.string.framekit_clip_move_right, enabled && index in 0 until state.clips.size - 1) { viewModel.moveSelectedClip(1) }
        ClipAction(Icons.Filled.Delete, R.string.framekit_clip_delete, enabled && state.clips.size > 1, viewModel::deleteSelectedClip)
        if (state.busy) CircularProgressIndicator(Modifier.size(20.dp), color = colors.accent, strokeWidth = 2.dp)
    }
}

@Composable
private fun ClipAction(icon: ImageVector, label: Int, enabled: Boolean, onClick: () -> Unit) =
    ClipAction(rememberVectorPainter(icon), label, enabled, onClick)

@Composable
private fun ClipAction(icon: Painter, label: Int, enabled: Boolean, onClick: () -> Unit) {
    val colors = FrameKitTheme.colors
    IconButton(onClick = onClick, enabled = enabled) {
        Icon(icon, contentDescription = stringResource(label), tint = if (enabled) colors.foreground else colors.foregroundMuted.copy(alpha = 0.4f))
    }
}

@Composable
private fun ToolArea(
    state: VideoEditorUiState.Ready,
    viewModel: VideoEditorViewModel,
    wide: Boolean,
    modifier: Modifier = Modifier,
    scrollPanel: Boolean = false,
) {
    val tools = viewModel.config.enabledTools.toList().sortedBy { it.ordinal }
    AnimatedContent(
        targetState = state.activeTool,
        modifier = modifier.fillMaxWidth(),
        transitionSpec = { fadeIn(tween(TOOL_TRANSITION_MS)) togetherWith fadeOut(tween(TOOL_TRANSITION_MS)) },
        label = "video-tool-area",
    ) { tool ->
        Column(Modifier.fillMaxWidth()) {
            val geometry = state.clip.effects.geometry
            when (tool) {
                VideoTool.TRIM -> ToolPanelWithActions(R.string.framekit_tool_trim, viewModel, isDraft = true, scrollPanel) {}
                VideoTool.CROP -> ToolPanelWithActions(UiR.string.framekit_tool_crop, viewModel, isDraft = true, scrollPanel) {
                    CropToolPanel(
                        aspect = state.cropAspect,
                        onSelectAspect = viewModel::selectAspect,
                        onRotateLeft = viewModel::rotateLeft,
                        onRotateRight = viewModel::rotateRight,
                        onFlipHorizontal = viewModel::flipHorizontal,
                    )
                }
                VideoTool.ROTATE -> ToolPanelWithActions(UiR.string.framekit_tool_rotate, viewModel, isDraft = true, scrollPanel) {
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
                VideoTool.CANVAS -> ToolPanelWithActions(R.string.framekit_tool_canvas, viewModel, isDraft = false, scrollPanel) {
                    CanvasToolPanel(canvas = state.displayed.canvas, onChange = viewModel::setCanvas)
                }
                VideoTool.ADJUST -> ToolPanelWithActions(UiR.string.framekit_tool_adjust, viewModel, isDraft = false, scrollPanel) {
                    AdjustToolPanel(
                        adjustments = state.clip.effects.adjustments,
                        selected = state.adjustKind,
                        onSelect = viewModel::selectAdjustment,
                        onChange = viewModel::changeAdjustment,
                        onChangeFinished = viewModel::finishGesture,
                    )
                }
                VideoTool.FILTER -> ToolPanelWithActions(UiR.string.framekit_tool_filter, viewModel, isDraft = false, scrollPanel) {
                    val thumbnails by viewModel.filterThumbnails.collectAsStateWithLifecycle()
                    FilterToolPanel(
                        selection = state.clip.effects.filter,
                        thumbnails = thumbnails,
                        onSelect = viewModel::selectFilter,
                        onIntensity = viewModel::changeFilterIntensity,
                        onIntensityFinished = viewModel::finishGesture,
                    )
                }
                VideoTool.SPEED -> ToolPanelWithActions(R.string.framekit_tool_speed, viewModel, isDraft = true, scrollPanel) {
                    SpeedToolPanel(speed = state.clip.speed, sourceDurationUs = state.clip.sourceRange.durationUs, onSelect = viewModel::selectSpeed)
                }
                VideoTool.AUDIO -> ToolPanelWithActions(R.string.framekit_tool_audio, viewModel, isDraft = false, scrollPanel) {
                    val music = state.displayed.timeline.audioClips.firstOrNull()
                    val loaded = music?.let { state.music[it.source] }
                    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> viewModel.addMusic(uri) }
                    AudioToolPanel(
                        hasAudio = state.source.metadata.hasAudio,
                        muted = state.clip.muted,
                        volume = state.clip.volume,
                        onMuted = viewModel::setMuted,
                        onVolume = viewModel::changeVolume,
                        onVolumeFinished = viewModel::finishGesture,
                        music = music?.let {
                            MusicUi(
                                name = loaded?.name,
                                volume = it.volume,
                                loop = it.loop,
                                startUs = it.timelineStartUs,
                                offsetUs = it.sourceRange.startUs,
                                songDurationUs = loaded?.info?.metadata?.durationUs ?: it.sourceRange.endExclusiveUs,
                            )
                        },
                        busy = state.busy,
                        onAddMusic = { picker.launch(arrayOf("audio/*")) },
                        onMusicVolume = viewModel::changeMusicVolume,
                        onMusicLoop = viewModel::setMusicLoop,
                        onMusicStartHere = viewModel::startMusicHere,
                        onMusicOffset = viewModel::changeMusicOffset,
                        onRemoveMusic = viewModel::removeMusic,
                        onGestureFinished = viewModel::finishGesture,
                    )
                }
                VideoTool.TEXT -> ToolPanelWithActions(UiR.string.framekit_tool_text, viewModel, isDraft = true, scrollPanel) {
                    val editing = state.displayed.timeline.overlays.firstOrNull { it.overlay.id == state.editingTextId }
                    val text = editing?.overlay as? ImageOverlay.Text
                    if (text != null) {
                        TextToolPanel(text = text, onText = viewModel::updateText, onStyle = viewModel::updateTextStyle, onOpacity = viewModel::updateTextOpacity)
                    }
                }
                VideoTool.STICKER -> ToolPanelWithActions(UiR.string.framekit_tool_sticker, viewModel, isDraft = false, scrollPanel) {
                    val selected = state.displayed.timeline.overlays.firstOrNull { it.overlay.id == state.selectedOverlayId }
                    TimedRangeRow(
                        range = selected?.range,
                        hint = stringResource(R.string.framekit_sticker_help),
                        onStartHere = { selected?.let { viewModel.setOverlayEdge(it.overlay.id, start = true) } },
                        onEndHere = { selected?.let { viewModel.setOverlayEdge(it.overlay.id, start = false) } },
                        onDelete = { selected?.let { viewModel.deleteOverlay(it.overlay.id) } },
                    )
                    StickerToolPanel(category = state.stickerCategory, onCategory = viewModel::selectStickerCategory, onAdd = viewModel::addSticker)
                }
                VideoTool.PRIVACY -> ToolPanelWithActions(UiR.string.framekit_tool_privacy, viewModel, isDraft = false, scrollPanel) {
                    val selected = state.displayed.timeline.privacyMasks.firstOrNull { it.mask.id == state.selectedMaskId }
                    TimedRangeRow(
                        range = selected?.range,
                        hint = stringResource(R.string.framekit_mask_help),
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
private fun ColumnScope.ToolPanelWithActions(title: Int, viewModel: VideoEditorViewModel, isDraft: Boolean, scroll: Boolean, panel: @Composable () -> Unit) {
    if (scroll) {
        // 높이가 모자라면 패널만 스크롤하고 적용·취소 줄은 항상 보이게 둔다.
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) { panel() }
    } else {
        panel()
    }
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
    VideoTool.CANVAS -> ToolRailItem(this, stringResource(R.string.framekit_tool_canvas), painterResource(UiR.drawable.framekit_ic_aspect))
    VideoTool.ADJUST -> ToolRailItem(this, stringResource(UiR.string.framekit_tool_adjust), painterResource(UiR.drawable.framekit_ic_adjust))
    VideoTool.FILTER -> ToolRailItem(this, stringResource(UiR.string.framekit_tool_filter), painterResource(UiR.drawable.framekit_ic_filter))
    VideoTool.SPEED -> ToolRailItem(this, stringResource(R.string.framekit_tool_speed), painterResource(UiR.drawable.framekit_ic_speed))
    VideoTool.AUDIO -> ToolRailItem(this, stringResource(R.string.framekit_tool_audio), painterResource(UiR.drawable.framekit_ic_volume))
    VideoTool.TEXT -> ToolRailItem(this, stringResource(UiR.string.framekit_tool_text), painterResource(UiR.drawable.framekit_ic_text))
    VideoTool.STICKER -> ToolRailItem(this, stringResource(UiR.string.framekit_tool_sticker), painterResource(UiR.drawable.framekit_ic_sticker))
    VideoTool.PRIVACY -> ToolRailItem(this, stringResource(UiR.string.framekit_tool_privacy), painterResource(UiR.drawable.framekit_ic_privacy))
}

// 좁은 화면에서 재생 줄·타임라인·도구가 차지할 수 있는 최대 비율. 나머지는 미리보기 몫이다.
private const val MAX_CONTROL_AREA_FRACTION = 0.6f

private const val TOOL_TRANSITION_MS = 200

private val LocalEditorSnackbar = staticCompositionLocalOf { SnackbarHostState() }
