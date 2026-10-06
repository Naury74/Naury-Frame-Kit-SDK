package com.naury.framekit.ui.tool

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.naury.framekit.core.geometry.GeometryEdit
import com.naury.framekit.ui.component.ValueSlider
import com.naury.framekit.ui.design.FrameKitTheme
import com.naury.framekit.ui.R
import java.util.Locale

/** 90° 회전, 뒤집기, 수평 보정 슬라이더. */
@Composable
public fun RotateToolPanel(
    straightenDegrees: Double,
    onRotateLeft: () -> Unit,
    onRotateRight: () -> Unit,
    onFlipHorizontal: () -> Unit,
    onFlipVertical: () -> Unit,
    onStraighten: (Double) -> Unit,
    onStraightenFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val resources = LocalResources.current
    Column(modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            ToolIconButton(R.drawable.framekit_ic_rotate_left, R.string.framekit_rotate_left, onRotateLeft)
            ToolIconButton(R.drawable.framekit_ic_rotate_right, R.string.framekit_rotate_right, onRotateRight)
            ToolIconButton(R.drawable.framekit_ic_flip_horizontal, R.string.framekit_flip_horizontal, onFlipHorizontal)
            ToolIconButton(R.drawable.framekit_ic_flip_vertical, R.string.framekit_flip_vertical, onFlipVertical)
        }
        ValueSlider(
            label = stringResource(R.string.framekit_straighten),
            value = straightenDegrees.toFloat(),
            valueRange = GeometryEdit.MIN_STRAIGHTEN_DEGREES.toFloat()..GeometryEdit.MAX_STRAIGHTEN_DEGREES.toFloat(),
            onValueChange = { onStraighten(it.toDouble()) },
            onValueChangeFinished = onStraightenFinished,
            formatValue = { resources.getString(R.string.framekit_degrees, String.format(Locale.US, "%.1f", it)) },
            snapThreshold = 0.6f,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun ToolIconButton(@DrawableRes icon: Int, @StringRes label: Int, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(painterResource(icon), contentDescription = stringResource(label), tint = FrameKitTheme.colors.foreground)
    }
}
