package com.naury.framekit.ui.canvas

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.center
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.naury.framekit.ui.R
import com.naury.framekit.ui.design.FrameKitTheme
import kotlinx.coroutines.launch
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 캔버스 화면 확대 상태. 편집 결과에는 영향이 없고 화면에 보이는 크기와 위치만 바꾼다.
 *
 * 화면 좌표 `s`와 확대 전(레이어 안) 좌표 `l`의 관계는 `s = c + (l - c) * zoom + pan`이다. `c`는 영역의 중심이다.
 * 배율은 1..[MAX_ZOOM]이며, 손으로 옮길 때는 확대된 콘텐츠가 영역 밖으로 빠져 빈 곳이 보이지 않게 [pan]을 제한한다.
 */
@Stable
public class CanvasZoomState {
    /** 현재 배율, `1..MAX_ZOOM`. */
    public var zoom: Float by mutableFloatStateOf(1f)
        private set

    /** 확대 뒤에 더하는 이동량(화면 px). */
    public var pan: Offset by mutableStateOf(Offset.Zero)
        private set

    /** 확대되어 있으면 `true`. */
    public val isZoomed: Boolean get() = zoom > ZOOMED_THRESHOLD

    /**
     * 두 손가락(또는 확대 중 한 손가락) 제스처 한 번만큼 바꾼다. [centroid]의 콘텐츠가 손가락 아래에 머물도록 확대한다.
     *
     * @param zoomChange 이번 이벤트의 배율 변화(1이면 그대로).
     * @param panChange 이번 이벤트의 이동량(화면 px).
     * @param centroid 손가락 중심(화면 px, 영역 왼쪽 위 기준).
     * @param size 영역 크기(px).
     */
    public fun transform(zoomChange: Float, panChange: Offset, centroid: Offset, size: Size) {
        val next = (zoom * zoomChange).coerceIn(1f, MAX_ZOOM)
        val center = size.center
        val anchored = (centroid - center) - (centroid - center - pan) * (next / zoom)
        zoom = next
        pan = clamp(anchored + panChange, next, size)
    }

    /** 원래 크기(1배, 이동 없음)로 바로 돌아간다. */
    public fun reset() {
        zoom = 1f
        pan = Offset.Zero
    }

    /**
     * 확대 전 좌표의 [target] 사각형이 영역(여백 [padding] 제외)을 채우도록 배율과 위치를 정한다. 자르기 영역을
     * 줄였을 때 남은 부분을 크게 보여 주는 데 쓴다. 배율은 1..[MAX_ZOOM]으로 제한한다.
     */
    public fun fitTarget(target: Rect, size: Size, padding: Float): Pair<Float, Offset> {
        if (target.width <= 0f || target.height <= 0f) return 1f to Offset.Zero
        val available = Size((size.width - 2 * padding).coerceAtLeast(1f), (size.height - 2 * padding).coerceAtLeast(1f))
        val next = min(available.width / target.width, available.height / target.height).coerceIn(1f, MAX_ZOOM)
        val shift = (target.center - size.center) * next
        return next to clamp(-shift, next, size)
    }

    /** [targetZoom]·[targetPan]으로 부드럽게 옮긴다. */
    public suspend fun animateTo(targetZoom: Float, targetPan: Offset) {
        val startZoom = zoom
        val startPan = pan
        if (startZoom == targetZoom && startPan == targetPan) return
        animate(0f, 1f, animationSpec = tween(ANIMATION_MS, easing = FastOutSlowInEasing)) { fraction, _ ->
            zoom = startZoom + (targetZoom - startZoom) * fraction
            pan = startPan + (targetPan - startPan) * fraction
        }
    }

    /**
     * 두 번 탭에 쓰는 전환 목표. 확대되어 있으면 원래 크기, 아니면 [localPoint](확대 전 좌표)를 중심으로
     * [DOUBLE_TAP_ZOOM]배다.
     */
    public fun doubleTapTarget(localPoint: Offset, size: Size): Pair<Float, Offset> {
        if (isZoomed) return 1f to Offset.Zero
        val next = DOUBLE_TAP_ZOOM
        // 탭한 지점이 그대로 손가락 아래에 머물게 한다.
        val screen = size.center + (localPoint - size.center) * zoom + pan
        return next to clamp(screen - size.center - (localPoint - size.center) * next, next, size)
    }

    private fun clamp(value: Offset, zoom: Float, size: Size): Offset {
        val maxX = (zoom - 1f) * size.width / 2f
        val maxY = (zoom - 1f) * size.height / 2f
        return Offset(value.x.coerceIn(-maxX, maxX), value.y.coerceIn(-maxY, maxY))
    }

    public companion object {
        /** 최대 배율. */
        public const val MAX_ZOOM: Float = 5f

        /** 두 번 탭할 때의 배율. */
        public const val DOUBLE_TAP_ZOOM: Float = 2.5f

        private const val ZOOMED_THRESHOLD = 1.01f
        private const val ANIMATION_MS = 260
    }
}

/**
 * 확대 중일 때 현재 배율을 보여 주고 누르면 원래 크기로 돌아가는 버튼. 1배면 아무것도 그리지 않는다.
 * 두 손가락 제스처를 모르는 사용자도 확대에서 빠져나올 수 있게 한다.
 */
@Composable
public fun ZoomResetButton(state: CanvasZoomState, modifier: Modifier = Modifier) {
    if (!state.isZoomed) return
    val colors = FrameKitTheme.colors
    val scope = rememberCoroutineScope()
    TextButton(
        onClick = { scope.launch { state.animateTo(1f, Offset.Zero) } },
        modifier = modifier.padding(8.dp).background(colors.surface.copy(alpha = 0.86f), MaterialTheme.shapes.small),
    ) {
        Text(stringResource(R.string.framekit_zoom_fit, (state.zoom * 100).roundToInt()), color = colors.foreground)
    }
}
