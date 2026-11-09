package io.github.ts3mobile.app.identity

import android.view.WindowManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import io.github.ts3mobile.app.Ts3MobileApplication
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class IdentityActivityTest {
    @get:Rule val rule = createAndroidComposeRule<IdentityManagementActivity>()

    @Test fun rotationDoesNotSavePasswordAndVisitingDoesNotGenerateOrReplaceIdentity() =
        runBlocking {
            val identities = (rule.activity.application as Ts3MobileApplication).identities
            val before = identities.snapshot()
            rule.waitUntil(
                5_000,
            ) { rule.onAllNodes(androidx.compose.ui.test.hasText("Reading identity summary…")).fetchSemanticsNodes().isEmpty() }
            rule.onNodeWithText("Backup passphrase").performScrollTo().performTextInput("synthetic rotation secret")
            rule.activityRule.scenario.onActivity {
                assertTrue(it.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
            }
            rule.activityRule.scenario.recreate()
            rule.onNodeWithText("Backup passphrase").performScrollTo().assertTextEquals("Backup passphrase", "")
            rule.onNodeWithText("Export encrypted backup…").performScrollTo().assertIsDisplayed()
            assertEquals(before, identities.snapshot())
        }
}
