package com.banoku.oplus.autobrightness.ui.theme

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
    primary = SunGold,
    onPrimary = DarkNavy,
    primaryContainer = CardNavy,
    onPrimaryContainer = SunLight,
    secondary = SunAmber,
    onSecondary = DarkNavy,
    background = DarkNavy,
    onBackground = Color(0xFFF1F5F9),
    surface = SurfaceNavy,
    onSurface = Color(0xFFF1F5F9),
    surfaceVariant = CardNavy,
    onSurfaceVariant = Color(0xFFCBD5E1),
    outline = BorderNavy
)

private val LightColorScheme = lightColorScheme(
    primary = SunAmber,
    onPrimary = Color.White,
    primaryContainer = SunLight,
    onPrimaryContainer = Color(0xFF452B00),
    secondary = SunGold,
    onSecondary = DarkNavy,
    background = Color(0xFFF8FAFC),
    onBackground = Color(0xFF0F172A),
    surface = Color.White,
    onSurface = Color(0xFF0F172A),
    surfaceVariant = Color(0xFFEDF2F7),
    onSurfaceVariant = Color(0xFF475569),
    outline = Color(0xFFCBD5E1)
)

@Composable
fun AutoBrightnessGuardTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
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
        content = content
    )
}
