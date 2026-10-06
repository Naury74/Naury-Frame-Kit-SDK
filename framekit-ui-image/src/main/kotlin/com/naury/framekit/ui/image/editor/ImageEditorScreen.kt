package com.naury.framekit.ui.image.editor

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.ui.platform.LocalDensity
import com.naury.framekit.ui.layout.EditorLayout
import com.naury.framekit.ui.layout.EditorLayoutPolicy
import com.naury.framekit.ui.layout.FoldPosture
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.naury.framekit.ui.component.ApplyCancelBar
import com.naury.framekit.ui.component.DiscardChangesDialog
import com.naury.framekit.ui.component.EditorErrorView
import com.naury.framekit.ui.component.EditorLoadingView
import com.naury.framekit.ui.component.EditorTopBar
import com.naury.framekit.ui.component.ExportErrorDialog
import com.naury.framekit.ui.component.ExportOverlay
import com.naury.framekit.ui.component.HistoryControls
import com.naury.framekit.ui.component.ToolRail
import com.naury.framekit.ui.component.ToolRailItem
import com.naury.framekit.ui.design.FrameKitTheme
import com.naury.framekit.ui.image.R
import com.naury.framekit.ui.image.contract.ImageTool
import com.naury.framekit.ui.image.tool.AdjustToolPanel
import com.naury.framekit.ui.image.tool.CropToolPanel
import com.naury.framekit.ui.image.tool.FilterToolPanel
import com.naury.framekit.ui.image.tool.RotateToolPanel
import com.naury.framekit.ui.R as UiR

@Composable
internal fun ImageEditorScreen(viewModel: ImageEditorViewModel, posture: FoldPosture = FoldPosture.Flat) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var pickerLaunched by rememberSaveable { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        pickerLaunched = false
        viewModel.onPicked(uri)
    }
    BackHandler { viewModel.onBack() }

    Box(Modifier.fillMaxSize().background(FrameKitTheme.colors.background)) {
        when (val current = state) {
            ImageEditorUiState.AwaitingPick -> LaunchedEffect(Unit) {
                // 화면 회전으로 다시 그려질 때 picker가 두 번 열리지 않도록 실행 여부를 저장해 둔다.
                if (!pickerLaunched) {
                    pickerLaunched = true
                    picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }
            }
            ImageEditorUiState.Loading -> EditorLoadingView(Modifier.windowInsetsPadding(WindowInsets.safeDrawing))
            is ImageEditorUiState.LoadFailed -> EditorErrorView(
                code = current.code,
                onClose = viewModel::requestClose,
                onChooseAnother = if (current.canChooseAnother) viewModel::chooseAnother else null,
                modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing),
            )
            is ImageEditorUiState.Ready -> ReadyContent(current, viewModel, posture)
        }
    }
}

