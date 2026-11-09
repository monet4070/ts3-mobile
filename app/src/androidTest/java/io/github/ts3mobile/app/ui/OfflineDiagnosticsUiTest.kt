package io.github.ts3mobile.app.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.github.ts3mobile.app.service.AudioDiagnosticStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import java.io.File

class OfflineDiagnosticsUiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun retainedOfflineHistoryCanBeMarkedCopiedAndSavedWithoutConnecting() {
        val fixture = UiFixture()
        fixture.state.value =
            fixture.state.value.copy(
                audioDiagnostics = AudioDiagnosticStatus(savedSamples = 7, sessions = 1, lastPersistedTimeMs = 1_700_000_000_000),
            )
        rule.setContent { fixture.Render() }
        rule.onNodeWithContentDescription("More options").performClick()
        rule.onNodeWithText("Diagnostics").performScrollTo().performClick()
        rule.onNodeWithText("Save file").performScrollTo().performClick()
        rule.onNodeWithText("Copy").performScrollTo().performClick()
        rule.onNodeWithText("Mark an audio interruption").performScrollTo().performClick()
        rule.runOnIdle {
            assertEquals(1, fixture.diagnosticSaves)
            assertEquals(1, fixture.copies)
            assertEquals(1, fixture.audioMarks)
            assertEquals(0, fixture.connects)
            assertFalse(fixture.transmission.any { it })
        }
    }

    @Test fun freshInstallationDisablesMarkUntilAnAudioSessionExists() {
        val fixture = UiFixture()
        rule.setContent { fixture.Render() }
        rule.onNodeWithContentDescription("More options").performClick()
        rule.onNodeWithText("Diagnostics").performScrollTo().performClick()
        rule.onNodeWithText("Mark an audio interruption").performScrollTo().assertIsNotEnabled()
    }

    @Test fun chineseLargeTextRetainedHistoryShowsStorageFailureAndReachableActions() {
        val fixture = UiFixture()
        fixture.state.value =
            fixture.state.value.copy(
                audioDiagnostics =
                    AudioDiagnosticStatus(
                        savedSamples = 120, sessions = 3, failedWrites = 2, invalidRecords = 1, marks = 1,
                    ),
            )
        rule.setContent { fixture.Render(dark = true, locale = "zh-CN", fontScale = 1.8f) }
        rule.onNodeWithContentDescription("更多选项").performClick()
        rule.onNodeWithText("诊断").performScrollTo().performClick()
        rule.onNodeWithText("保存文件").performScrollTo().assertIsDisplayed()
        rule.onNode(isDialog()).captureToImage().asAndroidBitmap().let { bitmap ->
            File(rule.activity.filesDir, "offline-audio-zh-large.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
        }
        rule.onNodeWithText("记录不完整：写入失败 2 次，读取异常 1 条").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("标记刚才断音").performScrollTo().performClick()
        rule.runOnIdle { assertEquals(1, fixture.audioMarks) }
    }
}
