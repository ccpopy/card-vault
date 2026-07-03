package com.cardvault.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8FA6FF),
    onPrimary = Color(0xFF10214D),
    primaryContainer = Color(0xFF32406B),
    onPrimaryContainer = Color(0xFFDDE3FF),
    secondary = Color(0xFFB8C2E8),
    background = Color(0xFF121318),
    surface = Color(0xFF1A1B22),
    surfaceVariant = Color(0xFF262833),
    onSurfaceVariant = Color(0xFFC5C8D6),
    error = Color(0xFFFFB4AB),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF4C5FB0),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDDE1FF),
    onPrimaryContainer = Color(0xFF00174B),
    secondary = Color(0xFF5A5D72),
    background = Color(0xFFF6F7FC),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFE2E4F0),
    onSurfaceVariant = Color(0xFF45474F),
)

@Composable
fun CardVaultTheme(themeMode: String = "system", content: @Composable () -> Unit) {
    val dark = when (themeMode) {
        "dark" -> true
        "light" -> false
        else -> isSystemInDarkTheme()
    }
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        content = content,
    )
}
