package io.github.ts3mobile.app.ui

import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick

internal fun ComposeTestRule.openAppSettings(
    more: String = "More options",
    settings: String = "Settings",
) {
    onNodeWithContentDescription(more).performClick()
    onNodeWithText(settings).performClick()
}
