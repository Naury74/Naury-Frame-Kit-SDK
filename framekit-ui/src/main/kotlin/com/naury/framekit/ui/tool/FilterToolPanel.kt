package com.naury.framekit.ui.tool

import androidx.compose.foundation.lazy.rememberLazyListState
import com.naury.framekit.ui.component.horizontalFadingEdges
import com.naury.framekit.ui.catalog.LocalCatalogUi
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.runtime.getValue
import androidx.compose.material3.Icon
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.height
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.naury.framekit.core.effect.AdjustmentKind
import com.naury.framekit.core.effect.FilterCatalog
import com.naury.framekit.core.effect.FilterSelection
import com.naury.framekit.ui.component.DialSlider
import com.naury.framekit.ui.design.FrameKitTheme
import com.naury.framekit.ui.R

/**
 * 선택한 프리셋의 강도 다이얼과 프리셋 썸네일 목록. 원본을 고르면 다이얼 자리에 안내 문구를 보여 줘
 * 패널 높이가 바뀌지 않게 한다.
 */
@Composable
public fun FilterToolPanel(
    selection: FilterSelection,
    thumbnails: Map<String, Bitmap>,
    onSelect: (String) -> Unit,
    onIntensity: (Float) -> Unit,
    onIntensityFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = FrameKitTheme.colors
    val selectedId = if (selection.isIdentity) FilterCatalog.ORIGINAL_ID else selection.presetId
    val catalog = LocalCatalogUi.current
    Column(modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp)) {
        Box(Modifier.fillMaxWidth().height(DIAL_AREA_HEIGHT), contentAlignment = Alignment.Center) {
            if (selectedId != FilterCatalog.ORIGINAL_ID) {
                DialSlider(
                    label = stringResource(R.string.framekit_filter_intensity),
                    value = (selection.intensity * AdjustmentKind.DISPLAY_RANGE).toFloat(),
                    valueRange = 0f..AdjustmentKind.DISPLAY_RANGE.toFloat(),
                    onValueChange = onIntensity,
                    onValueChangeFinished = onIntensityFinished,
                    formatValue = { Math.round(it).toString() },
                    resetValue = AdjustmentKind.DISPLAY_RANGE.toFloat(),
                    snapThreshold = 0f,
                )
            } else {
                Text(stringResource(R.string.framekit_filter_hint), color = colors.foregroundMuted, style = MaterialTheme.typography.bodySmall)
            }
        }
        val listState = rememberLazyListState()
        LazyRow(
            state = listState,
            contentPadding = PaddingValues(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(top = 8.dp).horizontalFadingEdges(listState).selectableGroup(),
        ) {
            items(catalog.filterIds, key = { it }) { id ->
                val isSelected = id == selectedId
                val label = catalog.filterLabel(id) ?: filterLabelRes(id)?.let { stringResource(it) } ?: id
                val borderWidth by animateDpAsState(if (isSelected) 2.5.dp else 0.dp, label = "filter-border")
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .width(THUMBNAIL_SIZE)
                        .selectable(selected = isSelected, role = Role.RadioButton) { onSelect(id) },
                ) {
                    Box(
                        Modifier
                            .size(THUMBNAIL_SIZE)
                            .clip(MaterialTheme.shapes.medium)
                            .background(colors.raised)
                            // 두께 0으로 그려도 가는 선이 남아, 선택될 때만 테두리를 그린다.
                            .then(if (borderWidth > 0.dp) Modifier.border(borderWidth, colors.accent, MaterialTheme.shapes.medium) else Modifier),
                    ) {
                        thumbnails[id]?.let { bitmap ->
                            Image(
                                bitmap.asImageBitmap(),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.size(THUMBNAIL_SIZE).padding(borderWidth).clip(MaterialTheme.shapes.medium),
                            )
                        }
                        if (isSelected) {
                            // 색만으로 선택을 알리지 않도록 오른쪽 아래에 체크 배지를 단다.
                            Box(
                                Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(5.dp)
                                    .size(18.dp)
                                    .background(colors.accent, CircleShape),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(Icons.Filled.Check, contentDescription = null, tint = colors.onAccent, modifier = Modifier.size(12.dp))
                            }
                        }
                    }
                    Text(
                        label,
                        color = if (isSelected) colors.foreground else colors.foregroundMuted,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }
    }
}

private val THUMBNAIL_SIZE = 68.dp
private val DIAL_AREA_HEIGHT = 72.dp
