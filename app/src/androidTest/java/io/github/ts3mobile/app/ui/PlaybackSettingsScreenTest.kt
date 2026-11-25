package io.github.ts3mobile.app.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.github.ts3mobile.app.R
import io.github.ts3mobile.app.service.ActualAudioDevices
import io.github.ts3mobile.app.service.TeamSpeakServiceState
import io.github.ts3mobile.app.settings.PlaybackPreferenceState
import io.github.ts3mobile.audio.opus.ActualAudioDevice
import io.github.ts3mobile.audio.opus.PlaybackStatus
import io.github.ts3mobile.audio.opus.ReceiveDelayProfile
import org.junit.Rule
import org.junit.Test

class PlaybackSettingsScreenTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun selectingAProfileUpdatesTheRadioChoiceAndShowsWhenItApplies() {
        val state = mutableStateOf(PlaybackPreferenceState(profile = ReceiveDelayProfile.BALANCED))
        rule.setContent {
            PlaybackSettingsScreen(state.value, { state.value = PlaybackPreferenceState(profile = it) }, {}, {})
        }
        rule.onNodeWithText(rule.activity.getString(R.string.playback_settings_lifetime)).assertIsDisplayed()
        rule.onNodeWithText(rule.activity.getString(R.string.playback_profile_balanced)).assertIsSelected()
        rule.onNodeWithText(rule.activity.getString(R.string.playback_profile_stability)).performClick().assertIsSelected()
    }

    @Test fun voiceSettingsOpensThePlaybackScreenWhileConnected() {
        val fixture = UiFixture(connected = true)
        rule.setContent { fixture.Render() }
        rule.openAppSettings()
        val title = rule.activity.getString(R.string.playback_settings_title)
        rule.onNodeWithText(title).performClick()
        rule.onNodeWithText(rule.activity.getString(R.string.playback_settings_lifetime)).assertIsDisplayed()
    }

    @Test fun actualDeviceDoesNotFallBackToSelectedRouteAndIdleMicDoesNotShowAStaleMuteState() {
        val session = mutableStateOf(TeamSpeakServiceState(playbackStatus = PlaybackStatus.BUFFERING))
        rule.setContent { PlaybackSettingsScreen(PlaybackPreferenceState(), {}, {}, {}, session.value) }
        reading(R.string.audio_device_selected_output, R.string.audio_route_system)
        reading(R.string.audio_device_actual_output, R.string.audio_device_unknown)
        reading(R.string.audio_device_actual_input, R.string.audio_device_not_recording)
        rule.runOnIdle {
            session.value =
                session.value.copy(
                    isCapturing = true,
                    playbackStatus = PlaybackStatus.PLAYING,
                    actualAudioDevices = ActualAudioDevices(ActualAudioDevice.BLUETOOTH_A2DP, true, ActualAudioDevice.BLUETOOTH_SCO, false),
                )
        }
        reading(R.string.audio_device_actual_output, R.string.audio_device_bluetooth_media)
        reading(R.string.audio_device_actual_input, R.string.audio_device_bluetooth_call)
        reading(R.string.audio_device_system_mute, R.string.audio_device_not_muted)
        rule.runOnIdle { session.value = session.value.copy(isCapturing = false, playbackMuted = true) }
        reading(R.string.audio_device_actual_output, R.string.audio_device_idle)
        reading(R.string.audio_device_system_mute, R.string.audio_device_not_recording)
    }

    private fun reading(
        label: Int,
        value: Int,
    ) {
        val activity = rule.activity
        rule.onNode(hasText(activity.getString(label)) and hasText(activity.getString(value))).performScrollTo().assertIsDisplayed()
    }
}
