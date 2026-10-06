package com.naury.framekit.ui.image.editor

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
import com.naury.framekit.ui.image.tool.CropToolPanel
import com.naury.framekit.ui.image.tool.RotateToolPanel
import com.naury.framekit.ui.R as UiR

@Composable
internal fun ImageEditorScreen(viewModel: ImageEditorViewModel) {
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
            is ImageEditorUiState.Ready -> ReadyContent(current, viewModel)
        }
    }
}

@Composable
private fun ReadyContent(state: ImageEditorUiState.Ready, viewModel: ImageEditorViewModel) {
    val snackbar = remember { SnackbarHostState() }
    val applyHint = stringResource(UiR.string.framekit_apply_first)
    LaunchedEffect(state.showApplyHint) {
        if (state.showApplyHint) {
            snackbar.showSnackbar(applyHint)
            viewModel.dismissApplyHint()
        }
    }
    val view = LocalView.current
    val exporting = state.export is ExportUiState.Running
    DisposableEffect(exporting) {
        view.keepScreenOn = exporting
        onDispose { view.keepScreenOn = false }
    }

    BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        val wide = maxWidth > maxHeight && maxWidth >= 600.dp
        if (wide) {
            Row(Modifier.fillMaxSize()) {
                CanvasArea(state, viewModel, Modifier.weight(1f).fillMaxHeight())
                Column(Modifier.width(360.dp).fillMaxHeight()) {
                    EditorTopBar(onClose = viewModel::requestClose, onSave = viewModel::save, saveEnabled = state.export == null)
                    Spacer(Modifier.weight(1f))
                    ToolArea(state, viewModel)
                }
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                EditorTopBar(onClose = viewModel::requestClose, onSave = viewModel::save, saveEnabled = state.export == null)
                CanvasArea(state, viewModel, Modifier.weight(1f).fillMaxWidth())
                ToolArea(state, viewModel)
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 96.dp))
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
        ImageCanvas(
            state = state,
            onBeginCropDrag = viewModel::beginCropDrag,
            onDragCrop = viewModel::dragCrop,
            onEndCropDrag = viewModel::endCropDrag,
            onShowOriginal = viewModel::showOriginal,
        )
        if (state.activeTool == null) {
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
private fun ToolArea(state: ImageEditorUiState.Ready, viewModel: ImageEditorViewModel) {
    val tools = viewModel.config.enabledTools.toList()
    AnimatedContent(
        targetState = state.activeTool,
        transitionSpec = { fadeIn(tween(TOOL_TRANSITION_MS)) togetherWith fadeOut(tween(TOOL_TRANSITION_MS)) },
        label = "tool-area",
    ) { tool ->
        Column(Modifier.fillMaxWidth().background(FrameKitTheme.colors.background)) {
            when (tool) {
                ImageTool.CROP -> ToolPanelWithActions(R.string.framekit_tool_crop, viewModel) {
                    CropToolPanel(aspect = state.cropAspect, onSelectAspect = viewModel::selectAspect)
                }
                ImageTool.ROTATE -> ToolPanelWithActions(R.string.framekit_tool_rotate, viewModel) {
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
private fun ColumnScope.ToolPanelWithActions(title: Int, viewModel: ImageEditorViewModel, panel: @Composable () -> Unit) {
    panel()
    ApplyCancelBar(
        title = stringResource(title),
        onCancel = viewModel::cancelTool,
        onApply = viewModel::applyTool,
        onReset = viewModel::resetTool,
    )
}

@Composable
private fun ImageTool.railItem(): ToolRailItem<ImageTool> = when (this) {
    ImageTool.CROP -> ToolRailItem(this, stringResource(R.string.framekit_tool_crop), painterResource(UiR.drawable.framekit_ic_crop))
    ImageTool.ROTATE -> ToolRailItem(this, stringResource(R.string.framekit_tool_rotate), painterResource(UiR.drawable.framekit_ic_rotate_right))
}

private const val TOOL_TRANSITION_MS = 200
