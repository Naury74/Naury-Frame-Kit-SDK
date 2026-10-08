package com.naury.framekit.ui.design

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.runtime.remember
import androidx.compose.material3.Typography
import com.naury.framekit.ui.config.EditorPalette
import com.naury.framekit.ui.catalog.LocalCatalogUi
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import com.naury.framekit.ui.config.EditorUiConfig
import com.naury.framekit.ui.config.ThemeMode

/** 이름이 붙은 에디터 색상. 호스트가 대비를 확인할 수 있도록 상태 색상도 이름으로 노출한다. */
@Immutable
public data class FrameKitColors(
    val accent: Color,
    val onAccent: Color,
    val background: Color,
    val surface: Color,
    val raised: Color,
    val foreground: Color,
    val foregroundMuted: Color,
    val canvasBackground: Color,
    val error: Color,
    val isDark: Boolean,
) {
    public companion object {
        public val DefaultAccent: Color = Color(0xFF635BFF)

        public fun dark(accent: Color = DefaultAccent): FrameKitColors = FrameKitColors(
            accent = accent,
            onAccent = Color.White,
            background = Color(0xFF0D0D0E),
            surface = Color(0xFF171719),
            raised = Color(0xFF242428),
            foreground = Color(0xFFFFFFFF),
            foregroundMuted = Color(0xFFA1A1AA),
            canvasBackground = Color(0xFF000000),
            error = Color(0xFFFF6B6B),
            isDark = true,
        )

        public fun light(accent: Color = DefaultAccent): FrameKitColors = FrameKitColors(
            accent = accent,
            onAccent = Color.White,
            background = Color(0xFFF6F6F8),
            surface = Color(0xFFFFFFFF),
            raised = Color(0xFFEDEDF1),
            foreground = Color(0xFF111114),
            foregroundMuted = Color(0xFF5F5F6B),
            canvasBackground = Color(0xFFE4E4E9),
            error = Color(0xFFD92D20),
            isDark = false,
        )
    }
}

/** 에디터 컴포넌트가 읽는, [EditorUiConfig]에서 파생된 값. */
@Immutable
public data class FrameKitDesign(
    val colors: FrameKitColors,
    val config: EditorUiConfig,
)

private val LocalFrameKitDesign = staticCompositionLocalOf { FrameKitDesign(FrameKitColors.dark(), EditorUiConfig()) }

/** [FrameKitTheme] 안에서 현재 에디터 디자인에 접근하는 접근자. */
public object FrameKitTheme {
    public val colors: FrameKitColors
        @Composable get() = LocalFrameKitDesign.current.colors

    public val config: EditorUiConfig
        @Composable get() = LocalFrameKitDesign.current.config
}

/** [config]에 기술된 에디터 색상, 모양, Material 테마를 적용한다. */
@Composable
public fun FrameKitTheme(config: EditorUiConfig, content: @Composable () -> Unit) {
    val dark = when (config.themeMode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    val accent = config.primaryArgb?.let(::Color) ?: FrameKitColors.DefaultAccent
    // 밝은 프라이머리 색(노랑 등)을 받아도 저장 버튼 글자가 보이도록 위 글자색을 자동으로 맞춘다. 팔레트가 지정하면 그 값을 쓴다.
    val base = (if (dark) FrameKitColors.dark(accent) else FrameKitColors.light(accent))
        .copy(onAccent = Color(EditorPalette.readableOn(accent.toArgb())))
    val colors = base.with(config.palette)
    val radius = config.cornerRadiusDp.dp
    val catalog = LocalCatalogUi.current
    val fontFamily = remember(config.uiFontId, catalog) { config.uiFontId?.let(catalog.typeface)?.let { FontFamily(it) } }
    val typography = remember(fontFamily) { fontFamily?.let { Typography().withFont(it) } ?: Typography() }
    CompositionLocalProvider(LocalFrameKitDesign provides FrameKitDesign(colors, config)) {
        MaterialTheme(
            colorScheme = colors.toMaterial(),
            typography = typography,
            shapes = Shapes(
                small = RoundedCornerShape(radius / 2),
                medium = RoundedCornerShape(radius),
                large = RoundedCornerShape(radius),
            ),
            content = content,
        )
    }
}

private fun FrameKitColors.with(palette: EditorPalette?): FrameKitColors {
    if (palette == null) return this
    fun Int?.or(fallback: Color) = this?.let(::Color) ?: fallback
    return copy(
        background = palette.backgroundArgb.or(background),
        surface = palette.surfaceArgb.or(surface),
        raised = palette.raisedArgb.or(raised),
        foreground = palette.foregroundArgb.or(foreground),
        foregroundMuted = palette.foregroundMutedArgb.or(foregroundMuted),
        canvasBackground = palette.canvasArgb.or(canvasBackground),
        onAccent = palette.onPrimaryArgb.or(onAccent),
    )
}

private fun Typography.withFont(family: FontFamily): Typography = copy(
    displayLarge = displayLarge.copy(fontFamily = family),
    displayMedium = displayMedium.copy(fontFamily = family),
    displaySmall = displaySmall.copy(fontFamily = family),
    headlineLarge = headlineLarge.copy(fontFamily = family),
    headlineMedium = headlineMedium.copy(fontFamily = family),
    headlineSmall = headlineSmall.copy(fontFamily = family),
    titleLarge = titleLarge.copy(fontFamily = family),
    titleMedium = titleMedium.copy(fontFamily = family),
    titleSmall = titleSmall.copy(fontFamily = family),
    bodyLarge = bodyLarge.copy(fontFamily = family),
    bodyMedium = bodyMedium.copy(fontFamily = family),
    bodySmall = bodySmall.copy(fontFamily = family),
    labelLarge = labelLarge.copy(fontFamily = family),
    labelMedium = labelMedium.copy(fontFamily = family),
    labelSmall = labelSmall.copy(fontFamily = family),
)

private fun FrameKitColors.toMaterial(): ColorScheme {
    val base = if (isDark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = accent,
        onPrimary = onAccent,
        secondary = accent,
        onSecondary = onAccent,
        background = background,
        onBackground = foreground,
        surface = surface,
        onSurface = foreground,
        surfaceVariant = raised,
        onSurfaceVariant = foregroundMuted,
        surfaceContainer = surface,
        surfaceContainerHigh = raised,
        surfaceContainerHighest = raised,
        error = error,
        outline = foregroundMuted,
    )
}
