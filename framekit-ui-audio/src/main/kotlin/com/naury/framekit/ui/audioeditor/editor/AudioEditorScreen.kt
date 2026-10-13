package com.naury.framekit.ui.audioeditor.editor

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.naury.framekit.android.input.EditorInput
import com.naury.framekit.core.video.AudioRole
import com.naury.framekit.ui.audioeditor.R
import com.naury.framekit.ui.audioeditor.contract.AudioTool
import com.naury.framekit.ui.component.ChoiceChips
import com.naury.framekit.ui.component.DialogAction
import com.naury.framekit.ui.component.DialogActionStyle
import com.naury.framekit.ui.component.DiscardChangesDialog
import com.naury.framekit.ui.component.EditorErrorView
import com.naury.framekit.ui.component.EditorLoadingView
import com.naury.framekit.ui.component.EditorTopBar
import com.naury.framekit.ui.component.FrameKitNavigationBar
import com.naury.framekit.ui.component.NavigationIcon
import com.naury.framekit.ui.component.ExportErrorDialog
import com.naury.framekit.ui.component.ExportOverlay
import com.naury.framekit.ui.component.ExportStageUi
import com.naury.framekit.ui.component.FrameKitDialog
import com.naury.framekit.ui.component.FrameKitSnackbarHost
import com.naury.framekit.ui.component.ToolRail
import com.naury.framekit.ui.component.ToolRailItem
import com.naury.framekit.ui.component.ValueSlider
import com.naury.framekit.ui.design.FrameKitTheme
import java.util.Locale
import kotlin.math.roundToInt
import com.naury.framekit.ui.R as UiR

@Composable
internal fun AudioEditorScreen(viewModel: AudioEditorViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var pickLaunched by rememberSaveable { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        pickLaunched = false
        viewModel.onPicked(uris)
    }
    BackHandler { viewModel.onBack() }
    Box(Modifier.fillMaxSize().background(FrameKitTheme.colors.background)) {
        when (val current = state) {
            is AudioEditorUiState.AwaitingPick -> LaunchedEffect(current) {
                if (!pickLaunched) {
                    pickLaunched = true
                    picker.launch(arrayOf("audio/*"))
                }
            }
            AudioEditorUiState.Recording -> FirstRecording(viewModel)
            AudioEditorUiState.Loading -> EditorLoadingView(Modifier.windowInsetsPadding(WindowInsets.safeDrawing))
            is AudioEditorUiState.LoadFailed -> EditorErrorView(
                code = current.code,
                onClose = viewModel::requestClose,
                onChooseAnother = if (current.canRetry) viewModel::retry else null,
                modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing),
            )
            is AudioEditorUiState.Ready -> Editor(current, viewModel)
        }
    }
}

/** 처음 녹음 화면. 녹음이 끝나면 편집 화면으로 넘어가거나 결과를 돌려준다. */
@Composable
private fun FirstRecording(viewModel: AudioEditorViewModel) {
    val colors = FrameKitTheme.colors
    var tooShort by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        FrameKitNavigationBar(
            title = stringResource(R.string.framekit_audio_new_recording),
            navigationIcon = NavigationIcon.CLOSE,
            onNavigate = viewModel::cancelRecording,
        )
        Spacer(Modifier.weight(1f))
        Box(Modifier.fillMaxWidth().padding(horizontal = 24.dp), contentAlignment = Alignment.Center) {
            RecorderPanel(
                mode = viewModel.config.recordMode,
                maxRecordingMs = viewModel.config.maxRecordingMs,
                compact = false,
                onRecorded = viewModel::onRecorded,
                onTooShort = { tooShort = true },
                modifier = Modifier.widthIn(max = 520.dp),
            )
        }
        if (tooShort) {
            Text(stringResource(R.string.framekit_audio_too_short), color = colors.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.align(Alignment.CenterHorizontally))
        }
        Spacer(Modifier.weight(1.2f))
    }
}

