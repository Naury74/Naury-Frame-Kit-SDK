package com.naury.framekit.ui.image.editor

import com.naury.framekit.android.input.MediaKind
import com.naury.framekit.ui.source.rememberSourceLaunchers
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.naury.framekit.core.overlay.ImageOverlay
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
import com.naury.framekit.ui.image.R
import com.naury.framekit.ui.image.contract.ImageTool
import com.naury.framekit.ui.tool.AdjustToolPanel
import com.naury.framekit.ui.tool.CropToolPanel
import com.naury.framekit.ui.image.tool.CutoutToolPanel
import com.naury.framekit.ui.image.tool.DrawToolPanel
import com.naury.framekit.ui.tool.FilterToolPanel
import com.naury.framekit.ui.tool.PrivacyToolPanel
import com.naury.framekit.ui.tool.StickerToolPanel
import com.naury.framekit.ui.tool.TextToolPanel
import com.naury.framekit.ui.tool.RotateToolPanel
import com.naury.framekit.ui.R as UiR

@Composable
internal fun ImageEditorScreen(viewModel: ImageEditorViewModel, posture: FoldPosture = FoldPosture.Flat) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // 화면 회전으로 다시 그려질 때 picker·카메라가 두 번 열리지 않도록 실행 여부를 저장해 둔다.
    var sourceLaunched by rememberSaveable { mutableStateOf(false) }
    val launchers = rememberSourceLaunchers(
        maxItems = viewModel.pageLimit,
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
            is ImageEditorUiState.AwaitingSource -> LaunchedEffect(current.mode) {
                if (!sourceLaunched) {
                    sourceLaunched = true
                    when (val mode = current.mode) {
                        is SourceMode.Pick -> launchers.pick(MediaKind.IMAGE, mode.maxItems)
                        SourceMode.Capture -> launchers.capture(MediaKind.IMAGE, viewModel::prepareCapture)
                    }
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
                SessionNotice.PAGE_LIMIT -> R.string.framekit_page_limit
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
                        // 패널이 길거나 키보드가 올라와도 캔버스가 사라지지 않도록 도구 영역 높이를 제한하고 안쪽을 스크롤한다.
                        ToolArea(state, viewModel, Modifier.fillMaxWidth().heightIn(max = available * MAX_TOOL_AREA_FRACTION), layout.maxControlsWidthDp, scrollPanel = true)
                    }
                }
                is EditorLayout.SidePanel -> Row(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                    CanvasArea(state, viewModel, Modifier.weight(1f).fillMaxHeight())
                    Column(Modifier.width(layout.panelWidthDp.dp).fillMaxHeight()) {
                        topBar()
                        // 넓은 화면에서는 도구를 패널 위쪽부터 채워 빈 영역을 줄이고, 길어지면 스크롤한다.
                        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                            ToolArea(state, viewModel, Modifier.fillMaxWidth(), maxWidthDp = null, wide = true)
                        }
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
                        ToolArea(state, viewModel, Modifier.fillMaxWidth(), EditorLayoutPolicy.MAX_CONTROLS_WIDTH_DP, scrollPanel = true)
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
                        // 넓은 화면에서는 도구를 패널 위쪽부터 채워 빈 영역을 줄이고, 길어지면 스크롤한다.
                        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                            ToolArea(state, viewModel, Modifier.fillMaxWidth(), maxWidthDp = null, wide = true)
                        }
                    }
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
private fun CanvasArea(state: ImageEditorUiState.Ready, viewModel: ImageEditorViewModel, modifier: Modifier) {
    Box(modifier) {
        val rendered by viewModel.renderedPreview.collectAsStateWithLifecycle()
        val masks by viewModel.cutoutMasks.collectAsStateWithLifecycle()
        ImageCanvas(
            state = state,
            rendered = rendered,
            cutoutMask = state.displayed.cutout?.let { masks[it.maskAssetId] },
            actions = viewModel,
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
                onCompare = if (state.activeTool == null && history.isDirty) viewModel::showOriginal else null,
            )
        }
        // 안내 메시지는 캔버스 아래쪽에 띄워 레이아웃과 상관없이 도구 패널을 가리지 않게 한다.
        SnackbarHost(LocalEditorSnackbar.current, Modifier.align(Alignment.BottomCenter).padding(8.dp))
    }
}

