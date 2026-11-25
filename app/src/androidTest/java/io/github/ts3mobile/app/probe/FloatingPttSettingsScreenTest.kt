package io.github.ts3mobile.app.probe

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.github.ts3mobile.app.R
import io.github.ts3mobile.app.ui.theme.Ts3MobileTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class FloatingPttSettingsScreenTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun showingTheOverlayRequiresPermissionAndAChannel() {
        val permission = mutableStateOf(false)
        val connected = mutableStateOf(false)
        var permissionVisits = 0
        var shows = 0
        rule.setContent {
            Ts3MobileTheme {
                FloatingPttSettingsScreen(permission.value, connected.value, {}, { permissionVisits++ }, { shows++ }, false, {})
            }
        }
        val show = rule.onNodeWithText(rule.activity.getString(R.string.notification_show_floating_ptt))
        show.assertIsNotEnabled()
        rule.onNodeWithText(rule.activity.getString(R.string.ptt_overlay_permission)).performScrollTo().performClick()
        rule.runOnIdle {
            assertEquals(1, permissionVisits)
            permission.value = true
        }
        show.performScrollTo().assertIsNotEnabled()
        rule.runOnIdle { connected.value = true }
        show.assertIsEnabled().performClick()
        rule.runOnIdle { assertEquals(1, shows) }
    }

    @Test fun autoShowIsIndependentOfTheManualButtonAndDoesNotRequestPermissions() {
        val autoShow = mutableStateOf(false)
        rule.setContent {
            Ts3MobileTheme {
                FloatingPttSettingsScreen(
                    overlayAllowed = false,
                    channelReady = false,
                    onBack = {},
                    onPermissionSettings = { error("must not request permissions") },
                    onShow = { error("must not show while editing the preference") },
                    autoShow = autoShow.value,
                    onAutoShowChanged = { autoShow.value = it },
                )
            }
        }
        val toggle = rule.onNodeWithText(rule.activity.getString(R.string.ptt_auto_show))
        toggle.assertIsOff().performClick()
        toggle.assertIsOn()
        toggle.performClick()
        toggle.assertIsOff()
    }
}