@Composable
private fun Editor(state: AudioEditorUiState.Ready, viewModel: AudioEditorViewModel) {
    val snackbar = remember { SnackbarHostState() }
    val noticeText = state.notice?.let {
        stringResource(
            when (it) {
                AudioNotice.ADD_FAILED -> R.string.framekit_audio_add_failed
                AudioNotice.TOO_SHORT -> R.string.framekit_audio_too_short
                AudioNotice.SPLIT_UNAVAILABLE -> R.string.framekit_audio_split_unavailable
                AudioNotice.RESTORED -> R.string.framekit_audio_restored
            },
        )
    }
    LaunchedEffect(state.notice) {
        if (noticeText != null) {
            snackbar.showSnackbar(noticeText)
            viewModel.dismissNotice()
        }
    }
    val waveforms by viewModel.waveforms.collectAsStateWithLifecycle()
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        viewModel.addFiles(uris.map(EditorInput::UriSource))
    }
    BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        // 세로가 넉넉하면(일반 휴대폰 세로 화면 이상) 타임라인을 키워 빈 공간 대신 파형을 크게 보여 준다.
        val tall = maxHeight >= TALL_HEIGHT
        // 넓은 화면에서도 읽기 좋은 폭으로 가운데 둔다.
        Column(Modifier.fillMaxSize().widthIn(max = 840.dp).align(Alignment.TopCenter)) {
            EditorTopBar(onClose = viewModel::requestClose, onSave = viewModel::save, saveEnabled = state.export == null && state.project.durationUs > 0)
            Header(state)
            Spacer(Modifier.height(12.dp))
            AudioTimeline(
                project = state.project,
                names = state.sources.mapValues { it.value.name },
                waveforms = waveforms,
                positionUs = state.positionUs,
                selectedId = state.selectedClipId,
                range = state.range,
                labels = stringResource(R.string.framekit_audio_lane_voice) to stringResource(R.string.framekit_audio_lane_layer),
                emptyLayerLabel = stringResource(R.string.framekit_audio_add_layer),
                actions = AudioTimelineActions(
                    onScrub = viewModel::seekTo,
                    onSelect = viewModel::selectClip,
                    onEmptyLayer = { viewModel.openRecordSheet(RecordTarget.OVERLAY) },
                    onBeginDrag = viewModel::beginDrag,
                    onDrag = viewModel::dragBy,
                    onEndDrag = viewModel::endDrag,
                    onRangeEdge = viewModel::moveRangeEdge,
                ),
                description = stringResource(R.string.framekit_audio_timeline),
                modifier = Modifier.padding(horizontal = 12.dp),
                tall = tall,
            )
            PlaybackRow(state, viewModel)
            Spacer(Modifier.weight(1f))
            AnimatedContent(
                targetState = when {
                    state.range != null -> 1
                    state.selectedClip != null -> 2
                    else -> 0
                },
                transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(180)) },
                label = "audio-bottom",
            ) { panel ->
                when (panel) {
                    1 -> RangePanel(viewModel)
                    2 -> ClipPanel(state, viewModel)
                    else -> Tools(viewModel, onImport = { importer.launch(arrayOf("audio/*")) })
                }
            }
        }
        FrameKitSnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 96.dp))
    }

    state.recordSheet?.let { target -> RecordSheet(target, viewModel) }
    when (val export = state.export) {
        is AudioExportUiState.Running -> ExportOverlay(
            stage = if (export.cancelling) ExportStageUi.CANCELLING else ExportStageUi.ENCODING,
            onCancel = viewModel::cancelExport,
            progress = export.progress,
        )
        is AudioExportUiState.Failed -> ExportErrorDialog(code = export.code, onRetry = {
            viewModel.dismissExportError()
            viewModel.save()
        }, onDismiss = viewModel::dismissExportError)
        null -> Unit
    }
    if (state.showDiscardDialog) DiscardChangesDialog(onDiscard = viewModel::confirmDiscard, onKeepEditing = viewModel::dismissDiscard)
}

