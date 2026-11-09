package io.github.ts3mobile.app.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import io.github.ts3mobile.app.service.MicrophoneMode
import io.github.ts3mobile.app.settings.MicrophonePreferenceStatus
import io.github.ts3mobile.audio.opus.EchoCancellationState
import io.github.ts3mobile.audio.opus.MicrophoneSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

class MicrophoneSettingsTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun controlsApplyGainSuppressionEchoAndThresholdWithoutStartingCapture() {
        val fixture = UiFixture(connected = true)
        rule.setContent { fixture.Render() }
        openSettings()
        rule.onNodeWithContentDescription("Microphone input volume").performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(150f) }
        rule.onNodeWithText("Input volume: 150%").assertIsDisplayed()
        rule.onNodeWithContentDescription("RNNoise noise suppression").performScrollTo().performClick()
        rule.onNodeWithContentDescription("Device echo cancellation").performScrollTo().performClick()
        rule.onNodeWithContentDescription("Volume gate threshold").performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(-35f) }
        rule.onNodeWithText("Volume gate threshold: -35 dBFS").assertIsDisplayed()
        rule.onNodeWithText("Close").assertIsDisplayed().performClick()
        rule.runOnIdle {
            val settings = fixture.state.value.microphoneSettings
            assertEquals(150, settings.volumePercent)
            assertEquals(-35, settings.gateThresholdDb)
            assertFalse(settings.noiseSuppression)
            assertFalse(settings.echoCancellation)
            assertFalse(fixture.state.value.isCapturing)
            assertTrue(fixture.transmission.none { it })
        }
    }

    @Test fun gateModeShowsListeningThenSendingAndChatKeepsStopAction() {
        val fixture = UiFixture(connected = true)
        rule.setContent { fixture.Render(fontScale = 1.8f) }
        rule.onNodeWithText("Hold").performClick()
        rule.onNodeWithText("Gate").performClick()
        rule.runOnIdle {
            assertEquals(MicrophoneMode.VOLUME_GATE, fixture.state.value.microphoneMode)
            fixture.state.value = fixture.state.value.copy(isCapturing = true)
        }
        rule.onNodeWithText("Listening · below gate threshold").assertIsDisplayed()
        rule.runOnIdle { fixture.state.value = fixture.state.value.copy(isTransmitting = true) }
        rule.onNodeWithText("Talking").assertIsDisplayed()
        rule.onNodeWithText("Chat").performClick()
        rule.runOnIdle { fixture.state.value = fixture.state.value.copy(isTransmitting = false) }
        rule.onNodeWithText("Listening · below gate threshold").assertIsDisplayed()
        rule.onNodeWithContentDescription("Stop microphone").assertIsDisplayed().performClick()
        rule.runOnIdle { assertEquals(MicrophoneMode.OFF, fixture.state.value.microphoneMode) }
    }

    @Test fun chineseLargeTextSettingsRemainScrollableAndCloseVisible() {
        val fixture = UiFixture(connected = true)
        fixture.state.value = fixture.state.value.copy(echoCancellationState = EchoCancellationState.UNAVAILABLE)
        rule.setContent { fixture.Render(fontScale = 1.8f, locale = "zh-CN") }
        rule.onNodeWithContentDescription("更多选项").performClick()
        rule.onNodeWithText("麦克风设置").performClick()
        rule.onNodeWithContentDescription("麦克风输入音量").performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(200f) }
        rule.onNodeWithText("输入音量：200%").assertIsDisplayed()
        saveDialog("microphone-settings-zh-large-top")
        rule.onNodeWithText("当前设备未向本次采集提供回声消除。").performScrollTo().assertIsDisplayed()
        rule.onNodeWithContentDescription("音量门控阈值").performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(-60f) }
        rule.onNodeWithText("音量门控阈值：-60 dBFS").assertIsDisplayed()
        rule.onNodeWithText("关闭").assertIsDisplayed()
        saveDialog("microphone-settings-zh-large-bottom")
        rule.onNodeWithText("关闭").performClick()
    }

    @Test fun echoFailureIsVisibleAndSettingsCanBeEditedWhileDisconnected() {
        val fixture = UiFixture()
        fixture.state.value = fixture.state.value.copy(echoCancellationState = EchoCancellationState.FAILED)
        rule.setContent { fixture.Render() }
        openSettings()
        rule.onNodeWithText("The device could not apply echo cancellation. Capture can continue; try headphones.")
            .performScrollTo().assertIsDisplayed()
        rule.onNodeWithContentDescription("Volume gate threshold").performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(-15f) }
        rule.runOnIdle {
            assertEquals(-15, fixture.state.value.microphoneSettings.gateThresholdDb)
            assertFalse(fixture.state.value.isCapturing)
        }
    }

    @Test fun failedSaveIsVisibleAndRetryDoesNotPretendTheSettingsAreSaved() {
        val fixture = UiFixture()
        fixture.microphonePreferenceStatus.value = MicrophonePreferenceStatus.WRITE_FAILED
        rule.setContent { fixture.Render() }
        openSettings()
        rule.onNodeWithText("Tuning could not be saved. Changes remain active for this service; retry to keep them.")
            .assertIsDisplayed()
        rule.onNodeWithText("Tuning saved on this device").assertDoesNotExist()
        rule.onNodeWithText("Retry saving or loading").performScrollTo().performClick()
        rule.runOnIdle { assertEquals(1, fixture.microphonePreferenceRetries) }
        rule.onNodeWithText("Tuning saved on this device").assertDoesNotExist()
    }

    @Test fun restoringDefaultsAndClosingCommitsWithoutStartingCapture() {
        val fixture = UiFixture()
        fixture.state.value = fixture.state.value.copy(microphoneSettings = MicrophoneSettings(170, false, false, -20))
        rule.setContent { fixture.Render() }
        openSettings()
        rule.onNodeWithText("Restore tuning defaults").performScrollTo().performClick()
        rule.runOnIdle { assertEquals(MicrophoneSettings(), fixture.state.value.microphoneSettings) }
        rule.onNodeWithText("Close").performClick()
        rule.runOnIdle {
            assertTrue(fixture.microphonePreferenceCommits > 0)
            assertFalse(fixture.state.value.isCapturing)
            assertTrue(fixture.transmission.none { it })
        }
    }

    private fun openSettings() {
        rule.onNodeWithContentDescription("More options").performClick()
        rule.onNodeWithText("Microphone settings").performClick()
    }

    private fun saveDialog(name: String) {
        val bitmap = rule.onNode(isDialog()).captureToImage().asAndroidBitmap()
        File(rule.activity.filesDir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
