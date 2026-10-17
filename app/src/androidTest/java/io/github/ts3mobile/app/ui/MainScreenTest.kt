package io.github.ts3mobile.app.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import io.github.ts3mobile.app.service.MicrophoneMode
import io.github.ts3mobile.app.service.audioControlKey
import io.github.ts3mobile.protocol.ConnectionPhase
import io.github.ts3mobile.protocol.ConnectionStatus
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.io.File

class MainScreenTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun invalidFormStillShowsValidationAndRetry() {
        val fixture = UiFixture()
        fixture.form.value = fixture.form.value.copy(host = "", port = "0", nickname = "x")
        rule.setContent { fixture.Render() }
        rule.onNodeWithText("Connect").performScrollTo().performClick()
        rule.onNodeWithText("Enter a server address").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Nickname must be 3–30 characters").performScrollTo().assertIsDisplayed()
        rule.runOnIdle { assertEquals(1, fixture.connects) }
    }

    @Test fun reconnectDisablesFieldsAndKeepsCancel() {
        val fixture = UiFixture()
        fixture.state.value = fixture.state.value.copy(status = ConnectionStatus(ConnectionPhase.RECONNECTING))
        rule.setContent { fixture.Render() }
        rule.onNodeWithText("Server address").assertIsNotEnabled()
        rule.onNodeWithText("Cancel reconnect").performScrollTo().performClick()
        rule.runOnIdle { assertEquals(1, fixture.disconnects) }
    }

    @Test fun channelGesturesAndPasswordJoinRemainAvailable() {
        val fixture = UiFixture(connected = true)
        rule.setContent { fixture.Render() }
        rule.onNodeWithText("Morgan").assertDoesNotExist()
        rule.onNodeWithText("Design studio").performClick()
        // A single tap waits for the combined-clickable double-tap window.
        rule.waitUntil(timeoutMillis = 2_000) {
            rule.onAllNodesWithText("Morgan").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("Morgan").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Design studio").performScrollTo().performTouchInput { doubleClick() }
        rule.runOnIdle { assertEquals(listOf(2 to ""), fixture.joins) }
        rule.onNodeWithText("Private room").performScrollTo().performTouchInput { doubleClick() }
        rule.onNodeWithText("Channel password").performTextInput("fixture-only")
        rule.onNodeWithText("Cancel").performClick()
        rule.onNodeWithText("Private room").performScrollTo().performTouchInput { doubleClick() }
        rule.onNodeWithText("Channel password").performTextInput("fixture-secret")
        rule.onNodeWithText("Join").performClick()
        rule.runOnIdle { assertEquals(listOf(2 to "", 3 to "fixture-secret"), fixture.joins) }
    }

    @Test fun participantMuteVolumeAndDiagnosticsCallbacksRemainWired() {
        val fixture = UiFixture(connected = true)
        rule.setContent { fixture.Render(dark = true) }
        rule.onNodeWithText("Users").performClick()
        rule.onNodeWithContentDescription("Mute Sam").performClick()
        rule.onNodeWithContentDescription("Unmute Sam").assertIsDisplayed()
        rule.onNodeWithContentDescription("Adjust volume for Sam").performClick()
        rule.onNodeWithText("100%").assertIsDisplayed()
        rule.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress))
            .performSemanticsAction(SemanticsActions.SetProgress) { it(150f) }
        rule.onNodeWithText("150%").assertIsDisplayed()
        rule.onNodeWithText("Diagnostics").performClick()
        rule.onNodeWithText("Copy").performClick()
        rule.runOnIdle {
            assertEquals(
                true,
                fixture.state.value.participantAudioSettings.getValue(fixture.state.value.snapshot.participants[1].audioControlKey()).muted,
            )
            assertEquals(1, fixture.copies)
            assertEquals(150, fixture.state.value.participantAudioSettings.values.single().volumePercent)
        }
    }

    @Test fun holdToTalkReleasesOnTouchCancelAndScreenRemoval() {
        val fixture = UiFixture(connected = true)
        rule.setContent { fixture.Render() }
        rule.onNodeWithContentDescription("Push to talk").performTouchInput {
            down(center)
            cancel()
        }
        rule.runOnIdle { assertEquals(listOf(true, false), fixture.transmission) }
        rule.onNodeWithContentDescription("Push to talk").performTouchInput { down(center) }
        rule.runOnIdle { fixture.state.value = fixture.state.value.copy(status = ConnectionStatus(ConnectionPhase.DISCONNECTED)) }
        rule.runOnIdle { assertEquals(false, fixture.transmission.last()) }
    }

    @Test fun largeTextModeMenuKeepsAllModesAvailable() {
        val fixture = UiFixture(connected = true)
        rule.setContent { fixture.Render(fontScale = 1.8f) }
        rule.onNodeWithText("Hold").performClick()
        rule.onNodeWithText("Always on").performClick()
        rule.runOnIdle { assertEquals(MicrophoneMode.CONTINUOUS, fixture.state.value.microphoneMode) }
    }

    @Test fun largeTextParticipantVolumeFitsWithoutClipping() {
        val fixture = UiFixture(connected = true)
        rule.setContent { fixture.Render(fontScale = 1.8f) }
        rule.onNodeWithText("Users").performClick()
        rule.onNodeWithContentDescription("Adjust volume for Sam").performClick()
        rule.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress))
            .performSemanticsAction(SemanticsActions.SetProgress) { it(200f) }
        val layouts = mutableListOf<TextLayoutResult>()
        rule.onNodeWithText("200%").assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        savePreview("participants-large-text")
        org.junit.Assert.assertTrue(
            layouts.joinToString { "size=${it.size}, width=${it.didOverflowWidth}, height=${it.didOverflowHeight}" },
            layouts.isNotEmpty() &&
                layouts.all { layout ->
                    !layout.didOverflowHeight &&
                        (0 until layout.layoutInput.text.length).all { offset ->
                            val glyph = layout.getBoundingBox(offset)
                            glyph.left >= 0 && glyph.right <= layout.size.width
                        }
                },
        )
    }

    @Test fun connectionLightPreview() = preview("connection-light", connected = false)

    @Test fun connectionDarkPreview() = preview("connection-dark", connected = false, dark = true)

    @Test fun sessionLightPreview() = preview("session-light", connected = true)

    @Test fun sessionDarkPreview() = preview("session-dark", connected = true, dark = true)

    @Test fun chineseSessionPreview() = preview("session-zh", connected = true, locale = "zh-CN")

    @Test fun chineseConnectionPreview() = preview("connection-zh", connected = false, locale = "zh-CN")

    @Test fun largeTextConnectionPreview() = preview("connection-large-text", connected = false, locale = "zh-CN", fontScale = 1.8f)

    @Test fun largeTextSessionPreview() = preview("session-large-text", connected = true, fontScale = 1.8f)

    private fun preview(
        name: String,
        connected: Boolean,
        dark: Boolean = false,
        locale: String = "en",
        fontScale: Float = 1f,
    ) {
        val fixture = UiFixture(connected)
        rule.setContent { fixture.Render(dark, locale, fontScale) }
        rule.waitForIdle()
        savePreview(name)
        if (!connected && fontScale > 1f) {
            rule.onNodeWithText("连接").performScrollTo().assertIsDisplayed()
        }
    }

    private fun savePreview(name: String) {
        val output = File(rule.activity.getExternalFilesDir(null), "ui-previews").apply { mkdirs() }
        File(output, "$name.png").outputStream().use {
            check(rule.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }
}
