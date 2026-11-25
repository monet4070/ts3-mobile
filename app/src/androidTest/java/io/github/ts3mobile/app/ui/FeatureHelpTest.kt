package io.github.ts3mobile.app.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import io.github.ts3mobile.protocol.ChannelFileEntry
import io.github.ts3mobile.protocol.ChannelFileFailure
import io.github.ts3mobile.protocol.ChannelFileResult
import io.github.ts3mobile.protocol.ChannelFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.nio.file.Path

class FeatureHelpTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun startupShowsTheConnectionScreenAndOnlySettingsOffersHelp() {
        val fixture = UiFixture()
        rule.setContent { fixture.Render() }
        rule.onNodeWithText("Connect").assertIsDisplayed()
        rule.onNode(isDialog()).assertDoesNotExist()
        rule.onNodeWithText("Practice server").assertDoesNotExist()
        rule.onNodeWithContentDescription("More options").performClick()
        rule.onNodeWithText("Quick guide").assertDoesNotExist()
        rule.onNodeWithText("Feature help").assertDoesNotExist()
        click("Settings")
        rule.onNodeWithText("Quick guide").assertDoesNotExist()
        click("Feature help")
        rule.onNodeWithText("Connect to your server").assertIsDisplayed()
        rule.runOnIdle {
            assertEquals(0, fixture.connects)
            assertTrue(fixture.transmission.none { it })
        }
    }

    @Test fun helpIsAFlatListAndOneOfflineTopicDoesNotLeadToAnother() {
        val fixture = UiFixture()
        rule.setContent { fixture.Render(dark = true, fontScale = 1.8f) }
        rule.onNodeWithText("Connect to your server").assertDoesNotExist()
        rule.onNodeWithText("Feature help").assertDoesNotExist()
        openHelp()
        rule.onNodeWithText("More features").assertDoesNotExist()
        rule.onNodeWithText("Voice and floating PTT").assertDoesNotExist()
        rule.onNodeWithText("Accept or share an invite").assertIsDisplayed()
        rule.onNodeWithText("Check why voice cuts out").performScrollTo().assertIsDisplayed()
        click("Browse and save channel files")
        rule.onNodeWithText("Connect first to try this in the app.").assertIsDisplayed()
        rule.onNodeWithText("Next").assertDoesNotExist()
        rule.onNodeWithText("Go to the connection screen").assertDoesNotExist()
        click("Close tip")
        rule.onNodeWithText("Feature help").assertDoesNotExist()
        rule.onNodeWithText("Save an attachment from chat").assertDoesNotExist()
        rule.runOnIdle { assertEquals(0, fixture.connects) }
    }

    @Test fun helpUsesRealSettingsRowsAndDoesNotRepeatAfterReturning() {
        val fixture = UiFixture()
        rule.setContent { fixture.Render() }
        openHelp("Test your mic without going live")
        rule.onNodeWithText("Tap the highlighted row in Settings.").assertIsDisplayed()
        touch("Voice playback")
        rule.onNodeWithText("Stability first").assertDoesNotExist()
        touch("Microphone settings")
        rule.onNodeWithText("Test your mic without going live").assertIsDisplayed()
        rule.onNodeWithText("Back to guide").assertDoesNotExist()
        click("Close tip")
        rule.onNodeWithText("Test microphone").assertExists()
        rule.onNodeWithContentDescription("Back").performClick()
        rule.onNodeWithText("Settings").assertIsDisplayed()
        rule.onNodeWithText("Close tip").assertDoesNotExist()
        rule.onNodeWithContentDescription("Back").performClick()
        rule.onNodeWithText("Test your mic without going live").assertDoesNotExist()
        rule.runOnIdle {
            assertEquals(0, fixture.connects)
            assertEquals(0, fixture.notificationSettings)
            assertTrue(fixture.transmission.none { it })
        }
    }

    @Test fun closingSettingsSpotlightLeavesNativeSettingsOpen() {
        val fixture = UiFixture()
        rule.setContent { fixture.Render() }
        openHelp("Pick where incoming audio plays")
        rule.onNodeWithText("Tap the highlighted row in Settings.").assertIsDisplayed()
        click("Close tip")
        rule.onNodeWithText("Settings").assertIsDisplayed()
        click("Voice playback")
        rule.onNodeWithText("Stability first").assertExists()
        rule.onNodeWithText("Close tip").assertDoesNotExist()
    }

    @Test fun helpUsesTheRealChatTabAndReadOnlyFileList() {
        val fixture = UiFixture(connected = true)
        var downloads = 0
        val files =
            object : ChannelFiles {
                override suspend fun list(directory: String) = ChannelFileResult.Success(listOf(ChannelFileEntry("Example.txt", false, 42)))

                override suspend fun stageDownload(
                    remotePath: String,
                    privateDirectory: Path,
                ): ChannelFileResult<Path> {
                    downloads++
                    return ChannelFileResult.Failed(ChannelFileFailure.REJECTED)
                }
            }
        val activity = rule.activity
        rule.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides activity) {
                fixture.Render(openChannelFiles = { files })
            }
        }
        openHelp("Send a private message or poke")
        touch("Chat")
        rule.onNodeWithTag("chat_conversation_picker").assertExists()
        click("Close tip")
        openHelp("Browse and save channel files")
        rule.onNodeWithContentDescription("More options").performTouchInput { click() }
        touch("Channel files")
        rule.onNodeWithText("Browse and save channel files").assertIsDisplayed()
        click("Close tip")
        rule.onNodeWithText("Example.txt").assertIsDisplayed()
        rule.runOnIdle {
            assertEquals(0, downloads)
            assertTrue(fixture.sentBodies.isEmpty())
        }
    }

    private fun openHelp(topic: String? = null) {
        rule.openAppSettings()
        click("Feature help")
        if (topic != null) click(topic)
    }

    private fun click(text: String) {
        rule.onNodeWithText(text).performScrollTo().performClick()
    }

    private fun touch(text: String) {
        rule.onNodeWithText(text).performTouchInput { click() }
    }
}
