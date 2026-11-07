package io.github.ts3mobile.app.ui

import android.content.ClipboardManager
import android.graphics.Bitmap
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import io.github.ts3mobile.app.service.MicrophoneMode
import io.github.ts3mobile.protocol.ConnectionPhase
import io.github.ts3mobile.protocol.ConnectionStatus
import io.github.ts3mobile.protocol.TextSendOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File

class ChatPanelTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Before fun matchProductionWindow() {
        rule.runOnUiThread {
            rule.activity.enableEdgeToEdge()
            rule.activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
    }

    @Test fun sendsUntrimmedPlainTextAndCopiesOnlyBody() {
        val fixture = UiFixture(connected = true)
        rule.setContent { fixture.Render() }
        rule.onNodeWithText("Chat").performClick()
        rule.onNodeWithTag("chat_send").assertIsNotEnabled()
        rule.onNodeWithTag("chat_draft").performTextInput(" 中文😀 | \\ ")
        // Verify send/copy independently of native IME movement; the keyboard
        // geometry regression below retains its real visibility assertions.
        rule.onNodeWithTag("chat_send").assertIsEnabled().performSemanticsAction(SemanticsActions.OnClick) { it() }
        savePreview("chat-send-keyboard")
        // IME/window animation is outside Compose idling; retain a bounded visibility assertion.
        rule.waitUntil(timeoutMillis = 5_000) {
            runCatching { rule.onNodeWithText("Server accepted").assertIsDisplayed() }.isSuccess
        }
        rule.onNodeWithText("Server accepted").assertIsDisplayed()
        rule.runOnIdle { assertEquals(listOf(" 中文😀 | \\ "), fixture.sentBodies) }
        rule.onNodeWithText(" 中文😀 | \\ ").performTouchInput { longClick() }
        rule.onNodeWithText("Copy message").performClick()
        rule.runOnIdle {
            val clipboard = rule.activity.getSystemService(ClipboardManager::class.java)
            assertEquals(" 中文😀 | \\ ", clipboard.primaryClip!!.getItemAt(0).text.toString())
        }
    }

    @Test fun byteLimitAndSwitchingPreventSendWithoutChangingDraft() {
        val fixture = UiFixture(connected = true)
        fixture.chatStore.updateDraft("中".repeat(342))
        fixture.refreshChat()
        rule.setContent { fixture.Render() }
        rule.onNodeWithText("Chat").performClick()
        rule.onNodeWithTag("chat_send").assertIsNotEnabled()
        rule.onNodeWithText("Message exceeds 1024 UTF-8 bytes").assertIsDisplayed()
        rule.runOnIdle {
            fixture.chatStore.updateDraft("valid")
            fixture.chatStore.bind(1, 1, fixture.chat.value.binding, switching = true)
            fixture.refreshChat()
        }
        rule.onNodeWithTag("chat_send").assertIsNotEnabled()
        rule.onNodeWithText("Switching channel · sending paused").assertIsDisplayed()
        rule.runOnIdle {
            assertEquals("valid", fixture.chat.value.draft)
            assertTrue(fixture.sentBodies.isEmpty())
        }
    }

