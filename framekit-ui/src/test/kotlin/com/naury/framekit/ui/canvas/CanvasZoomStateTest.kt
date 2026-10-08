package com.naury.framekit.ui.canvas

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CanvasZoomStateTest {

    private val size = Size(1000f, 800f)

    @Test
    fun `zoom stays between 1x and 5x and pan keeps the content on screen`() {
        val state = CanvasZoomState()

        state.transform(zoomChange = 0.5f, panChange = Offset(100f, 0f), centroid = Offset(500f, 400f), size = size)
        assertThat(state.zoom).isEqualTo(1f)
        assertThat(state.pan).isEqualTo(Offset.Zero)

        state.transform(zoomChange = 2f, panChange = Offset(2000f, -2000f), centroid = Offset(500f, 400f), size = size)
        assertThat(state.zoom).isEqualTo(2f)
        assertThat(state.pan).isEqualTo(Offset(500f, -400f))

        state.transform(zoomChange = 10f, panChange = Offset.Zero, centroid = Offset(500f, 400f), size = size)
        assertThat(state.zoom).isEqualTo(CanvasZoomState.MAX_ZOOM)

        state.reset()
        assertThat(state.zoom).isEqualTo(1f)
        assertThat(state.pan).isEqualTo(Offset.Zero)
    }

    @Test
    fun `pinch keeps the content under the fingers`() {
        val state = CanvasZoomState()
        val centroid = Offset(700f, 300f)
        // 손가락 아래의 확대 전 좌표.
        val local = centroid
        state.transform(zoomChange = 2f, panChange = Offset.Zero, centroid = centroid, size = size)
        val screen = Offset(500f, 400f) + (local - Offset(500f, 400f)) * state.zoom + state.pan
        assertThat(screen.x).isWithin(0.01f).of(centroid.x)
        assertThat(screen.y).isWithin(0.01f).of(centroid.y)
    }

    @Test
    fun `fit target fills the area with the cropped region`() {
        val state = CanvasZoomState()
        // 왼쪽 위 1/4 영역을 남기면 2배로 키우고 그 영역을 가운데로 옮긴다.
        val (zoom, pan) = state.fitTarget(Rect(0f, 0f, 500f, 400f), size, padding = 0f)
        assertThat(zoom).isWithin(0.001f).of(2f)
        assertThat(pan.x).isWithin(0.01f).of(500f)
        assertThat(pan.y).isWithin(0.01f).of(400f)

        // 전체를 남기면 원래 크기.
        assertThat(state.fitTarget(Rect(0f, 0f, 1000f, 800f), size, padding = 0f).first).isEqualTo(1f)
    }

    @Test
    fun `double tap zooms around the tapped point and back`() {
        val state = CanvasZoomState()
        val (zoom, pan) = state.doubleTapTarget(Offset(500f, 400f), size)
        assertThat(zoom).isEqualTo(CanvasZoomState.DOUBLE_TAP_ZOOM)
        assertThat(pan).isEqualTo(Offset.Zero)
        state.transform(2f, Offset.Zero, Offset(500f, 400f), size)
        assertThat(state.doubleTapTarget(Offset(100f, 100f), size)).isEqualTo(1f to Offset.Zero)
    }
}
