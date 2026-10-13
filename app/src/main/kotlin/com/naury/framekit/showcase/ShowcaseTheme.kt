package com.naury.framekit.showcase

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** 샘플 앱 테마. 홈 화면에서 고른 다크·라이트를 앱 화면과 편집기에 함께 쓴다. */
@Composable
fun ShowcaseTheme(dark: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (dark) {
            darkColorScheme(
                primary = Brand,
                onPrimary = Color.White,
                background = Color(0xFF000000),
                onBackground = Color.White,
                surface = Color(0xFF1C1C1E),
                onSurface = Color.White,
                surfaceVariant = Color(0xFF2C2C2E),
                onSurfaceVariant = Color(0xFF8E8E93),
                outlineVariant = Color(0xFF38383A),
            )
        } else {
            lightColorScheme(
                primary = Brand,
                onPrimary = Color.White,
                background = Color(0xFFF2F2F7),
                onBackground = Color.Black,
                surface = Color.White,
                onSurface = Color.Black,
                surfaceVariant = Color(0xFFE5E5EA),
                onSurfaceVariant = Color(0xFF6C6C70),
                outlineVariant = Color(0xFFD1D1D6),
            )
        },
        content = content,
    )
}

private val Brand = Color(0xFF635BFF)
