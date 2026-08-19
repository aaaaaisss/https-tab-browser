package com.example.httpsbrowser.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFFAEC6FF),
    secondary = Color(0xFFC3C7D5),
    surface = Color(0xFF111318),
    surfaceVariant = Color(0xFF2A2E36),
    background = Color(0xFF111318)
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF165DCC),
    secondary = Color(0xFF4E607A),
    surface = Color(0xFFFAF9FF),
    surfaceVariant = Color(0xFFE0E2EC),
    background = Color(0xFFFAF9FF)
)

@Composable
fun HttpsBrowserTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content
    )
}
