package com.naury.framekit.showcase

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

@Composable
fun ShowcaseTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFF635BFF),
            onPrimary = Color.White,
            background = Color(0xFF0D0D0E),
            onBackground = Color.White,
            surface = Color(0xFF171719),
            onSurface = Color.White,
            surfaceVariant = Color(0xFF242428),
            onSurfaceVariant = Color(0xFFA1A1AA),
        ),
        content = content,
    )
}
