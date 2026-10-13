package com.naury.framekit.picker

import android.text.format.DateFormat
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.launch
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.naury.framekit.ui.component.FrameKitNavigationBar
import com.naury.framekit.ui.component.FrameKitSnackbarHost
import com.naury.framekit.ui.component.NavigationIcon
import com.naury.framekit.ui.design.FrameKitTheme
import com.naury.framekit.ui.design.frameKitGlass
import java.util.Calendar
import java.util.Locale
import com.naury.framekit.ui.R as UiR

/**
 * 앱 안 갤러리 화면.
 *
 * 사진을 누르면 고르고(하나만 고를 때는 바로 끝난다), 크게 보기 버튼이나 길게 누르기로 크게 본다. 크게 보는 화면에서도
 * 고르고 끝낼 수 있다.
 */
@Composable
internal fun PickerScreen(
    viewModel: PickerViewModel,
    loader: ThumbnailLoader,
    onClose: () -> Unit,
    onManageAccess: () -> Unit,
    onSystemPicker: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = FrameKitTheme.colors
    val request = viewModel.request
    val multiple = request.maxItems > 1
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val messageText = state.message?.let { message ->
        when (message) {
            is PickerMessage.LimitReached -> pluralStringResource(R.plurals.framekit_picker_limit, message.max, message.max)
            is PickerMessage.MinItems -> pluralStringResource(R.plurals.framekit_picker_min, message.min, message.min)
            is PickerMessage.Blocked -> when (message.block) {
                Block.TOO_LARGE -> stringResource(R.string.framekit_picker_too_large, Formatter.formatShortFileSize(context, request.maxFileSizeBytes ?: 0))
                Block.TOO_LONG -> stringResource(R.string.framekit_picker_too_long, formatDuration(request.maxVideoDurationMs ?: 0))
            }
        }
    }
    LaunchedEffect(state.message) {
        if (messageText != null) {
            snackbar.showSnackbar(messageText)
            viewModel.dismissMessage()
        }
    }
    BackHandler {
        when {
            state.preview != null -> viewModel.closePreview()
            state.showAlbums -> viewModel.toggleAlbums(false)
            else -> onClose()
        }
    }
    val trayVisible = multiple && state.selection.isNotEmpty()
    val doneEnabled = state.selection.size >= request.minItems
    Box(Modifier.fillMaxSize().background(colors.background)) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            FrameKitNavigationBar(
                title = null,
                navigationIcon = NavigationIcon.CLOSE,
                onNavigate = onClose,
                actionLabel = if (multiple) doneLabel(state.selection.size) else null,
                actionEnabled = doneEnabled,
                onAction = viewModel::confirm,
                titleContent = {
                    AlbumButton(
                        title = state.album?.let { albumName(it) } ?: stringResource(R.string.framekit_picker_recent),
                        expanded = state.showAlbums,
                        onClick = { viewModel.toggleAlbums(!state.showAlbums) },
                    )
                },
            )
            Box(Modifier.weight(1f)) {
                when {
                    state.items.isEmpty() && state.loading -> CircularProgressIndicator(color = colors.accent, strokeWidth = 2.5.dp, modifier = Modifier.size(32.dp).align(Alignment.Center))
                    state.items.isEmpty() -> EmptyState(onSystemPicker, Modifier.align(Alignment.Center))
                    else -> MediaGrid(
                        state = state,
                        multiple = multiple,
                        viewModel = viewModel,
                        loader = loader,
                        header = if (state.access == Access.PARTIAL) ({ PartialBanner(onManageAccess) }) else null,
                        bottomPadding = if (trayVisible) TRAY_HEIGHT + 24.dp else 12.dp,
                    )
                }
                SelectionTray(trayVisible, state, loader, onOpen = viewModel::openPreviewOf, onRemove = viewModel::toggle, modifier = Modifier.align(Alignment.BottomCenter))
                AlbumSheet(state, loader, onDismiss = { viewModel.toggleAlbums(false) }, onSelect = viewModel::selectAlbum)
            }
        }
        state.preview?.let { index -> PreviewOverlay(state, index, multiple, doneEnabled, loader, viewModel) }
        FrameKitSnackbarHost(
            snackbar,
            Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.navigationBars).padding(bottom = if (trayVisible || state.preview != null) TRAY_HEIGHT + 28.dp else 16.dp),
        )
    }
}

