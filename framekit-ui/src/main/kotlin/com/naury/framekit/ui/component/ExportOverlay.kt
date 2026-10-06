package com.naury.framekit.ui.component

import kotlin.math.roundToInt
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.naury.framekit.ui.R
import com.naury.framekit.ui.design.FrameKitTheme

/** Stage shown while an export runs. */
public enum class ExportStageUi {
    PREPARING,
    RENDERING,
    ENCODING,
    FINALIZING,

    /** Used when the engine does not report separate stages. */
    EXPORTING,
    CANCELLING,
}

/**
 * Blocks editing while an export runs. Cancel takes effect immediately without a confirmation and the
 * overlay switches to [ExportStageUi.CANCELLING] until the engine stops.
 *
 * @param progress `0..1` when the engine can estimate it; the indicator is indeterminate otherwise.
 */
@Composable
public fun ExportOverlay(stage: ExportStageUi, onCancel: () -> Unit, modifier: Modifier = Modifier, progress: Float? = null) {
    val colors = FrameKitTheme.colors
    val showStage = FrameKitTheme.config.showExportProgress
    val label = stringResource(
        when (if (showStage) stage else if (stage == ExportStageUi.CANCELLING) stage else ExportStageUi.EXPORTING) {
            ExportStageUi.PREPARING -> R.string.framekit_export_preparing
            ExportStageUi.RENDERING -> R.string.framekit_export_rendering
            ExportStageUi.ENCODING -> R.string.framekit_export_encoding
            ExportStageUi.FINALIZING -> R.string.framekit_export_finalizing
            ExportStageUi.EXPORTING -> R.string.framekit_export_exporting
            ExportStageUi.CANCELLING -> R.string.framekit_export_cancelling
        },
    )
    Box(
        modifier
            .fillMaxSize()
            .background(colors.background.copy(alpha = 0.82f))
            // 뒤의 편집 화면이 터치를 받지 않도록 overlay가 입력을 소비한다.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {}),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (progress != null && showStage && stage != ExportStageUi.CANCELLING) {
                CircularProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, color = colors.accent)
            } else {
                CircularProgressIndicator(color = colors.accent)
            }
            Text(
                if (progress != null && showStage && stage == ExportStageUi.ENCODING) "$label ${(progress * 100).roundToInt()}%" else label,
                color = colors.foreground,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            if (stage != ExportStageUi.CANCELLING) {
                TextButton(onClick = onCancel, modifier = Modifier.padding(top = 8.dp)) {
                    Text(stringResource(R.string.framekit_action_cancel), color = colors.foregroundMuted)
                }
            }
        }
    }
}
