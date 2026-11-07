package io.github.ts3mobile.app.identity

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import io.github.ts3mobile.app.ui.theme.Ts3MobileTheme
import io.github.ts3mobile.protocol.Ts3IdentitySummary
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class IdentityRestoreSectionTest {
    @get:Rule val rule = createComposeRule()

    @Test fun replacementDialogShowsBothIdentitiesAndRequiresRefreshAfterABusyConfirmation() {
        val current = Ts3IdentitySummary("current-synthetic-uid", 2, 1)
        val proposed = Ts3IdentitySummary("proposed-synthetic-uid", 55, 5)
        val preview = IdentityReplacementPreview(IdentitySnapshot(current, current, false, "fixture"), proposed, false)
        val state = mutableStateOf(IdentityRestoreState(IdentityRestoreStatus.PREVIEW, preview))
        val useState = mutableStateOf(IdentityUseState.CONNECTION)
        var confirms = 0
        var refreshes = 0
        rule.setContent {
            Ts3MobileTheme {
                IdentityRestoreSection(state.value, useState.value, true, false, {}, {}, { confirms++ }, { refreshes++ }, {}, {})
            }
        }
        rule.onNodeWithText("Confirm change").assertIsNotEnabled()
        rule.onNodeWithText("UID: current-synthetic-uid").assertIsDisplayed()
        rule.onNodeWithText("UID: proposed-synthetic-uid").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Server permissions belong to each identity.", substring = true).performScrollTo().assertIsDisplayed()
        rule.runOnIdle {
            useState.value = IdentityUseState.IDLE
            state.value = state.value.copy(status = IdentityRestoreStatus.FAILED, storeFailure = IdentityFailure.BUSY)
        }
        rule.onNodeWithText("Confirm change").assertIsNotEnabled()
        rule.onNodeWithText("Refresh comparison").performScrollTo().performClick()
        rule.runOnIdle {
            assertEquals(1, refreshes)
            state.value = IdentityRestoreState(IdentityRestoreStatus.PREVIEW, preview)
        }
        rule.onNodeWithText("Confirm change").performClick()
        rule.runOnIdle { assertEquals(1, confirms) }
    }

    @Test fun importingConsumesTheExistingPasswordAndRollbackNeedsAnAvailablePreviousIdentity() {
        var imports = 0
        rule.setContent {
            Ts3MobileTheme {
                IdentityRestoreSection(IdentityRestoreState(), IdentityUseState.IDLE, false, false, { secret ->
                    assertEquals("synthetic restore secret", String(secret))
                    secret.fill('\u0000')
                    imports++
                }, {}, {}, {}, {}, {})
            }
        }
        rule.onNodeWithText("Existing backup passphrase").performTextInput("synthetic restore secret")
        rule.onNodeWithText("Choose encrypted backup…").performClick()
        rule.runOnIdle { assertEquals(1, imports) }
        rule.onNodeWithText("Review local previous identity").assertIsNotEnabled()
    }
}
