package com.naury.framekit.ui.image.editor

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.naury.framekit.ui.design.FrameKitTheme
import com.naury.framekit.ui.image.R

/**
 * 여러 장 편집의 쪽 목록. 썸네일을 눌러 사진을 바꾸고, 지금 사진을 앞뒤로 옮기거나 빼고, 사진을 더한다.
 *
 * @param enabled 도구가 열려 있거나 저장 중이면 `false`. 이때는 사진을 바꿀 수 없다.
 */
@Composable
internal fun PageStrip(
    pageIds: List<String>,
    selected: Int,
    thumbnails: Map<String, Bitmap>,
    canAdd: Boolean,
    enabled: Boolean,
    onSelect: (Int) -> Unit,
    onAdd: () -> Unit,
    onMove: (Int) -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = FrameKitTheme.colors
    val listState = rememberLazyListState()
    LaunchedEffect(selected) { if (selected in pageIds.indices) listState.animateScrollToItem(selected) }
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.framekit_page_position, selected + 1, pageIds.size),
                color = colors.foregroundMuted,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(start = 8.dp).weight(1f),
            )
            PageAction(Icons.AutoMirrored.Filled.ArrowBack, R.string.framekit_page_move_earlier, enabled && selected > 0) { onMove(-1) }
            PageAction(Icons.AutoMirrored.Filled.ArrowForward, R.string.framekit_page_move_later, enabled && selected < pageIds.lastIndex) { onMove(1) }
            PageAction(Icons.Filled.Delete, R.string.framekit_page_remove, enabled && pageIds.size > 1, onRemove)
        }
        LazyRow(
            state = listState,
            contentPadding = PaddingValues(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            itemsIndexed(pageIds, key = { _, id -> id }) { index, id ->
                val isSelected = index == selected
                val label = stringResource(R.string.framekit_page_label, index + 1)
                Box(
                    Modifier
                        .size(56.dp)
                        .clip(MaterialTheme.shapes.small)
                        .background(colors.raised)
                        .border(if (isSelected) 3.dp else 0.dp, if (isSelected) colors.accent else colors.raised, MaterialTheme.shapes.small)
                        .selectable(selected = isSelected, enabled = enabled, role = Role.Tab) { onSelect(index) }
                        .semantics { contentDescription = label },
                    contentAlignment = Alignment.Center,
                ) {
                    thumbnails[id]?.takeIf { !it.isRecycled }?.let { bitmap ->
                        Image(bitmap.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(56.dp))
                    }
                    if (isSelected) {
                        Text(
                            "${index + 1}",
                            color = colors.onAccent,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.align(Alignment.TopStart).background(colors.accent).padding(horizontal = 4.dp),
                        )
                    }
                }
            }
            if (canAdd) {
                item(key = "add") {
                    val label = stringResource(R.string.framekit_page_add)
                    Box(
                        Modifier
                            .size(56.dp)
                            .clip(MaterialTheme.shapes.small)
                            .background(colors.surface)
                            .clickable(enabled = enabled, role = Role.Button, onClick = onAdd)
                            .semantics { contentDescription = label },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null, tint = colors.foreground)
                    }
                }
            }
        }
    }
}

@Composable
private fun PageAction(icon: ImageVector, label: Int, enabled: Boolean, onClick: () -> Unit) {
    val colors = FrameKitTheme.colors
    IconButton(onClick = onClick, enabled = enabled) {
        Icon(icon, contentDescription = stringResource(label), tint = if (enabled) colors.foreground else colors.foregroundMuted.copy(alpha = 0.4f))
    }
}