    @Test fun reconnectKeepsChatAndEditableDraftWhileListsArePaused() {
        val fixture = UiFixture(connected = true)
        fixture.receiveText("before recovery")
        rule.setContent { fixture.Render() }
        rule.onNodeWithText("Chat").performClick()
        rule.runOnIdle {
            fixture.state.value = fixture.state.value.copy(status = ConnectionStatus(ConnectionPhase.RECONNECTING))
            fixture.chatStore.pause(1, 1)
            fixture.refreshChat()
        }
        rule.onNodeWithText("before recovery").assertIsDisplayed()
        rule.onNodeWithTag("chat_draft").performTextInput("during recovery")
        rule.onNodeWithTag("chat_send").assertIsNotEnabled()
        rule.runOnUiThread {
            WindowInsetsControllerCompat(rule.activity.window, rule.activity.window.decorView).hide(WindowInsetsCompat.Type.ime())
        }
        rule.waitUntil(2_000) { rule.onAllNodesWithText("Channels").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Channels").performClick()
        rule.onNodeWithText("Session recovering · channel and user controls paused").assertIsDisplayed()
        rule.runOnIdle { assertEquals("during recovery", fixture.chat.value.draft) }
    }

    @Test fun historyScrollKeepsUnseenUnreadUntilJumpToLatest() {
        val fixture = UiFixture(connected = true)
        repeat(30) { fixture.receiveText("Fixture message $it\nSecond line") }
        rule.setContent { fixture.Render() }
        rule.onNodeWithText("Chat").performClick()
        rule.onNodeWithTag("chat_messages").performScrollToIndex(0)
        rule.waitForIdle()
        rule.runOnIdle { fixture.receiveText("latest unread") }
        rule.runOnIdle { assertTrue(fixture.chat.value.unread > 0) }
        rule.onNodeWithTag("chat_new_messages").performClick()
        rule.onNodeWithText("latest unread").assertIsDisplayed()
        rule.runOnIdle { assertEquals(0, fixture.chat.value.unread) }
    }

    @Test fun tabAndInputFocusReleasePttButKeepContinuousMode() {
        val fixture = UiFixture(connected = true)
        rule.setContent { fixture.Render() }
        rule.onNodeWithContentDescription("Push to talk").performSemanticsAction(SemanticsActions.OnClick) { it() }
        rule.onNodeWithText("Chat").performClick()
        rule.runOnIdle { assertEquals(false, fixture.transmission.last()) }
        rule.runOnIdle { fixture.state.value = fixture.state.value.copy(microphoneMode = MicrophoneMode.CONTINUOUS) }
        rule.onNodeWithTag("chat_draft").performClick()
        rule.onNodeWithText("Starting microphone").assertIsDisplayed()
        rule.runOnIdle { assertEquals(MicrophoneMode.CONTINUOUS, fixture.state.value.microphoneMode) }
        rule.onNodeWithContentDescription("Stop microphone").performClick()
        rule.runOnIdle { assertEquals(MicrophoneMode.OFF, fixture.state.value.microphoneMode) }
    }

    @Test fun unknownResultRemainsVisibleWithoutAutomaticRetry() {
        val fixture = UiFixture(connected = true)
        fixture.sendOutcome = TextSendOutcome.Unknown
        fixture.chatStore.updateDraft("one attempt")
        fixture.refreshChat()
        rule.setContent { fixture.Render() }
        rule.onNodeWithText("Chat").performClick()
        rule.onNodeWithTag("chat_send").performClick()
        rule.onNodeWithText("Result unknown · do not resend automatically").assertIsDisplayed()
        rule.runOnIdle {
            assertEquals(listOf("one attempt"), fixture.sentBodies)
            assertFalse(fixture.chat.value.sending)
        }
    }

    @Test fun chineseLargeTextKeyboardKeepsComposerAndStopAvailable() {
        val fixture = UiFixture(connected = true)
        rule.setContent { fixture.Render(locale = "zh", fontScale = 1.8f) }
        rule.onNodeWithText("聊天").performClick()
        rule.onNodeWithTag("chat_draft").performClick().performTextInput("大字体消息")
        savePreview("chat-zh-large-keyboard-before")
        rule.onNodeWithTag("chat_draft").assertIsDisplayed()
        rule.onNodeWithTag("chat_send").assertIsDisplayed()
        rule.onNodeWithContentDescription("停止麦克风").assertIsDisplayed()
        assertDraftTextFits()
        savePreview("chat-zh-large-keyboard")
    }

    @Test fun activityStateRecreationKeepsOnlyServiceOwnedContentAndChatPosition() {
        val fixture = UiFixture(connected = true)
        fixture.receiveText("service-memory-message")
        fixture.chatStore.updateDraft("service-memory-draft")
        fixture.refreshChat()
        val restoration = StateRestorationTester(rule)
        restoration.setContent { fixture.Render() }
        rule.onNodeWithText("Chat").performClick()
        restoration.emulateSavedInstanceStateRestore()
        rule.onNodeWithTag("chat_draft").assertIsDisplayed()
        rule.onNodeWithText("service-memory-draft").assertIsDisplayed()
        rule.onNodeWithText("service-memory-message").assertIsDisplayed()
    }

    @Test fun pendingSendAllowsDraftEditingButDisablesAnotherSubmit() {
        val fixture = UiFixture(connected = true)
        fixture.chatStore.updateDraft("pending")
        val binding = checkNotNull(fixture.chat.value.binding)
        val pending = checkNotNull(fixture.chatStore.beginSend(binding))
        fixture.refreshChat()
        rule.setContent { fixture.Render() }
        rule.onNodeWithText("Chat").performClick()
        rule.onNodeWithTag("chat_draft").performTextInput("next")
        rule.onNodeWithTag("chat_send").assertIsNotEnabled()
        rule.runOnIdle {
            fixture.chatStore.complete(binding, pending.id, TextSendOutcome.Accepted)
            fixture.refreshChat()
        }
        rule.onNodeWithTag("chat_send").assertIsEnabled()
        rule.runOnIdle {
            assertEquals("next", fixture.chat.value.draft)
            assertTrue(fixture.sentBodies.isEmpty())
        }
    }

    @Test fun englishLargeTextKeyboardKeepsInputUnclipped() {
        val fixture = UiFixture(connected = true)
        rule.setContent { fixture.Render(fontScale = 1.8f) }
        rule.onNodeWithText("Chat").performClick()
        rule.onNodeWithTag("chat_draft").performClick().performTextInput("Large text")
        assertDraftTextFits()
        rule.onNodeWithContentDescription("Stop microphone").assertIsDisplayed()
        savePreview("chat-en-large-keyboard")
    }

    private fun assertDraftTextFits() {
        val layouts = mutableListOf<TextLayoutResult>()
        rule.onNodeWithTag("chat_draft").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue(layouts.isNotEmpty())
        assertFalse(layouts.single().didOverflowHeight)
    }

    @Test fun darkEnglishChatPreview() {
        val fixture = UiFixture(connected = true)
        fixture.receiveText("Hello from the current channel")
        rule.setContent { fixture.Render(dark = true) }
        rule.onNodeWithText("Chat").performClick()
        rule.onNodeWithText("Disconnect").assertIsDisplayed()
        savePreview("chat-en-dark")
        rule.onNodeWithText("Hello from the current channel").assertIsDisplayed()
    }

    private fun savePreview(name: String) {
        val output = File(rule.activity.getExternalFilesDir(null), "ui-previews").apply { mkdirs() }
        File(output, "$name.png").outputStream().use {
            check(rule.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }
}
