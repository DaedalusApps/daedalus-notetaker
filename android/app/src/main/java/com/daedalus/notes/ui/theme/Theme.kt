package com.daedalus.notes.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// Deep navy/blue palette
private val NavyBlue = Color(0xFF0D2B4E)
private val SteelBlue = Color(0xFF1565C0)
private val CornflowerBlue = Color(0xFF4A90D9)
private val LightSky = Color(0xFFBBDEFB)
private val DeepNavyDark = Color(0xFF0A1929)
private val NavySurface = Color(0xFF102A43)
private val NavyVariant = Color(0xFF1C3D6E)
private val OnNavy = Color(0xFFFFFFFF)
private val OnNavyVariant = Color(0xFFBBCEE4)
private val ErrorRed = Color(0xFFCF6679)
private val ErrorRedDark = Color(0xFFB00020)

private val DarkColorScheme = darkColorScheme(
    primary = CornflowerBlue,
    onPrimary = OnNavy,
    primaryContainer = NavyVariant,
    onPrimaryContainer = LightSky,
    secondary = LightSky,
    onSecondary = DeepNavyDark,
    secondaryContainer = NavySurface,
    onSecondaryContainer = LightSky,
    tertiary = Color(0xFF82B1FF),
    onTertiary = DeepNavyDark,
    background = DeepNavyDark,
    onBackground = Color(0xFFE1ECF7),
    surface = NavySurface,
    onSurface = Color(0xFFE1ECF7),
    surfaceVariant = Color(0xFF1A3550),
    onSurfaceVariant = OnNavyVariant,
    error = ErrorRed,
    onError = OnNavy,
    outline = Color(0xFF4A6780),
)

private val LightColorScheme = lightColorScheme(
    primary = SteelBlue,
    onPrimary = OnNavy,
    primaryContainer = LightSky,
    onPrimaryContainer = NavyBlue,
    secondary = NavyVariant,
    onSecondary = OnNavy,
    secondaryContainer = Color(0xFFDCEEFB),
    onSecondaryContainer = NavyBlue,
    tertiary = Color(0xFF1976D2),
    onTertiary = OnNavy,
    background = Color(0xFFF5F9FF),
    onBackground = Color(0xFF0D1B2A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF0D1B2A),
    surfaceVariant = Color(0xFFDCE8F5),
    onSurfaceVariant = Color(0xFF1C3D6E),
    error = ErrorRedDark,
    onError = OnNavy,
    outline = Color(0xFF6B8EAE),
)

@Composable
fun DaedalusTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.primary.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = DaedalusTypography,
        content = content
    )
}
