package io.github.ts3mobile.app.identity

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import io.github.ts3mobile.app.ui.theme.Ts3MobileTheme
import io.github.ts3mobile.protocol.Ts3IdentitySummary
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class IdentityManagementScreenTest {
    @get:Rule val rule = createComposeRule()

    @Test fun passwordInputsAreClearedBeforeExportAndVerifiedStateExplainsReadbackLimits() {
        val summary = Ts3IdentitySummary("synthetic-uid", 23, 4)
        val state = mutableStateOf(IdentityExportState(IdentitySnapshot(summary, summary, false, "fixture"), IdentityExportStatus.READY))
        var calls = 0
        rule.setContent {
            Ts3MobileTheme {
                IdentityManagementScreen(state.value, IdentityUseState.IDLE, { password, confirmation ->
                    assertArrayEquals("synthetic passphrase".toCharArray(), password)
                    assertArrayEquals(password, confirmation)
                    password.fill('\u0000')
                    confirmation.fill('\u0000')
                    calls++
                }, {}, {}, {})
            }
        }
        rule.onNodeWithText("Backup passphrase").performScrollTo().performTextInput("synthetic passphrase")
        rule.onNodeWithText("Confirm passphrase").performScrollTo().performTextInput("synthetic passphrase")
        rule.onNodeWithText("Export encrypted backup…").performScrollTo().performClick()
        rule.runOnIdle { assertEquals(1, calls) }
        rule.onNodeWithText("Backup passphrase").assertTextEquals("Backup passphrase", "")
        rule.runOnIdle { state.value = state.value.copy(status = IdentityExportStatus.VERIFIED, exported = summary) }
        rule.onNodeWithText("Backup verified.", substring = true).performScrollTo().assertIsDisplayed()
    }

    @Test fun connectionWorkAndUnreadableIdentityDisableExportEvenWhenUiHasASummary() {
        val summary = Ts3IdentitySummary("synthetic-uid", 0, 1)
        val state = mutableStateOf(IdentityExportState(IdentitySnapshot(summary, null, false, "fixture"), IdentityExportStatus.READY))
        val useState = mutableStateOf(IdentityUseState.CONNECTION)
        rule.setContent {
            Ts3MobileTheme { IdentityManagementScreen(state.value, useState.value, { _, _ -> error("must not export") }, {}, {}, {}) }
        }
        rule.onNodeWithText("Export encrypted backup…").performScrollTo().assertIsNotEnabled()
        rule.runOnIdle {
            useState.value = IdentityUseState.IDLE
            state.value = state.value.copy(snapshot = state.value.snapshot!!.copy(unreadable = true))
        }
        rule.onNodeWithText("Export encrypted backup…").assertIsNotEnabled()
    }

    @Test fun unreadableStoredIdentityIsNotMisreportedAsAnIdentityNeverCreated() {
        val state = IdentityExportState(IdentitySnapshot(null, null, true, "fixture"), IdentityExportStatus.READY)
        rule.setContent { Ts3MobileTheme { IdentityManagementScreen(state, IdentityUseState.IDLE, { _, _ -> }, {}, {}, {}) } }
        rule.onNodeWithText("Identity data cannot be read.", substring = true).assertIsDisplayed()
        rule.onNodeWithText("No identity has been created.", substring = true).assertDoesNotExist()
    }
}
