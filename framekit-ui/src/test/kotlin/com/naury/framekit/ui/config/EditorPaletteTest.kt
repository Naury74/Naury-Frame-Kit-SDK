package com.naury.framekit.ui.config

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class EditorPaletteTest {

    private val darkBase = EditorPalette(
        backgroundArgb = 0xFF0D0D0E.toInt(),
        surfaceArgb = 0xFF171719.toInt(),
        foregroundArgb = 0xFFFFFFFF.toInt(),
        foregroundMutedArgb = 0xFFA1A1AA.toInt(),
        onPrimaryArgb = 0xFFFFFFFF.toInt(),
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
        val whiteOnPrimary = EditorPalette(onPrimaryArgb = 0xFFFFFFFF.toInt())

        assertThat(grayOnGray.contrastWarnings(darkBase, 0xFF635BFF.toInt())).contains("foreground/background")
        // 글자색을 흰색으로 고정하면 밝은 프라이머리 색에서 경고한다.
        assertThat(whiteOnPrimary.contrastWarnings(darkBase.copy(onPrimaryArgb = null), 0xFFF0F0FF.toInt())).containsExactly("onPrimary/primary")
        // 지정하지 않으면 검정을 자동으로 골라 경고하지 않는다.
        assertThat(EditorPalette().contrastWarnings(darkBase.copy(onPrimaryArgb = null), 0xFFF0F0FF.toInt())).isEmpty()
        // 패널과 거의 같은 색이면 선택 표시가 보이지 않는다.
        assertThat(EditorPalette().contrastWarnings(darkBase, 0xFF1A1A1E.toInt())).contains("primary/surface")
    }

    @Test
    fun `readable text color follows the primary color`() {
        assertThat(EditorPalette.readableOn(0xFFFFD60A.toInt())).isEqualTo(0xFF000000.toInt())
        assertThat(EditorPalette.readableOn(0xFF635BFF.toInt())).isEqualTo(0xFFFFFFFF.toInt())
        assertThat(EditorPalette.readableOn(0xFF1E6BFF.toInt())).isEqualTo(0xFFFFFFFF.toInt())
    }
}