/** 큰 재생 시간과 전체 길이. */
@Composable
private fun Header(state: AudioEditorUiState.Ready) {
    val colors = FrameKitTheme.colors
    Column(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(formatClock(state.positionUs / 1000), color = colors.foreground, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Light)
        Text(
            stringResource(R.string.framekit_audio_total, formatClock(state.project.durationUs / 1000)),
            color = colors.foregroundMuted,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

@Composable
private fun PlaybackRow(state: AudioEditorUiState.Ready, viewModel: AudioEditorViewModel) {
    val colors = FrameKitTheme.colors
    val history = state.transaction.history
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (viewModel.config.allowUndo) {
            IconButton(onClick = viewModel::undo, enabled = history.canUndo && !state.transaction.isActive) {
                Icon(painterResource(UiR.drawable.framekit_ic_undo), stringResource(R.string.framekit_audio_undo), tint = if (history.canUndo) colors.foreground else colors.foregroundMuted.copy(alpha = 0.4f))
            }
        }
        TextButton(onClick = { viewModel.skip(-SKIP_US) }) { Text("−5s", color = colors.foreground, style = MaterialTheme.typography.labelLarge) }
        Box(
            Modifier
                .size(64.dp)
                .background(colors.accent, CircleShape)
                .semantics { role = Role.Button }
                .clickable(onClick = viewModel::togglePlayback),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painterResource(if (state.playing) UiR.drawable.framekit_ic_pause else UiR.drawable.framekit_ic_play),
                contentDescription = stringResource(if (state.playing) R.string.framekit_audio_pause_playback else R.string.framekit_audio_play),
                tint = colors.onAccent,
                modifier = Modifier.size(30.dp),
            )
        }
        TextButton(onClick = { viewModel.skip(SKIP_US) }) { Text("+5s", color = colors.foreground, style = MaterialTheme.typography.labelLarge) }
        if (viewModel.config.allowRedo) {
            IconButton(onClick = viewModel::redo, enabled = history.canRedo && !state.transaction.isActive) {
                Icon(painterResource(UiR.drawable.framekit_ic_redo), stringResource(R.string.framekit_audio_redo), tint = if (history.canRedo) colors.foreground else colors.foregroundMuted.copy(alpha = 0.4f))
            }
        }
    }
}

@Composable
private fun Tools(viewModel: AudioEditorViewModel, onImport: () -> Unit) {
    val items = buildList {
        if (viewModel.hasTool(AudioTool.RECORD)) add(ToolRailItem(AudioTool.RECORD, stringResource(R.string.framekit_audio_tool_record), painterResource(UiR.drawable.framekit_ic_mic)))
        if (viewModel.hasTool(AudioTool.IMPORT)) add(ToolRailItem(AudioTool.IMPORT, stringResource(R.string.framekit_audio_tool_import), painterResource(R.drawable.framekit_ic_audio_file)))
        if (viewModel.hasTool(AudioTool.SPLIT)) add(ToolRailItem(AudioTool.SPLIT, stringResource(R.string.framekit_audio_tool_split), painterResource(UiR.drawable.framekit_ic_trim)))
        if (viewModel.hasTool(AudioTool.RANGE)) add(ToolRailItem(AudioTool.RANGE, stringResource(R.string.framekit_audio_tool_range), painterResource(R.drawable.framekit_ic_range)))
        if (viewModel.hasTool(AudioTool.VOLUME)) add(ToolRailItem(AudioTool.VOLUME, stringResource(R.string.framekit_audio_tool_volume), painterResource(UiR.drawable.framekit_ic_volume)))
    }
    Column {
        Text(
            stringResource(R.string.framekit_audio_tools_hint),
            color = FrameKitTheme.colors.foregroundMuted,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        )
        ToolRail(items = items, selected = null, onSelect = { tool ->
            when (tool) {
                AudioTool.RECORD -> viewModel.openRecordSheet(RecordTarget.INSERT)
                AudioTool.IMPORT -> onImport()
                AudioTool.SPLIT -> viewModel.splitAtPlayhead()
                AudioTool.RANGE -> viewModel.beginRange()
                AudioTool.VOLUME -> viewModel.selectClipAtPlayhead()
            }
        })
    }
}

/** 고른 구간: 잘라 내기, 이것만 남기기. */
@Composable
private fun RangePanel(viewModel: AudioEditorViewModel) {
    val colors = FrameKitTheme.colors
    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Text(stringResource(R.string.framekit_audio_range_hint), color = colors.foregroundMuted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 20.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton(rememberVectorPainter(Icons.Filled.Close), stringResource(UiR.string.framekit_action_cancel), colors.raised, colors.foreground, Modifier.weight(1f), viewModel::cancelRange)
            PillButton(painterResource(R.drawable.framekit_ic_range), stringResource(R.string.framekit_audio_keep_range), colors.raised, colors.foreground, Modifier.weight(1.3f), viewModel::keepRange)
            PillButton(rememberVectorPainter(Icons.Filled.Delete), stringResource(R.string.framekit_audio_delete_range), colors.error, Color.White, Modifier.weight(1.3f), viewModel::deleteRange)
        }
    }
}

