package com.naury.framekit.ui.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.naury.framekit.ui.config.EditorPalette
import com.naury.kinetica.ui.KineticaColors
import com.naury.kinetica.ui.KineticaDesign
import com.naury.kinetica.ui.KineticaShapes
import com.naury.kinetica.ui.KineticaTheme
import com.naury.kinetica.ui.LocalKineticaFillContrast
import com.naury.kinetica.ui.LocalKineticaHaptics
import com.naury.kinetica.ui.SurfaceStyle

/**
 * 호스트가 정한 에디터 색, 모서리, 글꼴, 햅틱 설정을 Kinetica 컴포넌트에도 그대로 적용한다.
 *
 * 편집 화면은 미리보기가 매 프레임 바뀌므로 블러 표면 대신 단색 표면만 쓴다. 눌림 표시는 기존 요소의 모양을 지키도록
 * 호스트 값을 그대로 둔다.
 */
@Composable
internal fun ProvideKinetica(
    colors: FrameKitColors,
    radius: Dp,
    fontFamily: FontFamily?,
    haptics: Boolean,
    content: @Composable () -> Unit,
) {
    val surface = remember(colors) {
        SurfaceStyle.Solid(
            color = colors.surface,
            contentColor = colors.foreground,
            background = colors.background,
            contentMuted = colors.foregroundMuted,
            raised = colors.raised,
        )
    }
    val kineticaColors = remember(colors) {
        val onError = Color(EditorPalette.readableOn(colors.error.toArgb()))
        // 켜진 스위치도 프라이머리 색으로 칠해 기존 편집기와 같게 보인다.
        KineticaColors(
            accent = colors.accent,
            onAccent = colors.onAccent,
            positive = colors.accent,
            onPositive = colors.onAccent,
            destructive = colors.error,
            onDestructive = onError,
        )
    }
    val design = remember(radius) {
        KineticaDesign(
            shapes = KineticaShapes(
                extraSmall = radius / 3,
                small = radius / 2,
                medium = radius,
                large = radius,
                extraLarge = radius,
                huge = DIALOG_RADIUS,
            ),
        )
    }
    val textStyle = remember(fontFamily) { fontFamily?.let { TextStyle(fontFamily = it) } ?: TextStyle.Default }
    KineticaTheme(
        darkTheme = colors.isDark,
        surface = surface,
        colors = kineticaColors,
        textStyle = textStyle,
        indication = null,
        design = design,
    ) {
        // 호스트 브랜드색을 그대로 쓰도록 채운 면의 글자 대비는 FrameKit과 같은 3:1 기준으로 고른다.
        CompositionLocalProvider(
            LocalKineticaHaptics provides haptics,
            LocalKineticaFillContrast provides FILL_CONTRAST,
            content = content,
        )
    }
}

// EditorPalette.readableOn이 강조색 위 글자색을 고르는 기준과 같다.
private const val FILL_CONTRAST = 3f

// FrameKitDialog의 모서리와 맞춘다.
private val DIALOG_RADIUS = 28.dp
