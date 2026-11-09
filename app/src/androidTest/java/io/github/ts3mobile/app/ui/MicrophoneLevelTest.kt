package io.github.ts3mobile.app.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.Lifecycle
import io.github.ts3mobile.app.service.MicrophoneMode
import io.github.ts3mobile.audio.opus.MicrophoneLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

class MicrophoneLevelTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun openSettings() {
        rule.onNodeWithContentDescription("More options").performClick()
        rule.onNodeWithText("Microphone settings").performClick()
    }

    private fun ptt() = rule.onNode(hasContentDescription("Push to talk") and hasAnyAncestor(isDialog()))

    private fun sample(
        gateOpen: Boolean = false,
        transmitting: Boolean = false,
    ) = MicrophoneLevel(1, 0, 0, System.nanoTime(), -35.0, true, gateOpen, transmitting, -40)

    @Test fun openingSettingsDoesNotCaptureAndExplainsNormalChannelSending() {
        val fixture = UiFixture(connected = true)
        rule.setContent { fixture.Render() }
        openSettings()
        rule.onNodeWithContentDescription("Microphone is off").assertIsDisplayed()
        rule.onNodeWithText("Hold to talk below sends your voice to the current channel.").assertIsDisplayed()
        ptt().assertIsDisplayed()
        rule.runOnIdle {
            assertFalse(fixture.state.value.isCapturing)
            assertTrue(fixture.transmission.none { it })
        }
    }

    @Test fun gestureUpAndCancellationReleaseNormalPtt() {
        val fixture = UiFixture(connected = true)
        rule.setContent { fixture.Render() }
        openSettings()
        ptt().performTouchInput {
            down(center)
            advanceEventTime(100)
            up()
        }
        rule.runOnIdle {
            assertTrue(fixture.transmission.contains(true))
            assertEquals(false, fixture.transmission.last())
        }
        ptt().performTouchInput {
            down(center)
            advanceEventTime(100)
            cancel()
        }
        rule.runOnIdle { assertEquals(false, fixture.transmission.last()) }
    }

    @Test fun dialogDisposalAndFocusLossReleaseAccessiblePtt() {
        val fixture = UiFixture(connected = true)
        rule.setContent { fixture.Render() }
        openSettings()
        ptt().performSemanticsAction(SemanticsActions.OnClick) { it() }
        rule.runOnIdle { assertEquals(true, fixture.transmission.last()) }
        var overlay: android.app.Dialog? = null
        try {
            rule.runOnUiThread { overlay = android.app.Dialog(rule.activity).apply { show() } }
            rule.waitUntil(2_000) { fixture.transmission.last() == false }
        } finally {
            rule.runOnUiThread { overlay?.dismiss() }
        }
        ptt().performSemanticsAction(SemanticsActions.OnClick) { it() }
        rule.onNodeWithText("Close").performClick()
        rule.runOnIdle { assertEquals(false, fixture.transmission.last()) }
    }

    @Test fun gateAndSendingComeFromSnapshotAndStopRemainsAvailable() {
        val fixture = UiFixture(connected = true)
        fixture.state.value = fixture.state.value.copy(microphoneMode = MicrophoneMode.VOLUME_GATE, isCapturing = true)
        rule.setContent { fixture.Render() }
        openSettings()
        rule.runOnIdle { fixture.microphoneLevels.value = sample() }
        rule.onNodeWithContentDescription("Volume gate closed · listening").assertIsDisplayed()
        rule.runOnIdle {
            fixture.microphoneLevels.value = sample(gateOpen = true, transmitting = true)
            fixture.state.value = fixture.state.value.copy(isTransmitting = true)
        }
        rule.onNodeWithContentDescription("Microphone active · sending").assertIsDisplayed()
        rule.onNodeWithText("Stop microphone").performScrollTo().performClick()
        rule.runOnIdle { assertEquals(MicrophoneMode.OFF, fixture.state.value.microphoneMode) }
    }

    @Test fun inactiveCaptureHidesAnyOldScalarAndDisconnectedSettingsOfferNoPtt() {
        val fixture = UiFixture()
        fixture.microphoneLevels.value = sample(gateOpen = true, transmitting = true)
        rule.setContent { fixture.Render() }
        openSettings()
        rule.onNodeWithContentDescription("Microphone is off").assertIsDisplayed()
        ptt().assertDoesNotExist()
        rule.runOnIdle { assertTrue(fixture.transmission.none { it }) }
    }

    @Test fun localLevelCollectionStopsWithActivityLifecycle() {
        val fixture = UiFixture(connected = true)
        rule.setContent { fixture.Render() }
        assertEquals(0, fixture.microphoneLevels.subscriptionCount.value)
        openSettings()
        rule.waitUntil(2_000) { fixture.microphoneLevels.subscriptionCount.value == 1 }
        ptt().performSemanticsAction(SemanticsActions.OnClick) { it() }
        rule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        rule.waitUntil(2_000) { fixture.microphoneLevels.subscriptionCount.value == 0 }
        rule.runOnIdle { assertEquals(false, fixture.transmission.last()) }
        rule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        rule.waitUntil(2_000) { fixture.microphoneLevels.subscriptionCount.value == 1 }
        rule.onNodeWithText("Close").performClick()
        rule.waitUntil(2_000) { fixture.microphoneLevels.subscriptionCount.value == 0 }
    }

    @Test fun scalarUpdatesOnlyRecomposeTheIndicatorWithinThePresentationTarget() {
        val fixture = UiFixture(connected = true)
        fixture.state.value = fixture.state.value.copy(isCapturing = true, isTransmitting = true)
        var parentCompositions = 0
        rule.setContent {
            SideEffect { parentCompositions++ }
            fixture.Render()
        }
        openSettings()
        rule.waitUntil(2_000) { fixture.microphoneLevels.subscriptionCount.value == 1 }
        val before = parentCompositions
        val changedAt = System.nanoTime()
        rule.runOnIdle { fixture.microphoneLevels.value = sample(gateOpen = true, transmitting = true) }
        rule.onNodeWithContentDescription("Microphone active · sending").assertIsDisplayed()
        val elapsed = System.nanoTime() - changedAt
        assertTrue("Synthetic publication to visible indicator exceeded 250ms", elapsed < 250_000_000)
        assertEquals(before, parentCompositions)
        File(rule.activity.getExternalFilesDir(null), "microphone-ui-latency.txt").writeText(
            "Synthetic Flow publication to visible indicator: ${elapsed / 1_000_000.0} ms; parent recompositions=0.\n",
        )
    }

    @Test fun chineseLargeTextShowsPttWarningAndCloseAndCanScrollToThreshold() {
        val fixture = UiFixture(connected = true)
        rule.setContent { fixture.Render(locale = "zh-CN", fontScale = 1.8f, dark = true) }
        rule.onNodeWithContentDescription("更多选项").performClick()
        rule.onNodeWithText("麦克风设置").performClick()
        rule.onNodeWithContentDescription("麦克风未开启").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("按住下方按钮讲话会将声音发送到当前频道。").performScrollTo().assertIsDisplayed()
        rule.onNode(hasContentDescription("按住说话") and hasAnyAncestor(isDialog())).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("关闭").assertIsDisplayed()
        val screenshot = File(rule.activity.getExternalFilesDir(null), "microphone-ui/tuning-zh-large.png")
        screenshot.parentFile?.mkdirs()
        screenshot.outputStream().use {
            check(rule.onNode(isDialog()).captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        rule.onNodeWithContentDescription("音量门控阈值").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("关闭").assertIsDisplayed()
    }

    @Test fun bufferedCaptureCannotClaimSendingWhenTheApplicationSendBoundaryIsDisabled() {
        val fixture = UiFixture(connected = true)
        fixture.state.value = fixture.state.value.copy(isCapturing = true, isTransmitting = false)
        fixture.microphoneLevels.value = sample(gateOpen = true, transmitting = true)
        rule.setContent { fixture.Render() }
        openSettings()
        rule.onNodeWithContentDescription("Microphone active · capturing").assertIsDisplayed()
        rule.onNodeWithContentDescription("Microphone active · sending").assertDoesNotExist()
    }
}
