package io.github.ts3mobile.app.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val LightColors =
    lightColorScheme(
        primary = Color(0xFF006D5B),
        onPrimary = Color.White,
        primaryContainer = Color(0xFF9CF2D7),
        onPrimaryContainer = Color(0xFF002019),
        secondary = Color(0xFF4E635C),
        secondaryContainer = Color(0xFFD1E8DF),
        tertiary = Color(0xFF765A00),
        tertiaryContainer = Color(0xFFFFDF8A),
        background = Color(0xFFF8FAF7),
        surface = Color(0xFFF8FAF7),
        surfaceVariant = Color(0xFFDCE5E0),
        error = Color(0xFFBA1A1A),
    )

private val DarkColors =
    darkColorScheme(
        primary = Color(0xFF80D5BC),
        onPrimary = Color(0xFF00382D),
        primaryContainer = Color(0xFF005143),
        onPrimaryContainer = Color(0xFF9CF2D7),
        secondary = Color(0xFFB5CCC3),
        secondaryContainer = Color(0xFF364B44),
        tertiary = Color(0xFFEAC247),
        tertiaryContainer = Color(0xFF594400),
        background = Color(0xFF101412),
        surface = Color(0xFF101412),
        surfaceVariant = Color(0xFF404944),
        error = Color(0xFFFFB4AB),
    )

@Composable
fun Ts3MobileTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val colors = if (dark) DarkColors else LightColors
    val view = LocalView.current

    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }

    MaterialTheme(
        colorScheme = colors,
        content = content,
    )
}
