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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.runtime.getValue
import androidx.compose.material3.OutlinedButton
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.BorderStroke
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.naury.framekit.ui.R
import com.naury.framekit.ui.design.FrameKitTheme

/** 내보내기 중 표시하는 단계. */
public enum class ExportStageUi {
    PREPARING,
    RENDERING,
    ENCODING,
    FINALIZING,

    /** 엔진이 단계를 따로 보고하지 않을 때 사용한다. */
    EXPORTING,
    CANCELLING,
}

/**
 * 내보내기 중에는 편집을 막는다. 취소는 확인 없이 즉시 적용되며, 엔진이 멈출 때까지 오버레이는
 * [ExportStageUi.CANCELLING]으로 바뀐다.
 *
 * @param progress 엔진이 추정할 수 있으면 `0..1`. 그렇지 않으면 인디케이터는 불확정 상태로 표시된다.
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
        val determinate = progress != null && showStage && stage != ExportStageUi.CANCELLING
        Column(
            Modifier
                .padding(horizontal = 40.dp)
                .widthIn(min = 240.dp, max = 320.dp)
                .background(colors.surface, MaterialTheme.shapes.large)
                .padding(horizontal = 24.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // 진행률을 알 수 있으면 링 안에 퍼센트를 크게 보여 준다.
            Box(Modifier.size(84.dp), contentAlignment = Alignment.Center) {
                if (determinate) {
                    val animated by animateFloatAsState(progress!!.coerceIn(0f, 1f), label = "export-progress")
                    CircularProgressIndicator(
                        progress = { animated },
                        color = colors.accent,
                        trackColor = colors.raised,
                        strokeWidth = 6.dp,
                        modifier = Modifier.size(84.dp),
                    )
                    Text("${(animated * 100).roundToInt()}%", color = colors.foreground, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                } else {
                    CircularProgressIndicator(color = colors.accent, trackColor = colors.raised, strokeWidth = 6.dp, modifier = Modifier.size(84.dp))
                }
            }
            Text(
                label,
                color = colors.foreground,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            if (stage != ExportStageUi.CANCELLING) {
                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    shape = MaterialTheme.shapes.medium,
                    border = BorderStroke(1.dp, colors.raised),
                ) {
                    Text(stringResource(R.string.framekit_action_cancel), color = colors.foreground)
                }
            }
        }
    }
}
