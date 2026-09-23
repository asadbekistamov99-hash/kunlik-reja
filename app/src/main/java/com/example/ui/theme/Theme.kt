package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val JarvisColorScheme = darkColorScheme(
    primary = ArcCyan,
    onPrimary = Titanium950,
    primaryContainer = ArcDeep,
    onPrimaryContainer = Titanium100,
    secondary = ArcBlue,
    onSecondary = Color.White,
    secondaryContainer = Titanium700,
    onSecondaryContainer = Titanium100,
    tertiary = ReactorGold,
    onTertiary = Titanium950,
    background = Titanium900,
    onBackground = Titanium100,
    surface = Titanium850,
    onSurface = Titanium100,
    surfaceVariant = Titanium800,
    onSurfaceVariant = Titanium300,
    surfaceContainer = Titanium850,
    surfaceContainerHigh = Titanium800,
    surfaceContainerHighest = Titanium700,
    surfaceContainerLow = Titanium900,
    surfaceContainerLowest = Titanium950,
    outline = Titanium500,
    outlineVariant = Titanium700,
    error = AlertRed,
    onError = Color.White
)

/** Jarvis Ultra is dark-only by design (titanium + arc-reactor accents). */
@Composable
fun JarvisTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = JarvisColorScheme, typography = Typography, content = content)
}

@Composable
fun KunTartibiTheme(content: @Composable () -> Unit) = JarvisTheme(content)
