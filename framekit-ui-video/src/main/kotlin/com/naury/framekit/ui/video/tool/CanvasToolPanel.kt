package com.naury.framekit.ui.video.tool

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.naury.framekit.core.video.CanvasFit
import com.naury.framekit.core.video.CanvasSpec
import com.naury.framekit.ui.component.ChoiceChips
import com.naury.framekit.ui.design.FrameKitTheme
import com.naury.framekit.ui.video.R

/** 결과 화면 비율과, 모양이 다른 클립을 맞출지(여백) 채울지(가장자리 자름). */
@Composable
internal fun CanvasToolPanel(canvas: CanvasSpec, onChange: (CanvasSpec) -> Unit, modifier: Modifier = Modifier) {
    val ratios = listOf<Pair<Int, Int>?>(null, 9 to 16, 16 to 9, 1 to 1, 4 to 5, 3 to 4)
    val current = canvas.aspectWidth?.let { w -> canvas.aspectHeight?.let { h -> w to h } }
    val originalLabel = stringResource(R.string.framekit_canvas_original)
    Column(modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        ChoiceChips(
            options = ratios,
            selected = current,
            label = { ratio -> ratio?.let { "${it.first}:${it.second}" } ?: originalLabel },
            onSelect = { ratio -> onChange(canvas.copy(aspectWidth = ratio?.first, aspectHeight = ratio?.second)) },
        )
        ChoiceChips(
            options = CanvasFit.entries,
            selected = canvas.fit,
            label = { stringResource(if (it == CanvasFit.FIT) R.string.framekit_canvas_fit else R.string.framekit_canvas_fill) },
            onSelect = { fit -> onChange(canvas.copy(fit = fit)) },
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            stringResource(R.string.framekit_canvas_help),
            color = FrameKitTheme.colors.foregroundMuted,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}
