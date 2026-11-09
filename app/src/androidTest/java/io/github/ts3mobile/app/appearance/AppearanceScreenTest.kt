package io.github.ts3mobile.app.appearance

import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import io.github.ts3mobile.app.ui.theme.Ts3MobileTheme
import io.github.ts3mobile.app.ui.theme.tailSyncColors
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.Locale

class AppearanceScreenTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun defaultIgnoresDarkSystemAndExplicitSystemAndFixedModesUseTheirPalette() {
        val selected = mutableStateOf(ThemeMode.LIGHT)
        val system = mutableStateOf(true)
        var background = Color.Unspecified
        rule.setContent {
            Ts3MobileTheme(selected.value.isDark(system.value)) {
                val color = MaterialTheme.colorScheme.background
                SideEffect { background = color }
                AppearanceScreen(ThemePreferenceState(selected.value, ThemePreferenceStatus.SAVED), { selected.value = it }, {}, {}, {})
            }
        }
        rule.runOnIdle { assertEquals(tailSyncColors(false).background, background) }
        save("light-default-dark-system")
        rule.onNodeWithText("Follow system").performClick()
        rule.onNodeWithText("Follow system").assertIsSelected()
        rule.runOnIdle { assertEquals(tailSyncColors(true).background, background) }
        save("dark-system")
        rule.runOnIdle { system.value = false }
        rule.runOnIdle { assertEquals(tailSyncColors(false).background, background) }
        rule.onNodeWithText("Dark").performClick()
        rule.runOnIdle { assertEquals(tailSyncColors(true).background, background) }
        rule.onNodeWithText("Light").performClick()
        rule.runOnIdle { assertEquals(tailSyncColors(false).background, background) }
    }

    @Test fun chineseLargeTextErrorsExposeRetryResetAndCloseWithoutEnablingBusyChoices() {
        val state = mutableStateOf(ThemePreferenceState(ThemeMode.DARK, ThemePreferenceStatus.SAVING))
        var retry = 0
        var reset = 0
        var close = 0
        rule.setContent {
            val original = LocalContext.current
            val configuration = Configuration(LocalConfiguration.current).apply { setLocale(Locale.SIMPLIFIED_CHINESE) }
            CompositionLocalProvider(
                LocalContext provides original.createConfigurationContext(configuration),
                LocalConfiguration provides configuration,
                LocalDensity provides Density(LocalDensity.current.density, 1.8f),
            ) {
                Ts3MobileTheme(true) {
                    AppearanceScreen(state.value, {}, { retry++ }, { reset++ }, { close++ })
                }
            }
        }
        rule.onNodeWithText("深色").assertIsNotEnabled()
        rule.runOnIdle { state.value = state.value.copy(status = ThemePreferenceStatus.WRITE_FAILED) }
        rule.onNodeWithText("重试").performScrollTo().performClick()
        rule.onNodeWithText("重置外观为浅色").performScrollTo().performClick()
        rule.onNodeWithText("关闭").performScrollTo().assertIsDisplayed()
        save("dark-zh-large-error")
        rule.onNodeWithText("关闭").performClick()
        rule.runOnIdle { assertEquals(listOf(1, 1, 1), listOf(retry, reset, close)) }
    }

    private fun save(name: String) {
        val directory = File(rule.activity.getExternalFilesDir(null), "appearance-ui").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use {
            check(rule.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
        }
    }
}
