package io.github.ts3mobile.app.identity

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.github.ts3mobile.app.MainActivity
import io.github.ts3mobile.app.ui.openAppSettings
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class IdentityMenuTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test fun realMainMenuOpensThePrivateIdentityPageAndReturnsWithoutConnecting() {
        rule.openAppSettings()
        rule.onNodeWithText("Identity and backups").performScrollTo().performClick()
        try {
            rule.onNodeWithText("Identity and backups").assertIsDisplayed()
            val arrow = rule.onNodeWithContentDescription("Back")
            val initialBounds = arrow.fetchSemanticsNode().boundsInRoot
            rule.onNodeWithText("Refresh identity").performScrollTo()
            arrow.assertIsDisplayed()
            assertEquals(initialBounds, arrow.fetchSemanticsNode().boundsInRoot)
            arrow.performClick()
        } finally {
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().runOnMainSync {
                androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED)
                    .filterIsInstance<IdentityManagementActivity>().forEach { it.finish() }
            }
        }
        rule.onNodeWithText("Settings").assertIsDisplayed()
        rule.onNodeWithContentDescription("Back").performClick()
        rule.onNodeWithContentDescription("More options").assertIsDisplayed()
    }
}
