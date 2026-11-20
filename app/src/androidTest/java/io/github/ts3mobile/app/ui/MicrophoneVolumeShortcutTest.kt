package io.github.ts3mobile.app.ui

import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ts3mobile.app.service.MicrophoneMode
import io.github.ts3mobile.app.settings.MicrophonePreferenceStatus
import io.github.ts3mobile.audio.opus.MicrophoneSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class MicrophoneVolumeShortcutTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun draggingUpRaisesOnlyInputVolumeAndSettingsShowsTheSameValue() {
        val fixture = UiFixture(connected = true)
        val original = MicrophoneSettings(noiseSuppression = false, echoCancellation = false, gateThresholdDb = -30)
        fixture.state.value = fixture.state.value.copy(microphoneMode = MicrophoneMode.OFF, microphoneSettings = original)
        rule.setContent { fixture.Render() }
        openShortcut()
        rule.onNodeWithText("100%").assertIsDisplayed()
        slider().performTouchInput { swipe(center, Offset(center.x, 18.dp.toPx())) }
        val gain =
            rule.runOnIdle {
                val current = fixture.state.value
                val adjusted = current.microphoneSettings.volumePercent
                assertTrue("An upward swipe should increase input gain", adjusted > original.volumePercent)
                assertEquals(original.copy(volumePercent = adjusted), current.microphoneSettings)
                assertEquals(MicrophoneMode.OFF, current.microphoneMode)
                assertFalse(current.isCapturing)
                assertFalse(current.playbackMuted)
                assertTrue(fixture.transmission.none { it })
                adjusted
            }
        dismissShortcut()
        slider().assertDoesNotExist()
        rule.runOnIdle { assertTrue(fixture.microphonePreferenceCommits > 0) }
        rule.openAppSettings()
        rule.onNodeWithText("Microphone settings").performClick()
        rule.onNodeWithText("Input volume: $gain%").performScrollTo().assertIsDisplayed()
    }

    @Test fun narrowLargeTextChatKeepsTheVolumeAndVoiceControlsIndependent() {
        val fixture = UiFixture(connected = true)
        rule.setContent { Box(Modifier.width(320.dp)) { fixture.Render(dark = true, fontScale = 1.8f) } }
        rule.onNodeWithText("Chat").performClick()
        openShortcut()
        slider().performSemanticsAction(SemanticsActions.SetProgress) { it(25f) }
        rule.onNodeWithText("25%").assertIsDisplayed()
        dismissShortcut()
        slider().assertDoesNotExist()
        rule.onNodeWithContentDescription("Push to talk").assertIsDisplayed()
        rule.runOnIdle {
            assertEquals(25, fixture.state.value.microphoneSettings.volumePercent)
            assertEquals(MicrophoneMode.PUSH_TO_TALK, fixture.state.value.microphoneMode)
            assertTrue(fixture.transmission.none { it })
        }
    }

    @Test fun aReadFailureOffersRetryWithoutEditingAnUnknownVolume() {
        val fixture = UiFixture(connected = true)
        fixture.microphonePreferenceStatus.value = MicrophonePreferenceStatus.READ_FAILED
        rule.setContent { fixture.Render() }
        openShortcut()
        slider().assertDoesNotExist()
        rule.onNodeWithText("Could not load volume").assertIsDisplayed()
        rule.onNodeWithText("Retry").performClick()
        rule.runOnIdle { assertEquals(1, fixture.microphonePreferenceRetries) }
    }

    private fun openShortcut() = rule.onNodeWithContentDescription("Adjust microphone volume").assertIsDisplayed().performClick()

    private fun slider() = rule.onNodeWithContentDescription("Microphone input volume")

    private fun dismissShortcut() = InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
}
