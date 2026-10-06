package com.naury.framekit.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.naury.framekit.ui.R
import com.naury.framekit.ui.design.FrameKitTheme

/** Cancel, an optional reset and Apply for tools that edit a draft. */
@Composable
public fun ApplyCancelBar(
    title: String,
    onCancel: () -> Unit,
    onApply: () -> Unit,
    modifier: Modifier = Modifier,
    onReset: (() -> Unit)? = null,
) {
    val colors = FrameKitTheme.colors
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        IconButton(onClick = onCancel) {
            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.framekit_action_cancel), tint = colors.foreground)
        }
        if (onReset != null) {
            TextButton(onClick = onReset) { Text("$title · ${stringResource(R.string.framekit_action_reset)}", color = colors.foregroundMuted) }
        } else {
            Text(title, color = colors.foreground)
        }
        IconButton(onClick = onApply) {
            Icon(Icons.Filled.Check, contentDescription = stringResource(R.string.framekit_action_apply), tint = colors.accent)
        }
    }
}
