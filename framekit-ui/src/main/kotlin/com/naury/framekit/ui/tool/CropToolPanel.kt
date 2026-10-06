package com.naury.framekit.ui.tool

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.framekit_ratio_custom)) },
        text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RatioField(width, { width = it.filter(Char::isDigit).take(4) }, stringResource(R.string.framekit_ratio_width), w == null && width.isNotEmpty())
                Text(" : ", color = colors.foreground)
                RatioField(height, { height = it.filter(Char::isDigit).take(4) }, stringResource(R.string.framekit_ratio_height), h == null && height.isNotEmpty())
            }
        },
        confirmButton = {
            TextButton(onClick = { if (w != null && h != null) onConfirm(w, h) }, enabled = w != null && h != null) {
                Text(stringResource(R.string.framekit_action_apply))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.framekit_action_cancel)) } },
        containerColor = colors.surface,
    )
}

@Composable
private fun RatioField(value: String, onChange: (String) -> Unit, label: String, isError: Boolean) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        isError = isError,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.width(96.dp),
    )
}

// 너무 큰 값은 실수일 가능성이 높고 비율 계산에도 의미가 없어 네 자리까지만 받는다.
private const val MAX_RATIO_SIDE = 9999