@Composable
private fun doneLabel(count: Int): String =
    if (count > 0) stringResource(R.string.framekit_picker_done_count, count) else stringResource(UiR.string.framekit_action_done)

// ---- 상단 ----

@Composable
private fun AlbumButton(title: String, expanded: Boolean, onClick: () -> Unit) {
    val colors = FrameKitTheme.colors
    val arrow by animateFloatAsState(if (expanded) 180f else 0f, tween(220), label = "album-arrow")
    Row(
        Modifier.height(42.dp)
            .frameKitGlass(CircleShape)
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 10.dp)
            .semantics { heading() },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, color = colors.foreground, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 180.dp))
        Icon(
            Icons.Default.KeyboardArrowDown,
            contentDescription = stringResource(R.string.framekit_picker_choose_album),
            tint = colors.foregroundMuted,
            modifier = Modifier.padding(start = 2.dp).size(20.dp).rotate(arrow),
        )
    }
}

@Composable
private fun PartialBanner(onManage: () -> Unit) {
    val colors = FrameKitTheme.colors
    Row(
        Modifier.fillMaxWidth().padding(bottom = 6.dp).clip(RoundedCornerShape(16.dp)).background(colors.surface).padding(start = 16.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.framekit_picker_partial), color = colors.foregroundMuted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f).padding(vertical = 12.dp))
        TextButton(onClick = onManage) { Text(stringResource(R.string.framekit_picker_partial_manage), color = colors.accent, fontWeight = FontWeight.SemiBold) }
    }
}

@Composable
private fun EmptyState(onSystemPicker: () -> Unit, modifier: Modifier) {
    val colors = FrameKitTheme.colors
    Column(modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(R.string.framekit_picker_empty), color = colors.foregroundMuted, style = MaterialTheme.typography.bodyMedium)
        Button(
            onClick = onSystemPicker,
            modifier = Modifier.padding(top = 16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = colors.surface, contentColor = colors.foreground),
        ) {
            Text(stringResource(R.string.framekit_picker_open_system))
        }
    }
}

// ---- 칸 ----

/** 날짜 구분 머리말 또는 항목 하나. [index]는 지금 앨범 안의 위치다. */
private sealed interface GridEntry {
    data class Header(val label: String) : GridEntry
    data class Media(val item: MediaItem, val index: Int) : GridEntry
}

@Composable
private fun MediaGrid(
    state: PickerUiState,
    multiple: Boolean,
    viewModel: PickerViewModel,
    loader: ThumbnailLoader,
    header: (@Composable () -> Unit)?,
    bottomPadding: Dp,
) {
    val items = state.albumItems
    val labels = DateLabels(stringResource(R.string.framekit_picker_today), stringResource(R.string.framekit_picker_yesterday), currentLocale())
    val entries = remember(items, labels) { groupByDate(items, labels) }
    val sizePx = with(LocalDensity.current) { GRID_CELL.roundToPx() }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(GRID_CELL),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = GRID_PADDING, end = GRID_PADDING, top = 4.dp, bottom = bottomPadding),
        horizontalArrangement = Arrangement.spacedBy(GRID_GAP),
        verticalArrangement = Arrangement.spacedBy(GRID_GAP),
    ) {
        if (header != null) item(key = "framekit:banner", span = { GridItemSpan(maxLineSpan) }) { header() }
        items(
            entries,
            key = { entry -> if (entry is GridEntry.Media) "${entry.item.isVideo}:${entry.item.id}" else "h:${(entry as GridEntry.Header).label}" },
            span = { entry -> if (entry is GridEntry.Header) GridItemSpan(maxLineSpan) else GridItemSpan(1) },
            contentType = { it::class },
        ) { entry ->
            when (entry) {
                is GridEntry.Header -> DateHeader(entry.label)
                is GridEntry.Media -> MediaCell(
                    item = entry.item,
                    order = state.orderOf(entry.item.id),
                    multiple = multiple,
                    blocked = viewModel.request.blockOf(entry.item) != null,
                    loader = loader,
                    sizePx = sizePx,
                    onOpen = { viewModel.openPreview(entry.index) },
                    onToggle = { viewModel.toggle(entry.item) },
                )
            }
        }
    }
}

