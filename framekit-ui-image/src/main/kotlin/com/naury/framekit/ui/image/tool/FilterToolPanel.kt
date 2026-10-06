package com.naury.framekit.ui.image.tool

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
import com.naury.framekit.ui.component.ValueSlider
import com.naury.framekit.ui.design.FrameKitTheme
import com.naury.framekit.ui.image.R

/** Preset thumbnails and the intensity slider of the selected preset. */
@Composable
internal fun FilterToolPanel(
    selection: FilterSelection,
    thumbnails: Map<String, Bitmap>,
    onSelect: (String) -> Unit,
    onIntensity: (Float) -> Unit,
    onIntensityFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = FrameKitTheme.colors
    val selectedId = if (selection.isIdentity) FilterCatalog.ORIGINAL_ID else selection.presetId
    Column(modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        LazyRow(
            contentPadding = PaddingValues(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.selectableGroup(),
        ) {
            items(FilterCatalog.presets, key = { it.id }) { preset ->
                val isSelected = preset.id == selectedId
                val label = filterLabelRes(preset.id)?.let { stringResource(it) } ?: preset.id
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .width(72.dp)
                        .selectable(selected = isSelected, role = Role.RadioButton) { onSelect(preset.id) },
                ) {
                    Box(
                        Modifier
                            .size(64.dp)
                            .clip(MaterialTheme.shapes.small)
                            .background(colors.raised)
                            .border(if (isSelected) 2.dp else 0.dp, if (isSelected) colors.accent else colors.raised, MaterialTheme.shapes.small),
                    ) {
                        thumbnails[preset.id]?.let { bitmap ->
                            Image(bitmap.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(64.dp))
                        }
                    }
                    Text(
                        label,
                        color = if (isSelected) colors.foreground else colors.foregroundMuted,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
        if (selectedId != FilterCatalog.ORIGINAL_ID) {
            ValueSlider(
                label = stringResource(R.string.framekit_filter_intensity),
                value = (selection.intensity * AdjustmentKind.DISPLAY_RANGE).toFloat(),
                valueRange = 0f..AdjustmentKind.DISPLAY_RANGE.toFloat(),
                onValueChange = onIntensity,
                onValueChangeFinished = onIntensityFinished,
                formatValue = { Math.round(it).toString() },
                resetValue = AdjustmentKind.DISPLAY_RANGE.toFloat(),
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}
