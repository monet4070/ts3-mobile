package io.github.ts3mobile.app.ui

import android.Manifest
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ts3mobile.app.MainActivity
import io.github.ts3mobile.app.R
import io.github.ts3mobile.app.service.MicrophoneMode
import io.github.ts3mobile.app.service.withOfflineConnectedSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

class MicrophoneTrialActivityTest {
    @get:Rule val rule = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun disconnectedActivityCanStartAndStopLocalMeterWithoutTurningOffVoiceMode() {
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        ActivityScenario.launch(MainActivity::class.java).use {
            openMicrophoneSettings()
            openTrial()
            val start = context.getString(R.string.microphone_trial_start)
            val stop = context.getString(R.string.microphone_trial_stop)
            waitDisplayed(start, mustBeEnabled = true)
            rule.onNodeWithText(context.getString(R.string.microphone_trial_turn_off_voice)).assertDoesNotExist()
            rule.onNodeWithText(stop).assertDoesNotExist()
            rule.onNodeWithText(start).performClick()
            waitDisplayed(stop)
            rule.onNodeWithText(stop).performClick()
            waitDisplayed(start)
        }
    }

    @Test fun captureStartsOnlyOnRequestAndCannotResumeItselfAfterBackgrounding() {
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val entry = context.getString(R.string.microphone_trial_title)
            val start = context.getString(R.string.microphone_trial_start)
            val stop = context.getString(R.string.microphone_trial_stop)
            val turnOff = context.getString(R.string.microphone_trial_turn_off_voice)
            openMicrophoneSettings()
            instrumentation.withOfflineConnectedSession { service, _ ->
                rule.onNodeWithText(entry).performScrollTo().performClick()
                waitDisplayed(turnOff, mustBeEnabled = true)
                rule.onNodeWithText(start).assertDoesNotExist()
                rule.onNodeWithText(turnOff).performClick()
                waitDisplayed(start, mustBeEnabled = true)
                rule.onNodeWithText(stop).assertDoesNotExist()
                rule.onNodeWithText(start).performClick()
                waitDisplayed(stop)
                assertFalse(service.mutableState.value.isTransmitting)
                scenario.moveToState(Lifecycle.State.STARTED)
                scenario.moveToState(Lifecycle.State.RESUMED)
                waitDisplayed(start)
                rule.onNodeWithText(stop).assertDoesNotExist()
                rule.onNodeWithText(start).performClick()
                waitDisplayed(stop)
                rule.onNodeWithText(context.getString(R.string.action_close)).performClick()
                rule.onNode(isDialog()).assertDoesNotExist()
                assertEquals(MicrophoneMode.OFF, service.mutableState.value.microphoneMode)
                rule.onNodeWithText(entry).performScrollTo().performClick()
                waitDisplayed(start)
            }
        }
    }

    private fun openMicrophoneSettings() {
        rule.openAppSettings(context.getString(R.string.action_more), context.getString(R.string.settings_title))
        rule.onNodeWithText(context.getString(R.string.microphone_settings)).performClick()
    }

    private fun openTrial() {
        val entry = context.getString(R.string.microphone_trial_title)
        waitDisplayed(entry, mustBeEnabled = true)
        rule.onNodeWithText(entry).performScrollTo().performClick()
    }

    private fun waitDisplayed(
        text: String,
        mustBeEnabled: Boolean = false,
    ) {
        rule.waitUntil(5_000) {
            runCatching {
                val node = rule.onNodeWithText(text).assertIsDisplayed()
                if (mustBeEnabled) node.assertIsEnabled()
            }.isSuccess
        }
    }
}
