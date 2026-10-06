package com.naury.framekit.ui.canvas

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.naury.framekit.ui.R
import com.naury.framekit.ui.design.FrameKitTheme
import kotlin.math.roundToInt

/**
 * 캔버스 화면 확대 상태. 1배에서 최대 [MAX_ZOOM]배까지이며, 확대된 콘텐츠가 화면 밖으로 빠져 빈 곳이
 * 보이지 않도록 이동량을 제한한다.
 *
 * [pan]은 화면 픽셀 단위이며 화면 중심을 기준으로 [zoom]배 확대한 뒤 더하는 이동량이다.
 */
@Stable
public class CanvasZoomState {
    public var zoom: Float by mutableFloatStateOf(1f)
        private set
    public var pan: Offset by mutableStateOf(Offset.Zero)
        private set

    /**
     * 두 손가락 제스처 한 번만큼 확대·이동한다.
     *
     * @param zoomChange 이번 이벤트의 배율 변화(1이면 그대로).
     * @param localPan 확대 전 좌표계의 이동량(px). 화면에서는 [zoom]배로 보인다.
     * @param width 확대하는 영역의 폭(px). 이동 한계를 정한다.
     * @param height 확대하는 영역의 높이(px).
     */
    public fun apply(zoomChange: Float, localPan: Offset, width: Float, height: Float) {
        val next = (zoom * zoomChange).coerceIn(1f, MAX_ZOOM)
        val maxX = (next - 1f) * width / 2f
        val maxY = (next - 1f) * height / 2f
        val moved = pan * (next / zoom) + localPan * next
        zoom = next
        pan = Offset(moved.x.coerceIn(-maxX, maxX), moved.y.coerceIn(-maxY, maxY))
    }

    /** 원래 크기(1배, 이동 없음)로 돌아간다. */
    public fun reset() {
        zoom = 1f
        pan = Offset.Zero
    }

    private companion object {
        const val MAX_ZOOM = 5f
    }
}

/**
 * 확대 중일 때 현재 배율을 보여 주고 누르면 원래 크기로 돌아가는 버튼. 1배면 아무것도 그리지 않는다.
 * 두 손가락 제스처를 모르는 사용자도 확대에서 빠져나올 수 있게 한다.
 */
@Composable
public fun ZoomResetButton(state: CanvasZoomState, modifier: Modifier = Modifier) {
    if (state.zoom <= 1.01f) return
    val colors = FrameKitTheme.colors
    TextButton(
        onClick = state::reset,
        modifier = modifier.padding(8.dp).background(colors.surface.copy(alpha = 0.86f), MaterialTheme.shapes.small),
    ) {
        Text(stringResource(R.string.framekit_zoom_fit, (state.zoom * 100).roundToInt()), color = colors.foreground)
    }
}
