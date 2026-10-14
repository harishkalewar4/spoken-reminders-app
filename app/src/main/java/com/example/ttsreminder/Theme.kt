package com.example.ttsreminder

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

private val LightColorScheme = lightColorScheme(
primary = Color(0xFF3F3D82), // Deep Indigo
onPrimary = Color(0xFFFFFFFF),
primaryContainer = Color(0xFFE2DFFF),
onPrimaryContainer = Color(0xFF00113B),
secondary = Color(0xFF755B00),
onSecondary = Color(0xFFFFFFFF),
secondaryContainer = Color(0xFFFFDEA3),
onSecondaryContainer = Color(0xFF241A00),
tertiary = Color(0xFF9C4146), // Coral / Warm Accent
onTertiary = Color(0xFFFFFFFF),
tertiaryContainer = Color(0xFFFFDADA),
onTertiaryContainer = Color(0xFF40000A),
background = Color(0xFFFCFBFF),
onBackground = Color(0xFF1B1B1F),
surface = Color(0xFFFCFBFF),
onSurface = Color(0xFF1B1B1F),
surfaceVariant = Color(0xFFE4E1EC),
onSurfaceVariant = Color(0xFF47464F)
)

private val DarkColorScheme = darkColorScheme(
primary = Color(0xFFBCB8FF),
onPrimary = Color(0xFF0E0851),
primaryContainer = Color(0xFF28256A),
onPrimaryContainer = Color(0xFFE2DFFF),
secondary = Color(0xFFE6C158),
onSecondary = Color(0xFF3D2E00),
secondaryContainer = Color(0xFF594400),
onSecondaryContainer = Color(0xFFFFDEA3),
tertiary = Color(0xFFFFB3B3),
onTertiary = Color(0xFF5F121B),
tertiaryContainer = Color(0xFF7E2A32),
onTertiaryContainer = Color(0xFFFFDADA),
background = Color(0xFF121316),
onBackground = Color(0xFFE4E1E6),
surface = Color(0xFF121316),
onSurface = Color(0xFFE4E1E6),
surfaceVariant = Color(0xFF47464F),
onSurfaceVariant = Color(0xFFC7C5D0)
)

@Composable
fun SpokenRemindersTheme(
darkTheme: Boolean = isSystemInDarkTheme(),
content: @Composable () -> Unit
) {
val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

val view = LocalView.current
if (!view.isInEditMode) {
    SideEffect {
        val window = (view.context as Activity).window
        window.statusBarColor = colorScheme.background.toArgb()
        WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
    }
}

MaterialTheme(
    colorScheme = colorScheme,
    content = content
)


}