@Composable
private fun DateHeader(label: String) {
    Text(
        label,
        color = FrameKitTheme.colors.foreground,
        fontSize = 15.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 4.dp, top = 14.dp, bottom = 4.dp).semantics { heading() },
    )
}

/**
 * 칸 하나. 누르면 [onToggle], 크게 보기 버튼·길게 누르기는 [onOpen].
 */
@Composable
private fun MediaCell(
    item: MediaItem,
    order: Int,
    multiple: Boolean,
    blocked: Boolean,
    loader: ThumbnailLoader,
    sizePx: Int,
    onOpen: () -> Unit,
    onToggle: () -> Unit,
) {
    val colors = FrameKitTheme.colors
    val selected = order > 0
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = when {
            selected -> 0.88f
            pressed -> 0.95f
            else -> 1f
        },
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "cell-scale",
    )
    val description = buildString {
        if (item.isVideo) append(stringResource(R.string.framekit_picker_video_cd, formatDuration(item.durationMs)))
        if (selected) {
            if (isNotEmpty()) append(", ")
            append(stringResource(R.string.framekit_picker_selected_cd, order))
        }
    }
    val previewLabel = stringResource(R.string.framekit_picker_preview)
    val selectLabel = stringResource(if (selected) R.string.framekit_picker_remove_cd else R.string.framekit_picker_select_cd)
    Box(
        Modifier.aspectRatio(1f)
            .clip(RoundedCornerShape(CELL_RADIUS))
            .background(if (selected) colors.accent.copy(alpha = 0.16f) else Color.Transparent)
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onClick = onToggle,
                onClickLabel = selectLabel,
                onLongClick = onOpen,
                onLongClickLabel = previewLabel,
            )
            .semantics { if (description.isNotEmpty()) contentDescription = description },
    ) {
        Box(
            Modifier.fillMaxSize()
                .graphicsLayer { scaleX = scale; scaleY = scale }
                .clip(RoundedCornerShape(if (selected) CELL_RADIUS - 2.dp else CELL_RADIUS))
                .background(colors.surface),
        ) {
            Thumbnail(item, loader, sizePx, Modifier.fillMaxSize())
            if (item.isVideo) {
                Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(36.dp).background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.45f)))))
                VideoBadge(item.durationMs, Modifier.align(Alignment.BottomStart).padding(7.dp))
            }
            if (blocked) Box(Modifier.fillMaxSize().background(colors.background.copy(alpha = 0.7f)))
        }
        if (selected) Box(Modifier.fillMaxSize().border(2.5.dp, colors.accent, RoundedCornerShape(CELL_RADIUS)))
        if (multiple && !blocked) SelectionMark(order, Modifier.align(Alignment.TopEnd).padding(7.dp))
        // 작은 버튼도 누르기 쉽도록 터치 영역은 44dp로 둔다.
        Box(
            Modifier.align(Alignment.BottomEnd).size(CORNER_TOUCH)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClick = onOpen)
                .semantics { contentDescription = previewLabel },
            contentAlignment = Alignment.BottomEnd,
        ) {
            ExpandMark(Modifier.padding(6.dp))
        }
    }
}

@Composable
private fun VideoBadge(durationMs: Long, modifier: Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
        Text(formatDuration(durationMs), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 1.dp))
    }
}

