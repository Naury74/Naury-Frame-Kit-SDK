package com.naury.framekit.ui.tool

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.naury.framekit.core.geometry.CropAspectRatio
import com.naury.framekit.ui.component.ChoiceChips
import com.naury.framekit.ui.R

/** 자르기 도구의 비율 칩. 프레임 자체는 캔버스에서 드래그한다. */
@Composable
public fun CropToolPanel(
    aspect: CropAspectRatio,
    onSelectAspect: (CropAspectRatio) -> Unit,
    modifier: Modifier = Modifier,
) {
    ChoiceChips(
        options = CropAspectRatio.presets,
        selected = aspect,
        label = { option ->
            when (option) {
                CropAspectRatio.Free -> stringResource(R.string.framekit_ratio_free)
                CropAspectRatio.Original -> stringResource(R.string.framekit_ratio_original)
                is CropAspectRatio.Fixed -> "${option.width}:${option.height}"
            }
        },
        onSelect = onSelectAspect,
        modifier = modifier.padding(vertical = 12.dp),
    )
}
