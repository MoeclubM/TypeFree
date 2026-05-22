package com.typefree.ime.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Premium color palette (sleek dark mode focused)
val PrimaryBlue = Color(0xFF2196F3)
val DarkBackground = Color(0xFF121212)
val DarkSurface = Color(0xFF1E1E1E)
val KeyBackground = Color(0xFF2E2E2E)
val SpecialKeyBackground = Color(0xFF3E3E3E)
val TextColor = Color(0xFFFFFFFF)
val TextColorSecondary = Color(0xFFB0B0B0)

val AiGradientStart = Color(0xFF8A2387)
val AiGradientCenter = Color(0xFFE94057)
val AiGradientEnd = Color(0xFFF27121)

private val DarkColorScheme = darkColorScheme(
    primary = PrimaryBlue,
    background = DarkBackground,
    surface = DarkSurface,
    onPrimary = Color.White,
    onBackground = TextColor,
    onSurface = TextColor
)

private val LightColorScheme = lightColorScheme(
    primary = PrimaryBlue,
    background = Color(0xFFF5F5F5),
    surface = Color.White,
    onPrimary = Color.White,
    onBackground = Color.Black,
    onSurface = Color.Black
)

@Composable
fun TypeFreeTheme(
    darkTheme: Boolean = true, // Force dark theme for typing experience
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    
    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