/** 고른 순서 번호. 고르지 않았으면 0. */
@Composable
private fun SelectionMark(order: Int, modifier: Modifier, size: Dp = 22.dp) {
    val colors = FrameKitTheme.colors
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Box(Modifier.fillMaxSize().clip(CircleShape).background(Color.Black.copy(alpha = 0.18f)).border(1.5.dp, Color.White.copy(alpha = 0.9f), CircleShape))
        AnimatedVisibility(order > 0, enter = scaleIn(spring(dampingRatio = Spring.DampingRatioMediumBouncy)) + fadeIn(), exit = scaleOut() + fadeOut()) {
            Box(Modifier.size(size).clip(CircleShape).background(colors.accent), contentAlignment = Alignment.Center) {
                Text(if (order > 0) "$order" else "", color = colors.onAccent, fontSize = (size.value * 0.52f).sp, fontWeight = FontWeight.Bold, style = CenteredDigits)
            }
        }
    }
}

/** 크게 보기 버튼. */
@Composable
private fun ExpandMark(modifier: Modifier) {
    Box(modifier.size(24.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.4f)), contentAlignment = Alignment.Center) {
        androidx.compose.foundation.Canvas(Modifier.size(12.dp)) {
            val w = size.width
            val arm = w * 0.38f
            val stroke = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.6.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round)
            fun corner(x: Float, y: Float, dx: Float, dy: Float) {
                val path = androidx.compose.ui.graphics.Path().apply {
                    moveTo(x + dx * arm, y)
                    lineTo(x, y)
                    lineTo(x, y + dy * arm)
                }
                drawPath(path, Color.White, style = stroke)
            }
            corner(0f, 0f, 1f, 1f)
            corner(w, 0f, -1f, 1f)
            corner(0f, w, 1f, -1f)
            corner(w, w, -1f, -1f)
        }
    }
}

// ---- 앨범 ----

