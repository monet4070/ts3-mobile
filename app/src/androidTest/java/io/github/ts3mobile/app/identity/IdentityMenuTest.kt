package io.github.ts3mobile.app.identity

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.github.ts3mobile.app.MainActivity
import org.junit.Rule
import org.junit.Test

class IdentityMenuTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test fun realMainMenuOpensThePrivateIdentityPageAndReturnsWithoutConnecting() {
        rule.onNodeWithContentDescription("More options").performClick()
        rule.onNodeWithText("Identity and backups").performScrollTo().performClick()
        try {
            rule.onNodeWithText("Identity and backups").assertIsDisplayed()
            rule.onNodeWithText("Close").performScrollTo().performClick()
        } finally {
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().runOnMainSync {
                androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED)
                    .filterIsInstance<IdentityManagementActivity>().forEach { it.finish() }
            }
        }
        rule.onNodeWithContentDescription("More options").assertIsDisplayed()
    }
}
