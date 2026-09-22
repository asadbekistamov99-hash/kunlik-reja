package com.example.ui.theme

import androidx.compose.ui.unit.dp
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

private val DarkColorScheme = darkColorScheme(
    primary = IndigoPrimaryDark,
    secondary = IndigoSecondaryDark,
    tertiary = AmberAccent,
    background = SurfaceBackgroundDark,
    surface = SurfaceCardDark,
    onPrimary = Color.Black,
    onSecondary = Color.Black,
    onBackground = Color(0xFFE2E2E6),
    onSurface = Color(0xFFE6EEFF),
    primaryContainer = Color(0xFF23375C),
    onPrimaryContainer = Color(0xFFDCE6FF),
    surfaceVariant = Color(0xFF20304A),
    onSurfaceVariant = Color(0xFFBCCAE0),
    outline = Color(0xFF61718C)
)

private val LightColorScheme = lightColorScheme(
    primary = IndigoPrimary,
    secondary = IndigoSecondary,
    tertiary = AmberAccent,
    background = SurfaceBackground,
    surface = SurfaceCard,
    onPrimary = Color.White,
    onSecondary = Color.White,
    onBackground = Color(0xFF1A1C24),
    onSurface = Color(0xFF1A1C24),
    primaryContainer = IndigoContainer,
    onPrimaryContainer = Color(0xFF20356F),
    surfaceVariant = Color(0xFFE8EDF5),
    onSurfaceVariant = Color(0xFF4D5D75),
    outline = Color(0xFF73839A)
)

@Composable
fun KunTartibiTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false, // Use our custom brand color scheme for consistency
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = androidx.compose.material3.Shapes(
            small = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
            medium = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
            large = androidx.compose.foundation.shape.RoundedCornerShape(28.dp)
        ),
        content = content
    )
}
