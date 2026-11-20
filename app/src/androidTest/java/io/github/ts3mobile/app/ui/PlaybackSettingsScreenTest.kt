package io.github.ts3mobile.app.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.ts3mobile.app.R
import io.github.ts3mobile.app.settings.PlaybackPreferenceState
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
}