@Composable
private fun ToolArea(
    state: ImageEditorUiState.Ready,
    viewModel: ImageEditorViewModel,
    modifier: Modifier,
    maxWidthDp: Int?,
    wide: Boolean = false,
    scrollPanel: Boolean = false,
) {
    val tools = viewModel.config.enabledTools.filter { it != ImageTool.CUTOUT || viewModel.cutoutAvailable }
    AnimatedContent(
        targetState = state.activeTool,
        modifier = modifier.background(FrameKitTheme.colors.background).wrapContentWidth(Alignment.CenterHorizontally),
        transitionSpec = { fadeIn(tween(TOOL_TRANSITION_MS)) togetherWith fadeOut(tween(TOOL_TRANSITION_MS)) },
        label = "tool-area",
    ) { tool ->
        Column(if (maxWidthDp != null) Modifier.widthIn(max = maxWidthDp.dp).fillMaxWidth() else Modifier.fillMaxWidth()) {
            when (tool) {
                ImageTool.CROP -> ToolPanelWithActions(UiR.string.framekit_tool_crop, viewModel, isDraft = true, scrollPanel) {
                    CropToolPanel(aspect = state.cropAspect, onSelectAspect = viewModel::selectAspect)
                }
                ImageTool.ROTATE -> ToolPanelWithActions(UiR.string.framekit_tool_rotate, viewModel, isDraft = true, scrollPanel) {
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
                ImageTool.ADJUST -> ToolPanelWithActions(UiR.string.framekit_tool_adjust, viewModel, isDraft = false, scrollPanel) {
                    AdjustToolPanel(
                        adjustments = state.displayed.adjustments,
                        selected = state.adjustKind,
                        onSelect = viewModel::selectAdjustment,
                        onChange = viewModel::changeAdjustment,
                        onChangeFinished = viewModel::finishGesture,
                    )
                }
                ImageTool.TEXT -> ToolPanelWithActions(UiR.string.framekit_tool_text, viewModel, isDraft = true, scrollPanel) {
                    val editing = state.displayed.overlays.firstOrNull { it.id == state.editingTextId } as? ImageOverlay.Text
                    if (editing != null) {
                        TextToolPanel(text = editing, onText = viewModel::updateText, onStyle = viewModel::updateTextStyle)
                    }
                }
                ImageTool.STICKER -> ToolPanelWithActions(UiR.string.framekit_tool_sticker, viewModel, isDraft = false, scrollPanel) {
                    StickerToolPanel(category = state.stickerCategory, onCategory = viewModel::selectStickerCategory, onAdd = viewModel::addSticker)
                }
                ImageTool.DRAW -> ToolPanelWithActions(R.string.framekit_tool_draw, viewModel, isDraft = false, scrollPanel) {
                    DrawToolPanel(brush = state.brush, onBrush = viewModel::updateBrush)
                }
                ImageTool.CUTOUT -> ToolPanelWithActions(R.string.framekit_tool_cutout, viewModel, isDraft = false, scrollPanel) {
                    CutoutToolPanel(
                        applied = state.displayed.cutout != null,
                        status = state.cutoutStatus,
                        onRemove = viewModel::removeBackground,
                        onRestore = viewModel::restoreBackground,
                    )
                }
                ImageTool.PRIVACY -> ToolPanelWithActions(UiR.string.framekit_tool_privacy, viewModel, isDraft = false, scrollPanel) {
                    PrivacyToolPanel(settings = state.privacy, onChange = viewModel::updatePrivacy)
                }
                ImageTool.FILTER -> ToolPanelWithActions(UiR.string.framekit_tool_filter, viewModel, isDraft = false, scrollPanel) {
                    val thumbnails by viewModel.filterThumbnails.collectAsStateWithLifecycle()
                    FilterToolPanel(
                        selection = state.displayed.filter,
                        thumbnails = thumbnails,
                        onSelect = viewModel::selectFilter,
                        onIntensity = viewModel::changeFilterIntensity,
                        onIntensityFinished = viewModel::finishGesture,
                    )
                }
                null -> {
                    if (viewModel.pageLimit > 1) PagesRow(state, viewModel)
                    when {
                        tools.isEmpty() -> Unit
                        wide -> ToolGrid(items = tools.map { it.railItem() }, onSelect = viewModel::selectTool)
                        else -> ToolRail(items = tools.map { it.railItem() }, selected = null, onSelect = viewModel::selectTool)
                    }
                }
            }
        }
    }
}

/** 여러 장 편집에서 도구 목록 위에 두는 쪽 목록. */
@Composable
private fun PagesRow(state: ImageEditorUiState.Ready, viewModel: ImageEditorViewModel) {
    val thumbnails by viewModel.pageThumbnails.collectAsStateWithLifecycle()
    val adder = rememberSourceLaunchers(
        maxItems = viewModel.pageLimit - state.pageCount,
        onPicked = viewModel::addPages,
        onCaptured = {},
        onFailed = {},
    )
    PageStrip(
        pageIds = viewModel.pageIds,
        selected = state.pageIndex,
        thumbnails = thumbnails,
        canAdd = state.pageCount < viewModel.pageLimit,
        enabled = state.export == null && !state.transaction.isActive,
        onSelect = viewModel::selectPage,
        onAdd = { adder.pick(MediaKind.IMAGE, viewModel.pageLimit - state.pageCount) },
        onMove = viewModel::moveCurrentPage,
        onRemove = viewModel::removeCurrentPage,
    )
}

@Composable
private fun ColumnScope.ToolPanelWithActions(title: Int, viewModel: ImageEditorViewModel, isDraft: Boolean, scroll: Boolean, panel: @Composable () -> Unit) {
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
private fun ImageTool.railItem(): ToolRailItem<ImageTool> = when (this) {
    ImageTool.CROP -> ToolRailItem(this, stringResource(UiR.string.framekit_tool_crop), painterResource(UiR.drawable.framekit_ic_crop))
    ImageTool.ROTATE -> ToolRailItem(this, stringResource(UiR.string.framekit_tool_rotate), painterResource(UiR.drawable.framekit_ic_rotate_right))
    ImageTool.ADJUST -> ToolRailItem(this, stringResource(UiR.string.framekit_tool_adjust), painterResource(UiR.drawable.framekit_ic_adjust))
    ImageTool.FILTER -> ToolRailItem(this, stringResource(UiR.string.framekit_tool_filter), painterResource(UiR.drawable.framekit_ic_filter))
    ImageTool.TEXT -> ToolRailItem(this, stringResource(UiR.string.framekit_tool_text), painterResource(UiR.drawable.framekit_ic_text))
    ImageTool.STICKER -> ToolRailItem(this, stringResource(UiR.string.framekit_tool_sticker), painterResource(UiR.drawable.framekit_ic_sticker))
    ImageTool.DRAW -> ToolRailItem(this, stringResource(R.string.framekit_tool_draw), painterResource(UiR.drawable.framekit_ic_draw))
    ImageTool.CUTOUT -> ToolRailItem(this, stringResource(R.string.framekit_tool_cutout), painterResource(UiR.drawable.framekit_ic_cutout))
    ImageTool.PRIVACY -> ToolRailItem(this, stringResource(UiR.string.framekit_tool_privacy), painterResource(UiR.drawable.framekit_ic_privacy))
}

private const val TOOL_TRANSITION_MS = 200

// 좁은 화면에서 도구 영역이 차지할 수 있는 최대 비율. 나머지는 캔버스 몫이다.
private const val MAX_TOOL_AREA_FRACTION = 0.55f

private val LocalEditorSnackbar = staticCompositionLocalOf { SnackbarHostState() }
