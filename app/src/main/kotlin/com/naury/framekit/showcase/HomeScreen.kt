package com.naury.framekit.showcase

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.naury.framekit.ui.config.ThemeMode
import com.naury.kinetica.ui.KAlert
import com.naury.kinetica.ui.KListItem
import com.naury.kinetica.ui.KListLeading
import com.naury.kinetica.ui.KListSection
import com.naury.kinetica.ui.KListTrailing
import com.naury.kinetica.ui.KSegmentedControl
import com.naury.kinetica.ui.KStatus
import com.naury.kinetica.ui.jellyPress
import com.naury.kinetica.ui.kClickable
import com.naury.framekit.ui.R as UiR

/**
 * 샘플 앱 첫 화면. 자주 쓰는 편집기는 위쪽 타일로 바로 열고, 나머지 예제는 기능별로 묶어 보여 준다.
 * 넓은 화면에서는 타일을 한 줄에 넷씩 두고 본문 폭을 제한한다.
 */
@Composable
fun HomeScreen(
    failure: String?,
    onDismissFailure: () -> Unit,
    editorTheme: ThemeMode,
    onEditorTheme: (ThemeMode) -> Unit,
    onLaunch: (ShowcaseExample) -> Unit,
    onPlayground: () -> Unit,
) {
    val groups = ShowcaseExample.entries.filter { it.group != null }.groupBy { it.group!! }.toSortedMap()
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        item {
            Column(Modifier.widthIn(max = CONTENT_MAX_WIDTH).fillMaxWidth()) {
                Header(editorTheme, onEditorTheme)
                Spacer(Modifier.height(22.dp))
                QuickStart(onLaunch)
                if (failure != null) {
                    Spacer(Modifier.height(10.dp))
                    KAlert(failure, status = KStatus.Error, onDismiss = onDismissFailure)
                }
                Spacer(Modifier.height(10.dp))
                PlaygroundCard(onPlayground)
            }
        }
        groups.forEach { (group, examples) ->
            item(key = group.name) {
                KListSection(Modifier.widthIn(max = CONTENT_MAX_WIDTH).fillMaxWidth().padding(top = 26.dp), header = stringResource(group.title)) {
                    examples.forEach { example ->
                        item(example.name) {
                            ExampleRow(example.icon, group.accent, stringResource(example.title), stringResource(example.description)) { onLaunch(example) }
                        }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun Header(editorTheme: ThemeMode, onEditorTheme: (ThemeMode) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(Brush.linearGradient(listOf(Color(0xFF7B6CFF), Color(0xFF3A2FB8)))),
        ) {
            Image(painterResource(R.drawable.ic_launcher_foreground), contentDescription = null, modifier = Modifier.fillMaxSize())
        }
        Spacer(Modifier.weight(1f))
        // 앱 화면과 모든 예제의 편집기를 고른 테마로 바꾼다.
        ThemeToggle(editorTheme, onEditorTheme)
    }
    Spacer(Modifier.height(20.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("FrameKit", color = colors.onBackground, fontSize = 34.sp, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
        Spacer(Modifier.width(10.dp))
        Text(
            BuildConfig.VERSION_NAME,
            color = colors.primary,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .clip(CircleShape)
                .background(colors.primary.copy(alpha = 0.14f))
                .padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
    Spacer(Modifier.height(4.dp))
    Text(stringResource(R.string.home_subtitle), color = colors.onSurfaceVariant, fontSize = 15.sp, lineHeight = 21.sp)
}

@Composable
private fun ThemeToggle(selected: ThemeMode, onSelect: (ThemeMode) -> Unit) {
    val modes = listOf(ThemeMode.LIGHT, ThemeMode.DARK)
    KSegmentedControl(
        options = listOf(stringResource(R.string.home_theme_light), stringResource(R.string.home_theme_dark)),
        selectedIndex = modes.indexOf(selected).coerceAtLeast(0),
        onSelect = { onSelect(modes[it]) },
        modifier = Modifier.width(160.dp),
    )
}

private class QuickTile(
    val example: ShowcaseExample,
    @StringRes val title: Int,
    @StringRes val caption: Int,
    @DrawableRes val icon: Int,
    val accent: Color,
)

@Composable
private fun QuickStart(onLaunch: (ShowcaseExample) -> Unit) {
    val tiles = listOf(
        QuickTile(ShowcaseExample.DEFAULT, R.string.home_edit_photo, R.string.home_quick_photo_caption, UiR.drawable.framekit_ic_adjust, ExampleGroup.PHOTO.accent),
        QuickTile(ShowcaseExample.VIDEO, R.string.home_edit_video, R.string.home_quick_video_caption, R.drawable.ic_videocam, ExampleGroup.VIDEO.accent),
        QuickTile(ShowcaseExample.DOCUMENT_SCAN, R.string.home_quick_scan, R.string.home_quick_scan_caption, UiR.drawable.framekit_ic_document, ExampleGroup.DOCUMENT.accent),
        QuickTile(ShowcaseExample.AUDIO_RECORD, R.string.home_quick_record, R.string.home_quick_record_caption, UiR.drawable.framekit_ic_mic, ExampleGroup.AUDIO.accent),
    )
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val perRow = if (maxWidth >= 560.dp) 4 else 2
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            tiles.chunked(perRow).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { tile -> QuickTileCard(tile, Modifier.weight(1f)) { onLaunch(tile.example) } }
                }
            }
        }
    }
}

@Composable
private fun QuickTileCard(tile: QuickTile, modifier: Modifier, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier
            .jellyPress()
            .height(116.dp)
            .clip(shape)
            .background(colors.surface)
            .kClickable(shape, onClick = onClick)
            .padding(14.dp),
    ) {
        IconBadge(tile.icon, tile.accent, size = 36.dp)
        Spacer(Modifier.weight(1f))
        Text(stringResource(tile.title), color = colors.onSurface, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(stringResource(tile.caption), color = colors.onSurfaceVariant, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun IconBadge(@DrawableRes icon: Int, color: Color, size: Dp) {
    Box(Modifier.size(size).clip(RoundedCornerShape(size * 0.26f)).background(color), contentAlignment = Alignment.Center) {
        Icon(painterResource(icon), contentDescription = null, tint = Color.White, modifier = Modifier.size(size * 0.6f))
    }
}

@Composable
private fun PlaygroundCard(onClick: () -> Unit) {
    KListSection {
        item {
            ExampleRow(
                icon = UiR.drawable.framekit_ic_filter,
                accent = MaterialTheme.colorScheme.primary,
                title = stringResource(R.string.home_playground),
                description = stringResource(R.string.home_playground_description),
                onClick = onClick,
            )
        }
    }
}

@Composable
private fun ExampleRow(@DrawableRes icon: Int, accent: Color, title: String, description: String, onClick: () -> Unit) {
    KListItem(
        headline = title,
        supporting = description,
        supportingMaxLines = 3,
        leading = KListLeading.PainterIcon(painterResource(icon), container = accent),
        trailing = KListTrailing.Disclosure(),
        onClick = onClick,
    )
}

private val CONTENT_MAX_WIDTH = 720.dp
