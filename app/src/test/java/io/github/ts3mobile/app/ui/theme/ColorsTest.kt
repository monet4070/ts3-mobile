package io.github.ts3mobile.app.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min

class ColorsTest {
    @Test fun creamPaletteUsesWarmSurfacesAndTerracottaAccents() {
        val scheme = tailSyncColors(dark = false)
        assertTrue(scheme.background.red > scheme.background.green)
        assertTrue(scheme.background.green > scheme.background.blue)
        assertTrue(scheme.surface.red > scheme.surface.blue)
        assertTrue(scheme.primary.red > scheme.primary.green && scheme.primary.green > scheme.primary.blue)
    }

    @Test fun lightAndDarkTextPairsHaveReadableContrast() {
        listOf(false, true).forEach { dark ->
            val scheme = tailSyncColors(dark)
            listOf(
                scheme.onSurface to scheme.surface,
                scheme.onBackground to scheme.background,
                scheme.onSurfaceVariant to scheme.surface,
                scheme.onSurfaceVariant to scheme.surfaceVariant,
                scheme.onPrimary to scheme.primary,
                scheme.onPrimaryContainer to scheme.primaryContainer,
                scheme.onErrorContainer to scheme.errorContainer,
            ).forEach { (text, background) ->
                assertTrue("Readable text contrast in dark=$dark", contrast(text, background) >= 4.5f)
            }
        }
    }

    private fun contrast(
        first: Color,
        second: Color,
    ): Float {
        val a = first.luminance()
        val b = second.luminance()
        return (max(a, b) + 0.05f) / (min(a, b) + 0.05f)
    }
}
