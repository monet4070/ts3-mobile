package io.github.ts3mobile.app.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

class SettingsNavigationTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun connectedMenuGroupsInvitesAndLeavesSettingsOutOfTheSessionActions() {
        val fixture = UiFixture(connected = true)
        val shares = mutableListOf<String>()
        rule.setContent {
            fixture.Render(onShareServerInvite = { shares += "server" }, onShareChannelInvite = { shares += "channel" })
        }
        openMenu()
        for (label in listOf("Share invite", "Away and status", "Find channels and users", "Server bookmarks", "Settings")) {
            rule.onNodeWithText(label).assertIsDisplayed()
        }
        for (label in listOf("Microphone settings", "Appearance", "Diagnostics", "Share server invite", "Share channel invite")) {
            rule.onNodeWithText(label).assertDoesNotExist()
        }
        rule.onNodeWithText("Share invite").performClick()
        rule.onNodeWithText("Share server invite").assertHasClickAction().performClick()
        openMenu()
        rule.onNodeWithText("Share invite").performClick()
        rule.onNodeWithText("Share channel invite").assertHasClickAction().performClick()
        rule.runOnIdle { assertEquals(listOf("server", "channel"), shares) }
    }

    @Test fun systemBackRestoresTheSessionTabAfterSettingsRecreation() {
        val fixture = UiFixture(connected = true)
        val restoration = StateRestorationTester(rule)
        restoration.setContent { fixture.Render() }
        rule.onNodeWithText("Users").performClick()
        rule.onNodeWithText("Sam").assertIsDisplayed()
        rule.openAppSettings()
        rule.onNodeWithText("Microphone settings").performClick()
        rule.onNodeWithText("Sam").assertDoesNotExist()
        restoration.emulateSavedInstanceStateRestore()
        rule.onNodeWithText("Microphone settings").assertIsDisplayed()
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.onNodeWithText("Settings").assertIsDisplayed()
        rule.onNodeWithContentDescription("Back").performClick()
        rule.onNodeWithText("Sam").assertIsDisplayed()
        rule.runOnIdle {
            assertEquals(0, fixture.connects)
            assertEquals(0, fixture.disconnects)
            assertTrue(fixture.transmission.none { it })
        }
    }

    @Test fun chineseLightSettingsShowsTheTrialEntryWhileTheServiceIsUnavailable() {
        val fixture = UiFixture()
        rule.setContent { fixture.Render(locale = "zh-CN") }
        rule.openAppSettings("更多选项", "设置")
        rule.onNodeWithText("TS3 Mobile").assertDoesNotExist()
        rule.onNodeWithText("麦克风设置").assertIsDisplayed()
        savePreview("settings-zh-light")
        rule.onNodeWithText("检查更新").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("查看是否有新版本").assertDoesNotExist()
        rule.onNodeWithText("麦克风设置").performScrollTo()
        rule.onNodeWithText("麦克风设置").assertHasClickAction().performClick()
        rule.onNodeWithText("本机试麦").assertIsDisplayed().assertIsNotEnabled()
        rule.onNodeWithText("请等待连接或音频设备切换完成").assertIsDisplayed()
        savePreview("microphone-zh-light")
        rule.runOnIdle { assertFalse(fixture.state.value.isCapturing) }
    }

    @Test fun englishDarkSettingsAtLargeTextKeepsEveryGroupReachable() {
        val fixture = UiFixture()
        rule.setContent { Box(Modifier.width(320.dp)) { fixture.Render(dark = true, fontScale = 1.8f) } }
        rule.openAppSettings()
        savePreview("settings-en-dark-large")
        for (label in listOf("Microphone settings", "Private message notifications", "Appearance", "Help and about", "Check for updates")) {
            rule.onNodeWithText(label).performScrollTo().assertIsDisplayed()
        }
        rule.onNodeWithContentDescription("Back").assertIsDisplayed().performClick()
        rule.onNodeWithContentDescription("More options").assertIsDisplayed()
    }

    private fun openMenu() = rule.onNodeWithContentDescription("More options").performClick()

    private fun savePreview(name: String) {
        val directory = File(rule.activity.getExternalFilesDir(null), "ui-previews").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use {
            check(rule.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }
}