@Composable
private fun ReadyContent(state: ImageEditorUiState.Ready, viewModel: ImageEditorViewModel, posture: FoldPosture) {
    val snackbar = remember { SnackbarHostState() }
    val applyHint = stringResource(UiR.string.framekit_apply_first)
    LaunchedEffect(state.showApplyHint) {
        if (state.showApplyHint) {
            snackbar.showSnackbar(applyHint)
            viewModel.dismissApplyHint()
        }
    }
    val noticeText = state.notice?.let {
        stringResource(
            when (it) {
                SessionNotice.RESTORED -> UiR.string.framekit_session_restored
                SessionNotice.EXPORT_INTERRUPTED -> UiR.string.framekit_export_interrupted
            },
        )
    }
    LaunchedEffect(state.notice) {
        if (noticeText != null) {
            snackbar.showSnackbar(noticeText)
            viewModel.dismissNotice()
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
                ToolArea(state, viewModel, Modifier.fillMaxWidth(), layout.maxControlsWidthDp)
            }
            is EditorLayout.SidePanel -> Row(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                CanvasArea(state, viewModel, Modifier.weight(1f).fillMaxHeight())
                Column(Modifier.width(layout.panelWidthDp.dp).fillMaxHeight()) {
                    topBar()
                    Spacer(Modifier.weight(1f))
                    ToolArea(state, viewModel, Modifier.fillMaxWidth(), maxWidthDp = null)
                }
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
                    ToolArea(state, viewModel, Modifier.fillMaxWidth(), EditorLayoutPolicy.MAX_CONTROLS_WIDTH_DP)
                }
            }
            is EditorLayout.SplitAtVerticalHinge -> Row(Modifier.fillMaxSize()) {
                CanvasArea(
                    state,
                    viewModel,
                    Modifier
                        .width(with(density) { layout.hingeLeftPx.toDp() })
                        .fillMaxHeight()
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical + WindowInsetsSides.Start)),
                )
                Spacer(Modifier.width(with(density) { (layout.hingeRightPx - layout.hingeLeftPx).toDp() }))
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical + WindowInsetsSides.End)),
                ) {
                    topBar()
                    Spacer(Modifier.weight(1f))
                    ToolArea(state, viewModel, Modifier.fillMaxWidth(), maxWidthDp = null)
                }
            }
        }
        SnackbarHost(
            snackbar,
            Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.safeDrawing).padding(bottom = 96.dp),
        )
    }

    when (val export = state.export) {
        is ExportUiState.Running -> ExportOverlay(stage = export.stage, onCancel = viewModel::cancelExport)
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
private fun CanvasArea(state: ImageEditorUiState.Ready, viewModel: ImageEditorViewModel, modifier: Modifier) {
    Box(modifier) {
        val rendered by viewModel.renderedPreview.collectAsStateWithLifecycle()
        ImageCanvas(
            state = state,
            rendered = rendered,
            onViewportSize = viewModel::onViewportSize,
            onBeginCropDrag = viewModel::beginCropDrag,
            onDragCrop = viewModel::dragCrop,
            onEndCropDrag = viewModel::endCropDrag,
            onShowOriginal = viewModel::showOriginal,
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

@Composable
private fun ToolArea(state: ImageEditorUiState.Ready, viewModel: ImageEditorViewModel, modifier: Modifier, maxWidthDp: Int?) {
    val tools = viewModel.config.enabledTools.toList()
    AnimatedContent(
        targetState = state.activeTool,
        modifier = modifier.background(FrameKitTheme.colors.background).wrapContentWidth(Alignment.CenterHorizontally),
        transitionSpec = { fadeIn(tween(TOOL_TRANSITION_MS)) togetherWith fadeOut(tween(TOOL_TRANSITION_MS)) },
        label = "tool-area",
    ) { tool ->
        Column(if (maxWidthDp != null) Modifier.widthIn(max = maxWidthDp.dp).fillMaxWidth() else Modifier.fillMaxWidth()) {
            when (tool) {
                ImageTool.CROP -> ToolPanelWithActions(R.string.framekit_tool_crop, viewModel, isDraft = true) {
                    CropToolPanel(aspect = state.cropAspect, onSelectAspect = viewModel::selectAspect)
                }
                ImageTool.ROTATE -> ToolPanelWithActions(R.string.framekit_tool_rotate, viewModel, isDraft = true) {
                    RotateToolPanel(
                        straightenDegrees = state.displayed.geometry.straightenDegrees,
                        onRotateLeft = viewModel::rotateLeft,
                        onRotateRight = viewModel::rotateRight,
                        onFlipHorizontal = viewModel::flipHorizontal,
                        onFlipVertical = viewModel::flipVertical,
                        onStraighten = viewModel::changeStraighten,
                        onStraightenFinished = viewModel::finishStraighten,
                    )
                }
                ImageTool.ADJUST -> ToolPanelWithActions(R.string.framekit_tool_adjust, viewModel, isDraft = false) {
                    AdjustToolPanel(
                        adjustments = state.displayed.adjustments,
                        selected = state.adjustKind,
                        onSelect = viewModel::selectAdjustment,
                        onChange = viewModel::changeAdjustment,
                        onChangeFinished = viewModel::finishGesture,
                    )
                }
                ImageTool.FILTER -> ToolPanelWithActions(R.string.framekit_tool_filter, viewModel, isDraft = false) {
                    val thumbnails by viewModel.filterThumbnails.collectAsStateWithLifecycle()
                    FilterToolPanel(
                        selection = state.displayed.filter,
                        thumbnails = thumbnails,
                        onSelect = viewModel::selectFilter,
                        onIntensity = viewModel::changeFilterIntensity,
                        onIntensityFinished = viewModel::finishGesture,
                    )
                }
                null -> if (tools.isNotEmpty()) {
                    ToolRail(
                        items = tools.map { it.railItem() },
                        selected = null,
                        onSelect = viewModel::selectTool,
                    )
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.ToolPanelWithActions(title: Int, viewModel: ImageEditorViewModel, isDraft: Boolean, panel: @Composable () -> Unit) {
    panel()
    ApplyCancelBar(
        title = stringResource(title),
        onCancel = if (isDraft) viewModel::cancelTool else null,
        onApply = if (isDraft) viewModel::applyTool else viewModel::closeTool,
        onReset = viewModel::resetTool,
    )
}

@Composable
private fun ImageTool.railItem(): ToolRailItem<ImageTool> = when (this) {
    ImageTool.CROP -> ToolRailItem(this, stringResource(R.string.framekit_tool_crop), painterResource(UiR.drawable.framekit_ic_crop))
    ImageTool.ROTATE -> ToolRailItem(this, stringResource(R.string.framekit_tool_rotate), painterResource(UiR.drawable.framekit_ic_rotate_right))
    ImageTool.ADJUST -> ToolRailItem(this, stringResource(R.string.framekit_tool_adjust), painterResource(UiR.drawable.framekit_ic_adjust))
    ImageTool.FILTER -> ToolRailItem(this, stringResource(R.string.framekit_tool_filter), painterResource(UiR.drawable.framekit_ic_filter))
}

private const val TOOL_TRANSITION_MS = 200