@Composable
private fun BoxScope.AlbumSheet(state: PickerUiState, loader: ThumbnailLoader, onDismiss: () -> Unit, onSelect: (String) -> Unit) {
    val colors = FrameKitTheme.colors
    AnimatedVisibility(state.showAlbums, enter = fadeIn(tween(180)), exit = fadeOut(tween(180)), modifier = Modifier.matchParentSize()) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss))
    }
    AnimatedVisibility(
        state.showAlbums,
        enter = fadeIn(tween(180)) + slideInVertically(tween(220)) { -it / 6 },
        exit = fadeOut(tween(150)) + slideOutVertically(tween(150)) { -it / 6 },
        modifier = Modifier.align(Alignment.TopCenter),
    ) {
        val sizePx = with(LocalDensity.current) { ALBUM_THUMB.roundToPx() }
        LazyColumn(
            Modifier.widthIn(max = ALBUM_SHEET_MAX_WIDTH).fillMaxWidth().padding(horizontal = 12.dp).heightIn(max = 520.dp).clip(RoundedCornerShape(24.dp)).background(colors.surface),
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            items(state.albums, key = { it.id }) { album ->
                val current = album.id == state.albumId
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp).clip(RoundedCornerShape(16.dp))
                        .background(if (current) colors.raised else Color.Transparent)
                        .clickable { onSelect(album.id) }
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(ALBUM_THUMB).clip(RoundedCornerShape(12.dp)).background(colors.raised)) {
                        album.cover?.let { Thumbnail(it, loader, sizePx, Modifier.fillMaxSize()) }
                    }
                    Column(Modifier.padding(start = 14.dp).weight(1f)) {
                        Text(albumName(album), color = colors.foreground, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${album.count}", color = colors.foregroundMuted, style = MaterialTheme.typography.bodySmall)
                    }
                    if (current) Icon(Icons.Default.Check, contentDescription = null, tint = colors.accent, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

@Composable
private fun albumName(album: Album): String = when (album.kind) {
    AlbumKind.ALL -> stringResource(R.string.framekit_picker_recent)
    AlbumKind.VIDEOS -> stringResource(R.string.framekit_picker_videos)
    AlbumKind.FOLDER -> album.name.orEmpty()
}

// ---- 고른 것 ----

/** 고른 항목 목록. 누르면 크게 보고, 빼기 버튼으로 뺀다. */
@Composable
private fun SelectionTray(visible: Boolean, state: PickerUiState, loader: ThumbnailLoader, onOpen: (MediaItem) -> Unit, onRemove: (MediaItem) -> Unit, modifier: Modifier) {
    val colors = FrameKitTheme.colors
    val byId = remember(state.items) { state.items.associateBy { it.id } }
    val selected = state.selection.mapNotNull { byId[it] }
    val sizePx = with(LocalDensity.current) { TRAY_THUMB.roundToPx() }
    val listState = rememberLazyListState()
    // 새로 고르면 줄 끝으로 옮겨 방금 고른 것이 보이게 한다.
    LaunchedEffect(selected.size) { if (selected.isNotEmpty()) listState.animateScrollToItem(selected.size - 1) }
    AnimatedVisibility(
        visible,
        modifier = modifier,
        enter = slideInVertically(spring(stiffness = Spring.StiffnessMediumLow)) { it } + fadeIn(),
        exit = slideOutVertically(tween(180)) { it } + fadeOut(tween(180)),
    ) {
        LazyRow(
            Modifier.widthIn(max = TRAY_MAX_WIDTH).fillMaxWidth().padding(12.dp).height(TRAY_HEIGHT).clip(RoundedCornerShape(22.dp)).background(colors.surface),
            state = listState,
            contentPadding = PaddingValues(horizontal = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items(selected, key = { "${it.isVideo}:${it.id}" }) { item ->
                Box(Modifier.animateItem().size(TRAY_THUMB).clip(RoundedCornerShape(12.dp)).background(colors.raised).clickable { onOpen(item) }) {
                    Thumbnail(item, loader, sizePx, Modifier.fillMaxSize())
                    Box(
                        Modifier.align(Alignment.TopEnd).size(28.dp).clickable { onRemove(item) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(Modifier.size(18.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)), contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.framekit_picker_remove_cd), tint = Color.White, modifier = Modifier.size(12.dp))
                        }
                    }
                }
            }
        }
    }
}

// ---- 크게 보기 ----

@Composable
private fun PreviewOverlay(state: PickerUiState, start: Int, multiple: Boolean, doneEnabled: Boolean, loader: ThumbnailLoader, viewModel: PickerViewModel) {
    val items = state.albumItems
    if (items.isEmpty()) return
    val colors = FrameKitTheme.colors
    val pager = rememberPagerState(initialPage = start.coerceIn(items.indices)) { items.size }
    val scope = rememberCoroutineScope()
    var chrome by remember { mutableStateOf(true) }
    var zoomed by remember { mutableStateOf(false) }
    // 확대해서 볼 만큼 크게 읽되, 메모리를 지나치게 쓰지 않도록 상한을 둔다.
    val window = LocalWindowInfo.current.containerSize
    val fullPx = (maxOf(window.width, window.height) * 3 / 2).coerceIn(1, PREVIEW_MAX_PX)
    LaunchedEffect(pager.currentPage) { zoomed = false }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(
            pager,
            Modifier.fillMaxSize(),
            userScrollEnabled = !zoomed,
            beyondViewportPageCount = 1,
            key = { "${items[it].isVideo}:${items[it].id}" },
        ) { page ->
            val item = items[page]
            ZoomableMedia(
                item = item,
                loader = loader,
                fullPx = fullPx,
                active = page == pager.currentPage,
                onZoomChange = { if (page == pager.currentPage) zoomed = it },
                onTap = { chrome = !chrome },
            )
        }
        val current = items.getOrNull(pager.currentPage)
        val selectable = current != null && viewModel.request.blockOf(current) == null
        val order = current?.let { state.orderOf(it.id) } ?: 0
        AnimatedVisibility(chrome, enter = fadeIn(tween(160)), exit = fadeOut(tween(160)), modifier = Modifier.align(Alignment.TopCenter)) {
            Box(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.6f), Color.Transparent)))) {
                Row(
                    Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing).padding(start = 6.dp, end = 12.dp, top = 4.dp, bottom = 28.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.size(42.dp).frameKitGlass(CircleShape).clickable(role = Role.Button, onClick = viewModel::closePreview),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = stringResource(UiR.string.framekit_action_back), tint = Color.White, modifier = Modifier.size(28.dp))
                    }
                    Spacer(Modifier.weight(1f))
                    Text(
                        "${pager.currentPage + 1} / ${items.size}",
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.frameKitGlass(CircleShape).padding(horizontal = 16.dp, vertical = 9.dp),
                    )
                    Spacer(Modifier.weight(1f))
                    if (multiple && selectable && current != null) {
                        val label = stringResource(if (order > 0) R.string.framekit_picker_remove_cd else R.string.framekit_picker_select_cd)
                        Box(
                            Modifier.size(42.dp).frameKitGlass(CircleShape).clickable(role = Role.Button) { viewModel.toggle(current) }.semantics { contentDescription = label },
                            contentAlignment = Alignment.Center,
                        ) {
                            SelectionMark(order, Modifier, size = 26.dp)
                        }
                    } else {
                        Spacer(Modifier.size(44.dp))
                    }
                }
            }
        }
        AnimatedVisibility(chrome, enter = fadeIn(tween(160)), exit = fadeOut(tween(160)), modifier = Modifier.align(Alignment.BottomCenter)) {
            Column(
                Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f))))
                    .windowInsetsPadding(WindowInsets.safeDrawing).padding(top = 32.dp, bottom = 10.dp),
            ) {
                Filmstrip(
                    items = items,
                    current = pager.currentPage,
                    state = state,
                    loader = loader,
                    onSelect = { index -> scope.launch { pager.animateScrollToPage(index) } },
                )
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (current?.isVideo == true) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                            Text(formatDuration(current.durationMs), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    val enabled = if (multiple) doneEnabled else selectable
                    Box(
                        Modifier.height(46.dp).frameKitGlass(CircleShape, tint = if (enabled) colors.accent else null)
                            .clickable(enabled = enabled, role = Role.Button) {
                                if (!multiple && current != null && order == 0) viewModel.toggle(current)
                                viewModel.closePreview()
                                viewModel.confirm()
                            }
                            .padding(horizontal = 22.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            if (multiple) doneLabel(state.selection.size) else stringResource(R.string.framekit_picker_select_cd),
                            color = if (enabled) colors.onAccent else Color.White.copy(alpha = 0.6f),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 크게 보는 사진 한 장. 두 손가락으로 확대·축소하고, 두 번 탭하면 확대하거나 원래대로 돌아간다. 확대한 동안에는 한 손가락으로
 * 옮기고, 원래 크기에서는 한 손가락 끌기를 옆 사진 넘기기에 넘긴다. 다른 쪽으로 넘어가면 원래 크기로 돌아간다.
 */
@Composable
private fun ZoomableMedia(item: MediaItem, loader: ThumbnailLoader, fullPx: Int, active: Boolean, onZoomChange: (Boolean) -> Unit, onTap: () -> Unit) {
    var scale by remember(item.id) { mutableFloatStateOf(1f) }
    var offset by remember(item.id) { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(active) {
        if (!active) {
            scale = 1f
            offset = Offset.Zero
        }
    }
    fun clamp(value: Offset, zoom: Float): Offset {
        val maxX = size.width * (zoom - 1f) / 2f
        val maxY = size.height * (zoom - 1f) / 2f
        return Offset(value.x.coerceIn(-maxX, maxX), value.y.coerceIn(-maxY, maxY))
    }
    fun animateTo(targetScale: Float, targetOffset: Offset) {
        scope.launch {
            val startScale = scale
            val startOffset = offset
            androidx.compose.animation.core.animate(0f, 1f, animationSpec = tween(220)) { t, _ ->
                scale = startScale + (targetScale - startScale) * t
                offset = startOffset + (targetOffset - startOffset) * t
            }
            onZoomChange(targetScale > 1.01f)
        }
    }
    Box(
        Modifier.fillMaxSize()
            .onSizeChanged { size = it }
            .pointerInput(item.id) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = { tap ->
                        if (scale > 1.01f) {
                            animateTo(1f, Offset.Zero)
                        } else {
                            // 누른 곳이 확대 뒤에도 같은 자리에 오도록 옮긴다.
                            val center = Offset(size.width / 2f, size.height / 2f)
                            animateTo(DOUBLE_TAP_ZOOM, clamp((center - tap) * (DOUBLE_TAP_ZOOM - 1f), DOUBLE_TAP_ZOOM))
                        }
                    },
                )
            }
            .pointerInput(item.id) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val pointers = event.changes.count { it.pressed }
                        val zoomChange = event.calculateZoom()
                        val pan = event.calculatePan()
                        // 원래 크기에서 한 손가락으로 끄는 것은 옆 사진 넘기기에 맡긴다.
                        if (pointers > 1 || scale > 1.01f) {
                            val nextScale = (scale * zoomChange).coerceIn(1f, MAX_ZOOM)
                            offset = clamp(offset + pan, nextScale)
                            scale = nextScale
                            onZoomChange(scale > 1.01f)
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                    if (scale <= 1.01f && scale != 1f) {
                        scale = 1f
                        offset = Offset.Zero
                        onZoomChange(false)
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        val placeholder = remember(item.id) { loader.anyCached(item) }
        Box(Modifier.fillMaxSize().graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y }) {
            // 큰 이미지를 읽는 동안 칸에 쓰던 작은 이미지를 먼저 보여 준다.
            if (placeholder != null) Image(placeholder.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            Thumbnail(item, loader, fullPx, Modifier.fillMaxSize(), ContentScale.Fit)
        }
        if (item.isVideo) {
            Box(Modifier.size(64.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(36.dp))
            }
        }
    }
}

/** 크게 보기 아래의 썸네일 줄. 지금 사진을 가운데로 옮기고, 누르면 그 사진으로 넘어간다. 고른 것은 순서 번호가 보인다. */
@Composable
private fun Filmstrip(items: List<MediaItem>, current: Int, state: PickerUiState, loader: ThumbnailLoader, onSelect: (Int) -> Unit) {
    val colors = FrameKitTheme.colors
    val listState = rememberLazyListState()
    val density = LocalDensity.current
    val sizePx = with(density) { FILM_CURRENT.roundToPx() }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val sidePadding = (maxWidth - FILM_CURRENT) / 2
        LaunchedEffect(current) {
            listState.animateScrollToItem(current)
        }
        LazyRow(
            state = listState,
            contentPadding = PaddingValues(horizontal = sidePadding),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().height(FILM_CURRENT + 8.dp),
        ) {
            items(items.size, key = { "${items[it].isVideo}:${items[it].id}" }) { index ->
                val item = items[index]
                val isCurrent = index == current
                val side by androidx.compose.animation.core.animateDpAsState(if (isCurrent) FILM_CURRENT else FILM_OTHER, tween(180), label = "film-size")
                val order = state.orderOf(item.id)
                Box(
                    Modifier.size(side)
                        .clip(RoundedCornerShape(10.dp))
                        .background(colors.raised)
                        .then(if (isCurrent) Modifier.border(2.dp, Color.White, RoundedCornerShape(10.dp)) else Modifier)
                        .clickable { onSelect(index) },
                ) {
                    Thumbnail(item, loader, sizePx, Modifier.fillMaxSize())
                    if (!isCurrent) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.25f)))
                    if (order > 0) {
                        Box(
                            Modifier.align(Alignment.TopEnd).padding(3.dp).size(16.dp).clip(CircleShape).background(colors.accent),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("$order", color = colors.onAccent, fontSize = 9.sp, fontWeight = FontWeight.Bold, style = CenteredDigits)
                        }
                    }
                }
            }
        }
    }
}

// ---- 공용 ----

/** [item]의 축소 이미지를 읽어 보여 준다. */
@Composable
private fun Thumbnail(item: MediaItem, loader: ThumbnailLoader, sizePx: Int, modifier: Modifier, contentScale: ContentScale = ContentScale.Crop) {
    val cached = remember(item.id, item.isVideo, sizePx) { loader.cached(item, sizePx) }
    val bitmap by produceState(cached, item.id, item.isVideo, sizePx) {
        if (value == null) value = loader.load(item, sizePx)
    }
    val alpha by animateFloatAsState(if (bitmap != null) 1f else 0f, if (cached != null) tween(0) else tween(160), label = "thumb-alpha")
    bitmap?.let { Image(it.asImageBitmap(), contentDescription = null, contentScale = contentScale, modifier = modifier.graphicsLayer { this.alpha = alpha }) }
}

@Composable
private fun currentLocale(): Locale = LocalConfiguration.current.locales[0]

/** 날짜 머리말 문구. 오늘·어제는 문구로, 그 밖에는 [locale]에 맞는 "연 월" 형식으로 쓴다. */
private data class DateLabels(val today: String, val yesterday: String, val locale: Locale)

// 최근 것부터 정렬된 [items]를 날짜(오늘·어제·월)로 묶는다. 머리말은 묶음이 바뀔 때만 넣는다.
private fun groupByDate(items: List<MediaItem>, labels: DateLabels): List<GridEntry> {
    val calendar = Calendar.getInstance()
    fun dayKey(millis: Long): Int {
        calendar.timeInMillis = millis
        return calendar.get(Calendar.YEAR) * 1000 + calendar.get(Calendar.DAY_OF_YEAR)
    }
    val now = System.currentTimeMillis()
    val today = dayKey(now)
    val yesterday = dayKey(now - DAY_MS)
    val monthFormat = java.text.SimpleDateFormat(DateFormat.getBestDateTimePattern(labels.locale, "yMMMM"), labels.locale)
    val entries = ArrayList<GridEntry>(items.size + 16)
    var lastLabel: String? = null
    items.forEachIndexed { index, item ->
        val millis = item.dateAdded * 1000
        val label = when {
            item.dateAdded <= 0 -> null
            dayKey(millis) == today -> labels.today
            dayKey(millis) == yesterday -> labels.yesterday
            else -> monthFormat.format(java.util.Date(millis))
        }
        if (label != null && label != lastLabel) {
            entries += GridEntry.Header(label)
            lastLabel = label
        }
        entries += GridEntry.Media(item, index)
    }
    return entries
}

/** `m:ss` 또는 `h:mm:ss`. */
internal fun formatDuration(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = total % 3600 / 60
    val s = total % 60
    return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s) else String.format(Locale.ROOT, "%d:%02d", m, s)
}

