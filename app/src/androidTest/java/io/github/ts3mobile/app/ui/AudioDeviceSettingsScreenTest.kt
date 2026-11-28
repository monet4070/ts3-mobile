package io.github.ts3mobile.app.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.github.ts3mobile.app.R
import io.github.ts3mobile.app.service.TeamSpeakServiceState
import io.github.ts3mobile.audio.opus.AudioInputKind
import io.github.ts3mobile.audio.opus.AudioInputOption
import io.github.ts3mobile.audio.opus.AudioInputRouting
import io.github.ts3mobile.audio.opus.AudioRouteKind
import io.github.ts3mobile.audio.opus.AudioRouteOption
import io.github.ts3mobile.audio.opus.AudioRoutingErrorKind
import io.github.ts3mobile.audio.opus.AudioRoutingState
import io.github.ts3mobile.audio.opus.BuiltInMicrophonePosition
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class AudioDeviceSettingsScreenTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun devicesIsTheFirstVoiceActionAndOpensOffline() {
        rule.setContent { UiFixture(connected = false).Render() }
        rule.onNodeWithContentDescription(text(R.string.action_more)).performClick()
        rule.onNodeWithText(text(R.string.settings_title)).performClick()
        val title = text(R.string.audio_device_settings_title)
        rule.onNodeWithText(title).assertIsDisplayed().performClick()
        rule.onNodeWithText(text(R.string.audio_device_input_title)).assertIsDisplayed()
        rule.onNodeWithText(text(R.string.audio_device_input_auto)).assertIsSelected()
    }

    @Test fun inputAndOutputChangeIndependentlyAndRemovedInputRevertsToAutomatic() {
        val microphone = AudioInputOption(14, AudioInputKind.LE_AUDIO, "Fixture Buds")
        val output = AudioRouteOption(15, AudioRouteKind.BLUETOOTH, "Fixture Buds", leAudio = true)
        val state =
            mutableStateOf(
                AudioRoutingState(
                    routes = listOf(AudioRoutingState.SystemRoute, output),
                    input = AudioInputRouting(listOf(AudioInputRouting.Automatic, microphone)),
                ),
            )
        rule.setContent {
            AudioDeviceSettingsScreen(
                TeamSpeakServiceState(audioRouting = state.value),
                { route -> state.value = state.value.copy(input = state.value.input.copy(selectedId = route)) },
                { route -> state.value = state.value.copy(selectedRouteId = route) },
                {},
            )
        }
        val inputLabel =
            rule.activity.getString(R.string.audio_route_with_device, text(R.string.audio_device_bluetooth_le_mic), "Fixture Buds")
        val input = rule.onNodeWithText(inputLabel)
        val playback = rule.onNode(hasText(output.resolveLabel(rule.activity)) and hasClickAction())
        input.performScrollTo().performClick().assertIsSelected()
        playback.performScrollTo().assertIsNotSelected().performClick().assertIsSelected()
        input.performScrollTo().assertIsSelected()
        rule.runOnIdle { state.value = state.value.copy(input = AudioInputRouting(error = AudioRoutingErrorKind.DEVICE_DISCONNECTED)) }
        input.assertDoesNotExist()
        rule.onNodeWithText(text(R.string.audio_device_input_auto)).performScrollTo().assertIsSelected()
        rule.onNodeWithText(text(R.string.audio_device_input_disconnected)).performScrollTo().assertIsDisplayed()
        playback.performScrollTo().assertIsSelected()
    }

    @Test fun microphoneChoicesUseKnownPositionsAndNumberMatchingLabelsWithoutMergingRoutes() {
        val bottom = AudioInputOption(22, AudioInputKind.BUILTIN, builtInPosition = BuiltInMicrophonePosition.BOTTOM)
        val back = AudioInputOption(24, AudioInputKind.BUILTIN, builtInPosition = BuiltInMicrophonePosition.BACK)
        val unknownFirst = AudioInputOption(27, AudioInputKind.BUILTIN)
        val unknownSecond = AudioInputOption(29, AudioInputKind.BUILTIN)
        val usbFirst = AudioInputOption(32, AudioInputKind.USB, "Fixture Mic")
        val usbSecond = AudioInputOption(33, AudioInputKind.USB, "Fixture Mic")
        val numberedName = rule.activity.getString(R.string.audio_device_input_numbered, "Fixture Mic", 1)
        val usbNumbered = AudioInputOption(34, AudioInputKind.USB, numberedName)
        val choices = listOf(AudioInputRouting.Automatic, bottom, back, unknownFirst, unknownSecond, usbFirst, usbSecond, usbNumbered)
        val input = mutableStateOf(AudioInputRouting(choices))
        rule.setContent {
            AudioDeviceSettingsScreen(
                TeamSpeakServiceState(audioRouting = AudioRoutingState(input = input.value)),
                { route -> input.value = input.value.copy(selectedId = route) },
                {},
                {},
            )
        }
        val generic = text(R.string.audio_device_builtin_microphone)
        val usb = rule.activity.getString(R.string.audio_route_with_device, text(R.string.audio_route_usb), "Fixture Mic")
        val expected =
            listOf(
                text(R.string.audio_device_builtin_microphone_bottom) to bottom.id,
                text(R.string.audio_device_builtin_microphone_back) to back.id,
                rule.activity.getString(R.string.audio_device_input_numbered, generic, 1) to unknownFirst.id,
                rule.activity.getString(R.string.audio_device_input_numbered, generic, 2) to unknownSecond.id,
                rule.activity.getString(R.string.audio_device_input_numbered, usb, 2) to usbFirst.id,
                rule.activity.getString(R.string.audio_device_input_numbered, usb, 3) to usbSecond.id,
                rule.activity.getString(R.string.audio_device_input_numbered, usb, 1) to usbNumbered.id,
            )
        expected.forEach { (label, route) ->
            rule.onNodeWithText(label).performScrollTo().performClick().assertIsSelected()
            rule.runOnIdle { assertEquals(route, input.value.selectedId) }
        }
    }

    private fun text(resource: Int): String = rule.activity.getString(resource)

    @Test fun bluetoothCallIsExplicitAndUsesThePairedMicrophone() {
        val media = AudioRouteOption(15, AudioRouteKind.BLUETOOTH, "Fixture Buds")
        val call = AudioRouteOption(16, AudioRouteKind.BLUETOOTH, "Fixture Buds", bluetoothCall = true)
        val routing =
            mutableStateOf(
                AudioRoutingState(routes = listOf(AudioRoutingState.SystemRoute, media, call), selectedRouteId = media.id),
            )
        rule.setContent {
            AudioDeviceSettingsScreen(
                TeamSpeakServiceState(audioRouting = routing.value),
                {},
                { route -> routing.value = routing.value.copy(selectedRouteId = route) },
                {},
            )
        }
        val callChoice = rule.onNode(hasText(call.resolveLabel(rule.activity)) and hasClickAction())
        val mediaChoice = rule.onNode(hasText(media.resolveLabel(rule.activity)) and hasClickAction())
        rule.onNodeWithText(text(R.string.audio_device_input_auto)).assertIsSelected()
        callChoice.performScrollTo().assertIsNotSelected().performClick().assertIsSelected()
        rule.onNodeWithText(text(R.string.audio_device_input_auto)).assertDoesNotExist()
        rule.onNodeWithText(text(R.string.audio_device_bluetooth_call_mic)).performScrollTo().assertIsDisplayed()
        mediaChoice.performScrollTo().performClick().assertIsSelected()
        rule.onNodeWithText(text(R.string.audio_device_input_auto)).performScrollTo().assertIsSelected()
    }
}
