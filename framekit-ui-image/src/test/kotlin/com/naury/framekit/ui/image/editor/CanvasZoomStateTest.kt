package com.naury.framekit.ui.image.editor

import androidx.compose.ui.geometry.Offset
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CanvasZoomStateTest {

    @Test
    fun `zoom stays between 1x and 5x and pan keeps the content on screen`() {
        val state = CanvasZoomState()

        state.apply(zoomChange = 0.5f, localPan = Offset(100f, 0f), width = 1000f, height = 800f)
        assertThat(state.zoom).isEqualTo(1f)
        assertThat(state.pan).isEqualTo(Offset.Zero)

        state.apply(zoomChange = 2f, localPan = Offset(1000f, -1000f), width = 1000f, height = 800f)
        assertThat(state.zoom).isEqualTo(2f)
        assertThat(state.pan).isEqualTo(Offset(500f, -400f))

        state.apply(zoomChange = 10f, localPan = Offset.Zero, width = 1000f, height = 800f)
        assertThat(state.zoom).isEqualTo(5f)

        state.reset()
        assertThat(state.zoom).isEqualTo(1f)
        assertThat(state.pan).isEqualTo(Offset.Zero)
    }
}
