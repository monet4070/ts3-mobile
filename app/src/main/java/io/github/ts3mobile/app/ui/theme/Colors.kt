package io.github.ts3mobile.app.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// Warm paper and terracotta, following TailSync's Canvas palette. No dynamic colors.
internal fun tailSyncColors(dark: Boolean) = if (dark) DarkColors else LightColors

private val LightColors =
    lightColorScheme(
        primary = Color(0xFFBD4F2C), onPrimary = Color(0xFFFFF7EF),
        primaryContainer = Color(0xFFF5E3D7), onPrimaryContainer = Color(0xFF9A4025),
        secondary = Color(0xFF5C5346), onSecondary = Color(0xFFFFF7EF),
        secondaryContainer = Color(0xFFEFE8D8), onSecondaryContainer = Color(0xFF4B4235),
        tertiary = Color(0xFF8D4F1A), onTertiary = Color(0xFFFFF7EF),
        tertiaryContainer = Color(0xFFF3E3CB), onTertiaryContainer = Color(0xFF754018),
        background = Color(0xFFF5F0E4), onBackground = Color(0xFF201B14),
        surface = Color(0xFFFFFDF5), onSurface = Color(0xFF201B14),
        surfaceVariant = Color(0xFFF1EBDD), onSurfaceVariant = Color(0xFF5C5346),
        outline = Color(0xFF847967), outlineVariant = Color(0xFFE2D9C3),
        surfaceTint = Color.Transparent,
        error = Color(0xFFAD3C33), onError = Color(0xFFFFF7EF),
        errorContainer = Color(0xFFF8E2DB), onErrorContainer = Color(0xFF8D2E27),
    )

private val DarkColors =
    darkColorScheme(
        primary = Color(0xFFF0946A), onPrimary = Color(0xFF27150C),
        primaryContainer = Color(0xFF4A3327), onPrimaryContainer = Color(0xFFF3B190),
        secondary = Color(0xFFB4A98F), onSecondary = Color(0xFF1C1915),
        secondaryContainer = Color(0xFF322C22), onSecondaryContainer = Color(0xFFEEE4D0),
        tertiary = Color(0xFFE2A26C), onTertiary = Color(0xFF38230E),
        tertiaryContainer = Color(0xFF493423), onTertiaryContainer = Color(0xFFEFC093),
        background = Color(0xFF1C1915), onBackground = Color(0xFFF2EBDB),
        surface = Color(0xFF28241D), onSurface = Color(0xFFF2EBDB),
        surfaceVariant = Color(0xFF322C22), onSurfaceVariant = Color(0xFFB4A98F),
        outline = Color(0xFF9D937F), outlineVariant = Color(0xFF524835),
        surfaceTint = Color.Transparent,
        error = Color(0xFFF19786), onError = Color(0xFF3E1710),
        errorContainer = Color(0xFF4C2B23), onErrorContainer = Color(0xFFF6B0A1),
    )
