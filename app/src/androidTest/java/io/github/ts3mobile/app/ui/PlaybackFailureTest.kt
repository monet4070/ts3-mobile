package io.github.ts3mobile.app.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import io.github.ts3mobile.audio.opus.PlaybackStatus
import io.github.ts3mobile.protocol.ConnectionPhase
import io.github.ts3mobile.protocol.ConnectionStatus
import org.junit.Rule
import org.junit.Test

class PlaybackFailureTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun failedPlaybackHasVisibleGuidanceAndRecoveryClearsIt() {
        val fixture = UiFixture(connected = true)
        fixture.state.value = fixture.state.value.copy(playbackStatus = PlaybackStatus.FAILED)
        rule.setContent { fixture.Render() }
        val message = "Audio playback stopped. Try changing the audio output or reconnecting."
        rule.onNodeWithText(message).assertIsDisplayed()
        rule.runOnIdle { fixture.state.value = fixture.state.value.copy(playbackStatus = PlaybackStatus.PLAYING) }
        rule.onNodeWithText(message).assertDoesNotExist()
        rule.runOnIdle {
            fixture.state.value =
                fixture.state.value.copy(
                    status = ConnectionStatus(ConnectionPhase.DISCONNECTING),
                    playbackStatus = PlaybackStatus.FAILED,
                )
        }
        rule.onNodeWithText(message).assertDoesNotExist()
    }
}
