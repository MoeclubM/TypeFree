package com.typefree.ime.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val LightColors = lightColorScheme(
    primary = Color(0xFF006A60),
    onPrimary = Color.White,
    secondary = Color(0xFF675E00),
    tertiary = Color(0xFF7D5260),
    surface = Color(0xFFFFFBFF),
    surfaceVariant = Color(0xFFE1E3DF)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF4FD8C7),
    onPrimary = Color(0xFF003731),
    secondary = Color(0xFFD6C65A),
    tertiary = Color(0xFFEFB8C8),
    surface = Color(0xFF111412),
    surfaceVariant = Color(0xFF424844)
)

@Composable
fun TypeFreeMaterialTheme(content: @Composable () -> Unit) {
    val darkTheme = isSystemInDarkTheme()
    val context = LocalContext.current
    val colorScheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && darkTheme -> dynamicDarkColorScheme(context)
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> dynamicLightColorScheme(context)
        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = MaterialTheme.typography,
        content = content
    )
}
