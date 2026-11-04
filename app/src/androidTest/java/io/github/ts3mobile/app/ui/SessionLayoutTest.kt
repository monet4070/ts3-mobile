package io.github.ts3mobile.app.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import io.github.ts3mobile.app.service.MicrophoneMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

class SessionLayoutTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun connectionKeepsConnectAndBookmarksInTheFirstViewport() {
        val fixture = UiFixture()
        rule.setContent { fixture.Render() }
        rule.onNodeWithText("Connect").assertIsDisplayed()
        rule.onNodeWithText("Choose a saved server").assertIsDisplayed().performClick()
        rule.onNodeWithText("Add bookmark").assertIsDisplayed()
        rule.runOnIdle { assertEquals(0, fixture.connects) }
    }

    @Test fun sessionShortcutsReleasePttBeforeOpeningTheirDialogs() {
        val fixture = UiFixture(connected = true)
        rule.setContent { fixture.Render() }
        rule.onNodeWithContentDescription("Push to talk").performSemanticsAction(SemanticsActions.OnClick) { it() }
        rule.onNodeWithContentDescription("Find channels and users").performClick()
        rule.onNodeWithText("Channel, topic or nickname").assertIsDisplayed()
        rule.runOnIdle { assertEquals(listOf(true, false), fixture.transmission) }
        rule.onNodeWithText("Close").performClick()
        rule.onNodeWithContentDescription("Push to talk").performSemanticsAction(SemanticsActions.OnClick) { it() }
        rule.onNodeWithContentDescription("Away and status").performClick()
        rule.onNodeWithText("Apply status").assertIsDisplayed()
        rule.runOnIdle { assertEquals(listOf(true, false, true, false), fixture.transmission) }
    }

    @Test fun unreadBadgeLeavesTheChatLabelReadableAndClearsWhenRead() {
        val fixture = UiFixture(connected = true)
        repeat(110) { fixture.receiveText("Fixture incoming $it") }
        rule.setContent { fixture.Render() }
        rule.onNodeWithText("99+").assertIsDisplayed()
        assertTextFits("Chat")
        savePreview("session-unread-badge")
        rule.onNodeWithText("Chat").performClick()
        rule.onNodeWithTag("chat_new_messages").performClick()
        rule.onNodeWithText("Fixture incoming 109").assertIsDisplayed()
        rule.runOnIdle { assertEquals(0, fixture.chat.value.totalUnread) }
        rule.onNodeWithText("99+").assertDoesNotExist()
    }

    @Test fun narrowLargeTextKeepsDisconnectAndAllTabsReachable() {
        val fixture = UiFixture(connected = true)
        fixture.state.value = fixture.state.value.copy(serverLabel = "A long fixture server name for narrow layouts")
        rule.setContent {
            Box(Modifier.width(320.dp).height(640.dp)) { fixture.Render(fontScale = 1.8f) }
        }
        assertTextFits("Disconnect")
        rule.onNodeWithContentDescription("More options").assertIsDisplayed().performClick()
        rule.onNodeWithText("Diagnostics").performScrollTo().performClick()
        rule.onNodeWithText("Copy").assertIsDisplayed()
        rule.onNodeWithText("Close").performClick()
        rule.onNodeWithContentDescription("Find channels and users").assertIsDisplayed()
        rule.onNodeWithContentDescription("Away and status").assertIsDisplayed()
        rule.onNodeWithText("Users").performScrollTo().performClick()
        assertTextFits("Users")
        rule.onNodeWithText("Chat").performScrollTo().performClick()
        assertTextFits("Chat")
        rule.onNodeWithText("Channels").performScrollTo().performClick()
        assertTextFits("Channels")
        savePreview("session-narrow-large")
        rule.onNodeWithText("Disconnect").performClick()
        rule.runOnIdle { assertEquals(1, fixture.disconnects) }
    }

    @Test fun narrowMicrophoneSelectorUsesItsActualAvailableWidth() {
        val fixture = UiFixture(connected = true)
        rule.setContent { Box(Modifier.width(320.dp)) { fixture.Render() } }
        rule.onNodeWithText("Microphone").assertIsDisplayed()
        rule.onNodeWithText("Hold").performClick()
        rule.onNodeWithText("Gate").performClick()
        assertTextFits("Gate")
        rule.runOnIdle { assertEquals(MicrophoneMode.VOLUME_GATE, fixture.state.value.microphoneMode) }
        savePreview("session-narrow-gate")
    }

    @Test fun chineseGateSelectionDoesNotWrapAnyModeLabel() {
        val fixture = UiFixture(connected = true)
        fixture.state.value = fixture.state.value.copy(microphoneMode = MicrophoneMode.VOLUME_GATE, isCapturing = true)
        rule.setContent { fixture.Render(locale = "zh-CN") }
        val usesMenu = rule.onAllNodesWithText("关闭").fetchSemanticsNodes().isEmpty()
        if (usesMenu) rule.onNodeWithText("音量门控").assertIsDisplayed().performClick()
        listOf("关闭", "按住", "常开", "音量门控").forEach { assertTextFits(it, inPopup = usesMenu) }
        savePreview("session-gate-zh", inPopup = usesMenu)
    }

    private fun assertTextFits(
        text: String,
        inPopup: Boolean = false,
    ) {
        val layouts = mutableListOf<TextLayoutResult>()
        val node =
            if (inPopup) {
                rule.onNode(hasText(text) and hasAnyAncestor(isPopup()), useUnmergedTree = true)
            } else {
                rule.onNodeWithText(text, useUnmergedTree = true)
            }
        node.assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue("No layout for $text", layouts.isNotEmpty())
        layouts.forEach { layout ->
            // Single-line paragraphs can retain their offered width after Text
            // shrinks to its glyphs. Inspect the glyphs, not paragraph width.
            assertFalse("Clipped height: $text", layout.didOverflowHeight)
            assertEquals("Wrapped label: $text", 1, layout.lineCount)
            assertFalse("Ellipsized label: $text", layout.isLineEllipsized(0))
            assertTrue(
                "Clipped glyph: $text; size=${layout.size}",
                (0 until layout.layoutInput.text.length).all { offset ->
                    val glyph = layout.getBoundingBox(offset)
                    glyph.left >= -0.5f && glyph.right <= layout.size.width + 0.5f
                },
            )
        }
    }

    private fun savePreview(
        name: String,
        inPopup: Boolean = false,
    ) {
        val output = File(rule.activity.getExternalFilesDir(null), "ui-previews").apply { mkdirs() }
        File(output, "$name.png").outputStream().use {
            val node = if (inPopup) rule.onNode(isPopup()) else rule.onRoot()
            check(node.captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }
}