private val GRID_CELL = 104.dp
private val GRID_GAP = 4.dp
private val GRID_PADDING = 10.dp
private val CELL_RADIUS = 14.dp
private val CORNER_TOUCH = 44.dp
private val ALBUM_THUMB = 52.dp
private val TRAY_THUMB = 52.dp
private val TRAY_HEIGHT = 72.dp

// 넓은 화면에서 목록이 화면 끝까지 늘어나지 않도록 폭을 제한한다.
private val ALBUM_SHEET_MAX_WIDTH = 440.dp
private val TRAY_MAX_WIDTH = 600.dp
private const val DAY_MS = 24L * 60 * 60 * 1000

// 크게 볼 때 읽는 이미지 긴 변의 상한(px).
private const val PREVIEW_MAX_PX = 2560
private const val DOUBLE_TAP_ZOOM = 2.5f
private const val MAX_ZOOM = 5f
private val FILM_CURRENT = 50.dp
private val FILM_OTHER = 40.dp

// 작은 원 안의 숫자가 글꼴 위아래 여백 때문에 가운데에서 벗어나지 않도록 여백을 뺀다.
private val CenteredDigits = TextStyle(
    platformStyle = PlatformTextStyle(includeFontPadding = false),
    lineHeightStyle = LineHeightStyle(alignment = LineHeightStyle.Alignment.Center, trim = LineHeightStyle.Trim.Both),
)
