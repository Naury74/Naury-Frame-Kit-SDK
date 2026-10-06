package com.naury.framekit.ui.config

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class EditorPaletteTest {

    private val darkBase = EditorPalette(
        backgroundArgb = 0xFF0D0D0E.toInt(),
        surfaceArgb = 0xFF171719.toInt(),
        foregroundArgb = 0xFFFFFFFF.toInt(),
        foregroundMutedArgb = 0xFFA1A1AA.toInt(),
        onAccentArgb = 0xFFFFFFFF.toInt(),
    )

    @Test
    fun `contrast follows the WCAG formula`() {
        assertThat(EditorPalette.contrast(0xFF000000.toInt(), 0xFFFFFFFF.toInt())).isWithin(0.01).of(21.0)
        assertThat(EditorPalette.contrast(0xFF777777.toInt(), 0xFF777777.toInt())).isWithin(0.01).of(1.0)
    }

    @Test
    fun `default dark colors pass and low contrast overrides are reported`() {
        assertThat(EditorPalette().contrastWarnings(darkBase, 0xFF635BFF.toInt())).isEmpty()

        val grayOnGray = EditorPalette(foregroundArgb = 0xFF333336.toInt())
        val paleAccent = EditorPalette()

        assertThat(grayOnGray.contrastWarnings(darkBase, 0xFF635BFF.toInt())).contains("foreground/background")
        assertThat(paleAccent.contrastWarnings(darkBase, 0xFFF0F0FF.toInt())).containsExactly("onAccent/accent")
    }
}
