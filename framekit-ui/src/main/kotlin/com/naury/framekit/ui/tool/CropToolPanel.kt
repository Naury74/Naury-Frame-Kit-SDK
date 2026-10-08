package com.naury.framekit.ui.tool

import androidx.compose.foundation.layout.Arrangement
import com.naury.framekit.ui.component.FrameKitDialog
import com.naury.framekit.ui.component.DialogActionStyle
import com.naury.framekit.ui.component.DialogAction
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.naury.framekit.core.geometry.CropAspectRatio
import com.naury.framekit.ui.R
import com.naury.framekit.ui.component.ChoiceChips
import com.naury.framekit.ui.design.FrameKitTheme

/**
 * 자르기 도구의 비율 칩과 빠른 회전·뒤집기 버튼. 프레임 자체는 캔버스에서 드래그한다.
 *
 * 프리셋에 없는 비율은 "직접 입력"으로 가로·세로 값을 넣어 고른다. 회전 버튼은 도구를 바꾸지 않고
 * 자르기 중에 방향을 맞출 수 있게 하며, 콜백이 `null`이면 숨긴다.
 */
@Composable
public fun CropToolPanel(
    aspect: CropAspectRatio,
    onSelectAspect: (CropAspectRatio) -> Unit,
    modifier: Modifier = Modifier,
    onRotateLeft: (() -> Unit)? = null,
    onRotateRight: (() -> Unit)? = null,
    onFlipHorizontal: (() -> Unit)? = null,
) {
    var editingCustom by rememberSaveable { mutableStateOf(false) }
    // null은 "직접 입력" 칩이다. 프리셋에 없는 고정 비율이 선택되어 있으면 이 칩이 선택된 것으로 보인다.
    val options: List<CropAspectRatio?> = CropAspectRatio.presets + null
    Column(modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        ChoiceChips(
            options = options,
            selected = aspect.takeIf { it in CropAspectRatio.presets },
            label = { option ->
                when (option) {
                    null -> (aspect as? CropAspectRatio.Fixed)?.takeIf { it !in CropAspectRatio.presets }
                        ?.let { stringResource(R.string.framekit_ratio_custom_value, it.width, it.height) }
                        ?: stringResource(R.string.framekit_ratio_custom)
                    CropAspectRatio.Free -> stringResource(R.string.framekit_ratio_free)
                    CropAspectRatio.Original -> stringResource(R.string.framekit_ratio_original)
                    is CropAspectRatio.Fixed -> "${option.width}:${option.height}"
                }
            },
            onSelect = { option -> if (option == null) editingCustom = true else onSelectAspect(option) },
            modifier = Modifier.padding(vertical = 4.dp),
        )
        if (onRotateLeft != null || onRotateRight != null || onFlipHorizontal != null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                val tint = FrameKitTheme.colors.foreground
                onRotateLeft?.let {
                    IconButton(onClick = it) {
                        Icon(painterResource(R.drawable.framekit_ic_rotate_left), stringResource(R.string.framekit_rotate_left), tint = tint)
                    }
                }
                onRotateRight?.let {
                    IconButton(onClick = it) {
                        Icon(painterResource(R.drawable.framekit_ic_rotate_right), stringResource(R.string.framekit_rotate_right), tint = tint)
                    }
                }
                onFlipHorizontal?.let {
                    IconButton(onClick = it) {
                        Icon(painterResource(R.drawable.framekit_ic_flip_horizontal), stringResource(R.string.framekit_flip_horizontal), tint = tint)
                    }
                }
            }
        }
    }
    if (editingCustom) {
        CustomRatioDialog(
            initial = aspect as? CropAspectRatio.Fixed,
            onConfirm = { width, height ->
                editingCustom = false
                onSelectAspect(CropAspectRatio.Fixed(width, height))
            },
            onDismiss = { editingCustom = false },
        )
    }
}

@Composable
private fun CustomRatioDialog(initial: CropAspectRatio.Fixed?, onConfirm: (Int, Int) -> Unit, onDismiss: () -> Unit) {
    val colors = FrameKitTheme.colors
    var width by rememberSaveable { mutableStateOf(initial?.width?.toString() ?: "") }
    var height by rememberSaveable { mutableStateOf(initial?.height?.toString() ?: "") }
    val w = width.toIntOrNull()?.takeIf { it in 1..MAX_RATIO_SIDE }
    val h = height.toIntOrNull()?.takeIf { it in 1..MAX_RATIO_SIDE }
    FrameKitDialog(
        title = stringResource(R.string.framekit_ratio_custom),
        icon = ImageVector.vectorResource(R.drawable.framekit_ic_crop),
        onDismiss = onDismiss,
        actions = listOf(
            DialogAction(stringResource(R.string.framekit_action_apply), DialogActionStyle.PRIMARY, enabled = w != null && h != null) {
                if (w != null && h != null) onConfirm(w, h)
            },
            DialogAction(stringResource(R.string.framekit_action_cancel), DialogActionStyle.SECONDARY, onClick = onDismiss),
        ),
    ) {
        // 입력한 비율의 모양을 바로 보여 줘 숫자를 바꿔 넣는 실수를 줄인다.
        val ratio = if (w != null && h != null) w.toFloat() / h else null
        Box(Modifier.height(72.dp).fillMaxWidth(), contentAlignment = Alignment.Center) {
            if (ratio == null) {
                // 값을 넣기 전에는 흐린 정사각형으로 자리를 보여 준다.
                Box(Modifier.size(48.dp).border(2.dp, colors.raised, MaterialTheme.shapes.small))
            } else {
                val boxWidth = if (ratio >= 1f) 96.dp else 96.dp * ratio.coerceAtLeast(0.2f)
                val boxHeight = if (ratio >= 1f) 96.dp / ratio.coerceAtMost(5f) else 96.dp
                Box(
                    Modifier
                        .size(boxWidth.coerceAtMost(96.dp) * 0.7f, boxHeight.coerceAtMost(96.dp) * 0.7f)
                        .border(2.dp, colors.accent, MaterialTheme.shapes.small),
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RatioField(width, { width = it.filter(Char::isDigit).take(4) }, stringResource(R.string.framekit_ratio_width), w == null && width.isNotEmpty(), Modifier.weight(1f))
            Text(":", color = colors.foreground, style = MaterialTheme.typography.titleLarge)
            RatioField(height, { height = it.filter(Char::isDigit).take(4) }, stringResource(R.string.framekit_ratio_height), h == null && height.isNotEmpty(), Modifier.weight(1f))
        }
    }
}

@Composable
private fun RatioField(value: String, onChange: (String) -> Unit, label: String, isError: Boolean, modifier: Modifier) {
    val colors = FrameKitTheme.colors
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        isError = isError,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        shape = MaterialTheme.shapes.medium,
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = colors.foreground,
            unfocusedTextColor = colors.foreground,
            focusedBorderColor = colors.accent,
            unfocusedBorderColor = colors.raised,
            focusedLabelColor = colors.accent,
            unfocusedLabelColor = colors.foregroundMuted,
            cursorColor = colors.accent,
        ),
        modifier = modifier,
    )
}

// 너무 큰 값은 실수일 가능성이 높고 비율 계산에도 의미가 없어 네 자리까지만 받는다.
private const val MAX_RATIO_SIDE = 9999