/** 고른 조각: 나누기·삭제·반복(겹친 줄)과 볼륨·페이드. */
@Composable
private fun ClipPanel(state: AudioEditorUiState.Ready, viewModel: AudioEditorViewModel) {
    val colors = FrameKitTheme.colors
    val clip = state.selectedClip ?: return
    val length = state.project.rangeOf(clip).durationUs
    Column(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(if (clip.role == AudioRole.VOICE) R.string.framekit_audio_lane_voice else R.string.framekit_audio_lane_layer),
                color = colors.foreground,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 12.dp).weight(1f),
            )
            SmallAction(painterResource(UiR.drawable.framekit_ic_trim), stringResource(R.string.framekit_audio_tool_split), colors.foreground, viewModel::splitAtPlayhead)
            if (clip.role != AudioRole.VOICE) {
                SmallAction(rememberVectorPainter(Icons.Filled.Refresh), stringResource(R.string.framekit_audio_loop), if (clip.loop) colors.accent else colors.foregroundMuted) { viewModel.setSelectedLoop(!clip.loop) }
            }
            SmallAction(rememberVectorPainter(Icons.Filled.Delete), stringResource(R.string.framekit_audio_delete_clip), colors.foreground, viewModel::deleteSelected)
            SmallAction(rememberVectorPainter(Icons.Filled.Close), stringResource(UiR.string.framekit_action_close), colors.foregroundMuted) { viewModel.selectClip(null) }
        }
        ValueSlider(
            label = stringResource(R.string.framekit_audio_volume),
            value = (clip.volume * 100).toFloat(),
            valueRange = 0f..200f,
            onValueChange = viewModel::changeSelectedVolume,
            onValueChangeFinished = viewModel::finishGesture,
            formatValue = { "${it.roundToInt()}%" },
            resetValue = 100f,
            snapThreshold = 4f,
        )
        val maxFade = (minOf(length / 2, MAX_FADE_US) / 1_000_000f).coerceAtLeast(0.1f)
        listOf(true, false).forEach { fadeIn ->
            ValueSlider(
                label = stringResource(if (fadeIn) R.string.framekit_audio_fade_in else R.string.framekit_audio_fade_out),
                value = ((if (fadeIn) clip.fadeInUs else clip.fadeOutUs) / 1_000_000f).coerceAtMost(maxFade),
                valueRange = 0f..maxFade,
                onValueChange = { s -> val us = (s * 1_000_000).toLong(); if (fadeIn) viewModel.changeSelectedFade(us, null) else viewModel.changeSelectedFade(null, us) },
                onValueChangeFinished = viewModel::finishGesture,
                formatValue = { String.format(Locale.ROOT, "%.1fs", it) },
                resetValue = 0f,
            )
        }
    }
}

/** 녹음 시트. 끼워 넣기(뒤를 밀기)와 겹쳐 넣기를 고르고 녹음한다. */
@Composable
private fun RecordSheet(target: RecordTarget, viewModel: AudioEditorViewModel) {
    FrameKitDialog(
        title = stringResource(R.string.framekit_audio_record_title),
        message = stringResource(if (target == RecordTarget.INSERT) R.string.framekit_audio_insert_hint else R.string.framekit_audio_overlay_hint),
        onDismiss = viewModel::closeRecordSheet,
        actions = listOf(DialogAction(stringResource(UiR.string.framekit_action_cancel), DialogActionStyle.SECONDARY, onClick = viewModel::closeRecordSheet)),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            ChoiceChips(
                options = RecordTarget.entries,
                selected = target,
                label = { stringResource(if (it == RecordTarget.INSERT) R.string.framekit_audio_target_insert else R.string.framekit_audio_target_overlay) },
                onSelect = viewModel::setRecordTarget,
            )
            RecorderPanel(
                mode = viewModel.config.recordMode,
                maxRecordingMs = viewModel.config.maxRecordingMs,
                compact = true,
                onRecorded = viewModel::onRecorded,
                onTooShort = viewModel::onRecordingTooShort,
            )
        }
    }
}

@Composable
private fun PillButton(icon: Painter, label: String, container: Color, content: Color, modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier
            .height(48.dp)
            .background(container, RoundedCornerShape(24.dp))
            .semantics { role = Role.Button }
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(18.dp))
        Text(label, color = content, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.padding(start = 6.dp))
    }
}

@Composable
private fun SmallAction(icon: Painter, label: String, tint: Color, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
            Text(label, color = tint, style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }
    }
}

private const val SKIP_US = 5_000_000L
private const val MAX_FADE_US = 5_000_000L

// 이 높이 이상이면 타임라인 줄을 높게 그린다(아래 패널이 가장 클 때도 겹치지 않는 높이).
private val TALL_HEIGHT = 800.dp
