package io.github.ts3mobile.app.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import io.github.ts3mobile.app.R
import io.github.ts3mobile.app.history.ServerHistoryEntry
import io.github.ts3mobile.app.service.MicrophoneMode
import io.github.ts3mobile.app.service.UserMessage
import io.github.ts3mobile.protocol.ConnectionPhase
import io.github.ts3mobile.protocol.ConnectionStatus
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.io.File

class IntegratedScreenTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun historySelectionClearsAnotherServersPasswordAndRemovalRemainsWired() {
        val fixture = UiFixture()
        val entry = ServerHistoryEntry("saved.example.com", 10000)
        fixture.history.value = listOf(entry)
        fixture.form.value = fixture.form.value.copy(password = "fixture-only")
        rule.setContent { fixture.Render() }
        rule.onNodeWithText(entry.label).performScrollTo().performClick()
        rule.runOnIdle {
            assertEquals(entry.host, fixture.form.value.host)
            assertEquals(entry.port.toString(), fixture.form.value.port)
            assertEquals("", fixture.form.value.password)
            assertEquals("Alex", fixture.form.value.nickname)
        }
        rule.onNodeWithContentDescription(rule.activity.getString(R.string.action_remove_history_entry, entry.label))
            .performScrollTo().performClick()
        rule.runOnIdle { assertEquals(listOf(entry), fixture.removedHistory) }
        rule.onNodeWithText(entry.label).assertDoesNotExist()
    }

    @Test fun busyConnectionDisablesHistorySelectionAndRemoval() {
        val fixture = UiFixture()
        val entry = ServerHistoryEntry("saved.example.com", 9987)
        fixture.history.value = listOf(entry)
        fixture.state.value = fixture.state.value.copy(status = ConnectionStatus(ConnectionPhase.CONNECTING))
        rule.setContent { fixture.Render() }
        rule.onNodeWithText(entry.label).performScrollTo().assertIsNotEnabled()
        rule.onNodeWithContentDescription(rule.activity.getString(R.string.action_remove_history_entry, entry.label))
            .assertIsNotEnabled()
    }

    @Test fun failedHistoryStillShowsTheErrorAndConnectAction() {
        val fixture = UiFixture()
        fixture.historyError.value = true
        rule.setContent { fixture.Render() }
        rule.onNodeWithText(rule.activity.getString(R.string.history_remove_failed)).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Connect").performScrollTo().performClick()
        rule.runOnIdle { assertEquals(1, fixture.connects) }
    }

    @Test fun backgroundDialogRefreshesReleasesTransmissionAndPreservesAllActions() {
        val fixture = UiFixture(connected = true)
        rule.setContent { fixture.Render() }
        rule.onNodeWithContentDescription("Push to talk").performSemanticsAction(SemanticsActions.OnClick) { it() }
        rule.onNodeWithContentDescription(rule.activity.getString(R.string.background_runtime_title)).performClick()
        rule.runOnIdle {
            assertEquals(1, fixture.refreshes)
            assertEquals(listOf(true, false), fixture.transmission)
        }
        rule.onNode(isToggleable()).performScrollTo().performClick()
        rule.onNodeWithText(rule.activity.getString(R.string.background_open_battery_settings)).performScrollTo().performClick()
        rule.onNodeWithText(rule.activity.getString(R.string.background_open_app_settings)).performScrollTo().performClick()
        rule.onNodeWithText(rule.activity.getString(R.string.background_copy_diagnostics)).performScrollTo().performClick()
        rule.runOnIdle {
            assertEquals(listOf(true), fixture.keepCpuAwakeRequests)
            assertEquals(1, fixture.batterySettings)
            assertEquals(1, fixture.appSettings)
            assertEquals(1, fixture.copies)
        }
        rule.onNodeWithText(rule.activity.getString(R.string.action_close)).performClick()
        rule.onNodeWithText(rule.activity.getString(R.string.background_runtime_title)).assertDoesNotExist()
    }

    @Test fun deferredMicrophoneShowsTheRemotePausedState() {
        val fixture = UiFixture(connected = true)
        fixture.state.value =
            fixture.state.value.copy(
                microphoneMode = MicrophoneMode.CONTINUOUS,
                microphoneError = UserMessage.MicrophoneResumeInApp,
            )
        rule.setContent { fixture.Render() }
        rule.onNodeWithText(rule.activity.getString(R.string.microphone_state_deferred)).assertIsDisplayed()
        rule.onNodeWithText(rule.activity.getString(R.string.microphone_state_starting)).assertDoesNotExist()
    }

    @Test fun integratedHistoryChinesePreview() {
        val fixture = UiFixture()
        fixture.history.value = listOf(ServerHistoryEntry("saved.example.com", 9987))
        rule.setContent { fixture.Render(locale = "zh-CN") }
        rule.onNodeWithText("saved.example.com:9987").performScrollTo().assertIsDisplayed()
        val output = File(rule.activity.getExternalFilesDir(null), "ui-previews").apply { mkdirs() }
        File(output, "integrated-history-zh.png").outputStream().use {
            check(rule.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }
}
