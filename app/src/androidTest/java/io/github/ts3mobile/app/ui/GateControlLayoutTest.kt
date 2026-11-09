package io.github.ts3mobile.app.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.github.ts3mobile.app.service.MicrophoneMode
import io.github.ts3mobile.app.ui.theme.Ts3MobileTheme
import io.github.ts3mobile.audio.opus.MicrophoneLevel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

class GateControlLayoutTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun thresholdAndSignalRemainTogetherDuringAdjustment() {
        val fixture = UiFixture(connected = true)
        fixture.state.value = fixture.state.value.copy(microphoneMode = MicrophoneMode.VOLUME_GATE, isCapturing = true)
        fixture.microphoneLevels.value = sample(-45.0)
        rule.setContent { fixture.Render(locale = "zh-CN") }
        rule.onNodeWithContentDescription("更多选项").performClick()
        rule.onNodeWithText("麦克风设置").performClick()
        rule.onNodeWithTag("microphone_gate_slider").performSemanticsAction(SemanticsActions.SetProgress) { it(-30f) }
        rule.onNodeWithText("音量门控阈值：-30 dBFS").assertIsDisplayed()
        rule.onNodeWithContentDescription("门控关闭 · 正在监听").assertIsDisplayed()
        val section = rule.onNodeWithTag("microphone_gate_section").fetchSemanticsNode().boundsInRoot
        val meter = rule.onNodeWithContentDescription("门控关闭 · 正在监听").fetchSemanticsNode().boundsInRoot
        val slider = rule.onNodeWithTag("microphone_gate_slider").fetchSemanticsNode().boundsInRoot
        assertTrue(section.contains(meter.center) && section.contains(slider.center))
        rule.runOnIdle { assertEquals(-30, fixture.state.value.microphoneSettings.gateThresholdDb) }
        saveDialog("gate-zh-light")
    }

    @Test fun liveTrackUsesTheSameScaleAsTheThreshold() = verifyTrack(LayoutDirection.Ltr)

    @Test fun rightToLeftTrackFollowsTheThresholdDirection() = verifyTrack(LayoutDirection.Rtl)

    private fun verifyTrack(direction: LayoutDirection) {
        val levels = MutableStateFlow<MicrophoneLevel?>(sample(-45.0))
        var fill = Color.Unspecified
        var empty = Color.Unspecified
        rule.setContent {
            Ts3MobileTheme {
                fill = MaterialTheme.colorScheme.secondary
                empty = MaterialTheme.colorScheme.outlineVariant
                CompositionLocalProvider(LocalLayoutDirection provides direction) {
                    Column(Modifier.padding(24.dp)) {
                        MicrophoneLevelIndicator(levels, isCapturing = true, isTransmitting = false, gateThresholdDb = -30)
                    }
                }
            }
        }
        rule.waitUntil(2_000) { levels.subscriptionCount.value == 1 }
        rule.waitForIdle()
        val pixels = rule.onNodeWithTag("microphone_gate_track", useUnmergedTree = true).captureToImage().toPixelMap()
        // -45 dBFS is one third of the visible -60..-15 scale.
        val fillX = if (direction == LayoutDirection.Ltr) 0.28f else 0.72f
        val emptyX = if (direction == LayoutDirection.Ltr) 0.40f else 0.60f
        assertEquals(fill, pixels[(pixels.width * fillX).toInt(), pixels.height / 2])
        assertEquals(empty, pixels[(pixels.width * emptyX).toInt(), pixels.height / 2])
    }

    @Test fun chineseLargeTextKeepsThresholdAndLevelVisibleWithoutStartingCapture() {
        val fixture = UiFixture(connected = true)
        rule.setContent { fixture.Render(locale = "zh-CN", fontScale = 1.8f, dark = true) }
        rule.onNodeWithContentDescription("更多选项").performClick()
        rule.onNodeWithText("麦克风设置").performClick()
        rule.onNodeWithTag("microphone_gate_slider").performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(-35f) }
        rule.onNodeWithContentDescription("麦克风未开启").assertIsDisplayed()
        rule.onNodeWithText("音量门控阈值：-35 dBFS").assertIsDisplayed()
        rule.onNodeWithText("关闭").assertIsDisplayed()
        rule.runOnIdle {
            assertFalse(fixture.state.value.isCapturing)
            assertTrue(fixture.transmission.none { it })
        }
        saveDialog("gate-zh-dark-large")
    }

    private fun sample(db: Double) = MicrophoneLevel(1, 0, 0, System.nanoTime(), db, true, false, false, -40)

    private fun saveDialog(name: String) {
        val file = File(rule.activity.getExternalFilesDir(null), "ui-hierarchy/" + name + ".png")
        file.parentFile?.mkdirs()
        file.outputStream().use {
            check(rule.onNode(isDialog()).captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }
}
