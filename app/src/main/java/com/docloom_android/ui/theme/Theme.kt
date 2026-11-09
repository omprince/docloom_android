package com.docloom_android.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = DarkAccent,
    onPrimary = Color.White,
    secondary = DarkAccentLight,
    onSecondary = DarkBackground,
    background = DarkBackground,
    onBackground = DarkForeground,
    surface = DarkBackground,
    onSurface = DarkForeground,
    surfaceVariant = DarkElevated,
    onSurfaceVariant = DarkMuted,
    surfaceContainer = DarkElevated,
    surfaceContainerLow = DarkElevated,
    surfaceContainerHigh = Color(0xFF161A33),
    outline = Color(0x33FFFFFF),
    outlineVariant = DarkBorder,
    error = ErrorRed,
)

private val LightColors = lightColorScheme(
    primary = LightAccent,
    onPrimary = Color.White,
    secondary = LightAccentLight,
    onSecondary = Color.White,
    background = LightBackground,
    onBackground = LightForeground,
    surface = LightBackground,
    onSurface = LightForeground,
    surfaceVariant = LightElevated,
    onSurfaceVariant = LightMuted,
    surfaceContainer = LightElevated,
    surfaceContainerLow = LightElevated,
    surfaceContainerHigh = Color(0xFFEDEEF6),
    outline = Color(0x4014162B),
    outlineVariant = LightBorder,
    error = Color(0xFFDC2626),
)

/**
 * DocLoom theme. [theme] is the account preference saved on the server:
 * "dark" (website default), "light" or "system".
 */
@Composable
fun DocloomTheme(
    theme: String = "dark",
    content: @Composable () -> Unit,
) {
    val dark = when (theme) {
        "light" -> false
        "system" -> isSystemInDarkTheme()
        else -> true
    }
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        typography = Typography,
        content = content,
    )
}
