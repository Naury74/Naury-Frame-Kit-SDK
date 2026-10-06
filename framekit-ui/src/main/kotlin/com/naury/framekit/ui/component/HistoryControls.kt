package com.naury.framekit.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.naury.framekit.ui.R
import com.naury.framekit.ui.design.FrameKitTheme

/**
 * Floating undo/redo buttons shown above the canvas. Disabled buttons stay visible so their position
 * does not jump; their state is announced by accessibility services.
 */
@Composable
public fun HistoryControls(
    canUndo: Boolean,
    canRedo: Boolean,
    showUndo: Boolean,
    showRedo: Boolean,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!showUndo && !showRedo) return
    val colors = FrameKitTheme.colors
    Row(modifier.background(colors.surface.copy(alpha = 0.86f), MaterialTheme.shapes.medium)) {
        if (showUndo) {
            IconButton(onClick = onUndo, enabled = canUndo) {
                Icon(
                    painterResource(R.drawable.framekit_ic_undo),
                    contentDescription = stringResource(R.string.framekit_action_undo),
                    tint = if (canUndo) colors.foreground else colors.foregroundMuted.copy(alpha = 0.4f),
                )
            }
        }
        if (showRedo) {
            IconButton(onClick = onRedo, enabled = canRedo) {
                Icon(
                    painterResource(R.drawable.framekit_ic_redo),
                    contentDescription = stringResource(R.string.framekit_action_redo),
                    tint = if (canRedo) colors.foreground else colors.foregroundMuted.copy(alpha = 0.4f),
                )
            }
        }
    }
}
