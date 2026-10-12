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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import com.naury.framekit.ui.component.DialogActionStyle
import com.naury.framekit.ui.component.DialogAction
import com.naury.framekit.ui.component.FrameKitDialog
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.Icons
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.shape.CircleShape
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
import com.naury.framekit.ui.component.ToolActionBar
import com.naury.framekit.ui.component.ApplyDraftDialog
import com.naury.framekit.ui.component.FrameKitSnackbarHost
import com.naury.framekit.ui.component.verticalFadingScroll
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
import com.naury.framekit.ui.image.tool.CutoutModelDownloadDialog
import com.naury.framekit.ui.image.tool.CutoutToolPanel
import androidx.compose.material3.CircularProgressIndicator
import kotlin.math.roundToInt
import com.naury.framekit.ui.image.tool.DocumentToolPanel
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
                SessionNotice.DOCUMENT_APPLIED -> R.string.framekit_document_applied
                SessionNotice.DOCUMENT_FAILED -> R.string.framekit_document_failed
                SessionNotice.CUTOUT_MODEL_READY -> R.string.framekit_cutout_model_ready
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
                // 도구 화면에서는 X 대신 뒤로 가기 화살표와 도구 이름을 보여 준다. 뒤로 가기는 시스템 뒤로 가기와 같아서
                // 바뀐 내용이 있으면 적용할지 먼저 묻는다.
                val tool = state.activeTool
                EditorTopBar(
                    onClose = if (tool != null) viewModel::onBack else viewModel::requestClose,
                    onSave = viewModel::save,
                    saveEnabled = state.export == null,
                    toolTitle = tool?.railItem()?.label,
                )
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
                        // 넓은 화면에서는 도구를 패널 위쪽부터 채우고 길어지면 스크롤한다. 적용 줄은 패널 맨 아래에 고정한다.
                        ToolArea(state, viewModel, Modifier.fillMaxWidth().weight(1f), maxWidthDp = null, wide = true, scrollPanel = true)
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
                        // 넓은 화면에서는 도구를 패널 위쪽부터 채우고 길어지면 스크롤한다. 적용 줄은 패널 맨 아래에 고정한다.
                        ToolArea(state, viewModel, Modifier.fillMaxWidth().weight(1f), maxWidthDp = null, wide = true, scrollPanel = true)
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
    val documentText = state.documentText
    if (state.showDocumentText && documentText != null) {
        DocumentTextDialog(documentText, onDismiss = { viewModel.showDocumentText(false) })
    }
    if (state.showDraftDialog) {
        // 확인창 제목과 버튼에 열려 있는 도구 이름을 넣는다(툴바와 같은 이름).
        ApplyDraftDialog(toolName = state.activeTool?.railItem()?.label.orEmpty(), onApply = viewModel::applyDraftFromDialog, onDiscard = viewModel::discardDraftFromDialog, onKeepEditing = viewModel::dismissDraftDialog)
    }
    if (state.showModelDownloadDialog) {
        CutoutModelDownloadDialog(onConfirm = viewModel::confirmModelDownload, onDismiss = viewModel::dismissModelDownloadDialog)
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
        if (state.activeTool == null && state.export == null) {
            val suggestion = state.documentSuggestion
            Column(Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                if (suggestion != null && !state.documentSuggestionDismissed && !state.documentRectified) {
                    DocumentPill(
                        text = stringResource(R.string.framekit_document_detected),
                        action = stringResource(R.string.framekit_document_scan),
                        onAction = { viewModel.selectTool(ImageTool.DOCUMENT) },
                        onClose = viewModel::dismissDocumentSuggestion,
                    )
                }
                if (state.documentText != null) {
                    DocumentPill(
                        text = stringResource(R.string.framekit_document_text_found),
                        action = stringResource(R.string.framekit_document_text_view),
                        onAction = { viewModel.showDocumentText(true) },
                        onClose = null,
                    )
                }
            }
        }
        // 배경을 지우거나 모델을 받는 동안 사진 위에도 진행 상황을 띄워, 패널을 보지 않아도 기다리는 중임을 알 수 있게 한다.
        if (state.activeTool == ImageTool.CUTOUT) CutoutCanvasStatus(state.cutoutStatus, Modifier.align(Alignment.Center))
        // 안내 메시지는 캔버스 아래쪽에 띄워 레이아웃과 상관없이 도구 패널을 가리지 않게 한다.
        FrameKitSnackbarHost(LocalEditorSnackbar.current, Modifier.align(Alignment.BottomCenter).padding(8.dp))
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
    // 문서 보정은 문서로 판별된 사진(또는 이미 보정한 쪽)에서만 보여 줘, 일반 사진에서는 툴바를 차지하지 않게 한다.
    val tools = viewModel.config.enabledTools.filter {
        (it != ImageTool.CUTOUT || viewModel.cutoutAvailable) &&
            (it != ImageTool.DOCUMENT || state.documentSuggestion != null || state.documentRectified)
    }
    AnimatedContent(
        targetState = state.activeTool,
        modifier = modifier.background(FrameKitTheme.colors.background).wrapContentWidth(Alignment.CenterHorizontally),
        transitionSpec = { fadeIn(tween(TOOL_TRANSITION_MS)) togetherWith fadeOut(tween(TOOL_TRANSITION_MS)) },
        label = "tool-area",
    ) { tool ->
        val width = if (maxWidthDp != null) Modifier.widthIn(max = maxWidthDp.dp).fillMaxWidth() else Modifier.fillMaxWidth()
        Column(if (wide) width.fillMaxHeight() else width) {
            when (tool) {
                ImageTool.CROP -> ToolPanelWithActions(viewModel, isDraft = true, scrollPanel, wide, resettable = true) {
                    CropToolPanel(
                        aspect = state.cropAspect,
                        onSelectAspect = viewModel::selectAspect,
                        onRotateLeft = viewModel::rotateLeft,
                        onRotateRight = viewModel::rotateRight,
                        onFlipHorizontal = viewModel::flipHorizontal,
                    )
                }
                ImageTool.ROTATE -> ToolPanelWithActions(viewModel, isDraft = true, scrollPanel, wide, resettable = true) {
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
                ImageTool.ADJUST -> ToolPanelWithActions(viewModel, isDraft = false, scrollPanel, wide, resettable = true) {
                    AdjustToolPanel(
                        adjustments = state.displayed.adjustments,
                        selected = state.adjustKind,
                        onSelect = viewModel::selectAdjustment,
                        onChange = viewModel::changeAdjustment,
                        onChangeFinished = viewModel::finishGesture,
                    )
                }
                ImageTool.TEXT -> ToolPanelWithActions(viewModel, isDraft = true, scrollPanel, wide) {
                    val editing = state.displayed.overlays.firstOrNull { it.id == state.editingTextId } as? ImageOverlay.Text
                    if (editing != null) {
                        TextToolPanel(text = editing, onText = viewModel::updateText, onStyle = viewModel::updateTextStyle, onOpacity = viewModel::updateTextOpacity)
                    }
                }
                ImageTool.STICKER -> ToolPanelWithActions(viewModel, isDraft = false, scrollPanel, wide) {
                    StickerToolPanel(category = state.stickerCategory, onCategory = viewModel::selectStickerCategory, onAdd = viewModel::addSticker)
                }
                ImageTool.DRAW -> ToolPanelWithActions(viewModel, isDraft = false, scrollPanel, wide) {
                    DrawToolPanel(brush = state.brush, onBrush = viewModel::updateBrush)
                }
                // 문서 펴기는 적용해야 원본이 바뀐다. 뒤로 가면 아무것도 바뀌지 않는다.
                ImageTool.DOCUMENT -> ToolPanelWithActions(viewModel, isDraft = false, scrollPanel, wide, onApply = viewModel::applyDocument) {
                    DocumentToolPanel(
                        busy = state.documentBusy,
                        rectified = state.documentRectified,
                        scanMode = state.scanMode,
                        onScanMode = viewModel::selectScanMode,
                        onDetect = viewModel::detectDocument,
                        onWholeImage = viewModel::useWholeImage,
                        onRestore = viewModel::restoreOriginalDocument,
                    )
                }
                ImageTool.CUTOUT -> ToolPanelWithActions(viewModel, isDraft = false, scrollPanel, wide) {
                    CutoutToolPanel(
                        applied = state.displayed.cutout != null,
                        status = state.cutoutStatus,
                        onRemove = viewModel::removeBackground,
                        onRestore = viewModel::restoreBackground,
                        onDownloadModel = viewModel::requestModelDownload,
                        onRetryDownload = viewModel::retryModelDownload,
                    )
                }
                ImageTool.PRIVACY -> ToolPanelWithActions(viewModel, isDraft = false, scrollPanel, wide) {
                    PrivacyToolPanel(settings = state.privacy, onChange = viewModel::updatePrivacy)
                }
                ImageTool.FILTER -> ToolPanelWithActions(viewModel, isDraft = false, scrollPanel, wide, resettable = true) {
                    val thumbnails by viewModel.filterThumbnails.collectAsStateWithLifecycle()
                    FilterToolPanel(
                        selection = state.displayed.filter,
                        thumbnails = thumbnails,
                        onSelect = viewModel::selectFilter,
                        onIntensity = viewModel::changeFilterIntensity,
                        onIntensityFinished = viewModel::finishGesture,
                    )
                }
                null -> if (wide) {
                    Column(Modifier.weight(1f).verticalFadingScroll()) {
                        if (viewModel.pageLimit > 1) PagesRow(state, viewModel)
                        if (tools.isNotEmpty()) ToolGrid(items = tools.map { it.railItem() }, onSelect = viewModel::selectTool)
                    }
                } else {
                    if (viewModel.pageLimit > 1) PagesRow(state, viewModel)
                    if (tools.isNotEmpty()) ToolRail(items = tools.map { it.railItem() }, selected = null, onSelect = viewModel::selectTool)
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

/**
 * 도구 패널과 하단 적용 줄. 제목과 뒤로 가기(취소)는 상단 바가 맡는다.
 *
 * @param fill 넓은 화면처럼 패널이 남는 높이를 모두 차지해 적용 줄을 맨 아래에 고정할 때 `true`.
 * @param resettable 되돌릴 기준이 분명한 도구(자르기·회전·보정·필터)만 초기화를 둔다. 텍스트·스티커·그리기 등에서는
 *   눌러도 아무 일이 없어 혼란스럽다.
 */
@Composable
private fun ColumnScope.ToolPanelWithActions(
    viewModel: ImageEditorViewModel,
    isDraft: Boolean,
    scroll: Boolean,
    fill: Boolean,
    resettable: Boolean = false,
    onApply: () -> Unit = if (isDraft) viewModel::applyTool else viewModel::closeTool,
    panel: @Composable () -> Unit,
) {
    if (scroll) {
        // 높이가 모자라면 패널만 스크롤하고 적용 줄은 항상 보이게 둔다.
        Column(Modifier.weight(1f, fill = fill).verticalFadingScroll()) { panel() }
    } else {
        panel()
    }
    ToolActionBar(onApply = onApply, onReset = if (resettable) viewModel::resetTool else null)
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
    ImageTool.DOCUMENT -> ToolRailItem(this, stringResource(R.string.framekit_tool_document), painterResource(UiR.drawable.framekit_ic_document))
    ImageTool.PRIVACY -> ToolRailItem(this, stringResource(UiR.string.framekit_tool_privacy), painterResource(UiR.drawable.framekit_ic_privacy))
}

private const val TOOL_TRANSITION_MS = 200

// 좁은 화면에서 도구 영역이 차지할 수 있는 최대 비율. 나머지는 캔버스 몫이다.
private const val MAX_TOOL_AREA_FRACTION = 0.55f

private val LocalEditorSnackbar = staticCompositionLocalOf { SnackbarHostState() }

/** 배경 제거 중이거나 모델을 내려받는 중일 때 사진 가운데에 띄우는 진행 알약. */
@Composable
private fun CutoutCanvasStatus(status: CutoutStatus?, modifier: Modifier) {
    val text = when (status) {
        CutoutStatus.Processing -> stringResource(R.string.framekit_cutout_processing)
        is CutoutStatus.ModelDownloading -> stringResource(R.string.framekit_cutout_model_downloading) +
            (status.fraction?.takeIf { !status.installing }?.let { " ${(it * 100).roundToInt()}%" } ?: "")
        else -> return
    }
    val colors = FrameKitTheme.colors
    Row(
        modifier.shadow(8.dp, CircleShape).background(colors.raised.copy(alpha = 0.94f), CircleShape).padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(color = colors.accent, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
        Text(text, color = colors.foreground, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 10.dp))
    }
}

/** 캔버스 아래쪽에 뜨는 작은 안내 알약. 문서 감지 제안과 인식한 글자 보기에 쓴다. */
@Composable
private fun DocumentPill(text: String, action: String, onAction: () -> Unit, onClose: (() -> Unit)?) {
    val colors = FrameKitTheme.colors
    Row(
        Modifier
            .padding(top = 8.dp)
            .shadow(6.dp, CircleShape)
            .background(colors.raised, CircleShape)
            .padding(start = 14.dp, end = if (onClose != null) 4.dp else 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(UiR.drawable.framekit_ic_document), contentDescription = null, tint = colors.accent, modifier = Modifier.size(18.dp))
        Text(text, color = colors.foreground, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 8.dp, end = 4.dp))
        TextButton(onClick = onAction) { Text(action, color = colors.accent, fontWeight = FontWeight.SemiBold) }
        if (onClose != null) {
            IconButton(onClick = onClose, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.framekit_action_close_dialog), tint = colors.foregroundMuted, modifier = Modifier.size(18.dp))
            }
        }
    }
}

/** 문서 보정 뒤 인식한 글자를 보여 주고 클립보드로 복사한다. 글자는 길게 눌러 일부만 고를 수도 있다. */
@Composable
private fun DocumentTextDialog(text: String, onDismiss: () -> Unit) {
    val colors = FrameKitTheme.colors
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val copied = stringResource(R.string.framekit_document_text_copied)
    FrameKitDialog(
        title = stringResource(R.string.framekit_document_text_title),
        icon = ImageVector.vectorResource(UiR.drawable.framekit_ic_document),
        onDismiss = onDismiss,
        actions = listOf(
            DialogAction(stringResource(R.string.framekit_document_text_copy), DialogActionStyle.PRIMARY) {
                clipboard.setText(AnnotatedString(text))
                // Android 13부터는 시스템이 복사를 알려 주므로 그 전 버전에서만 안내한다.
                if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) {
                    android.widget.Toast.makeText(context, copied, android.widget.Toast.LENGTH_SHORT).show()
                }
                onDismiss()
            },
            DialogAction(stringResource(R.string.framekit_action_close_dialog), DialogActionStyle.SECONDARY, onClick = onDismiss),
        ),
    ) {
        // 배경 상자는 그대로 두고 안쪽 글자만 스크롤·흐림 처리한다.
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(colors.background)) {
            SelectionContainer {
                Text(
                    text,
                    color = colors.foreground,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 300.dp)
                        .verticalFadingScroll()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                )
            }
        }
    }
}
