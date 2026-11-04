package io.github.ts3mobile.app.ui

import android.graphics.Bitmap
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import io.github.ts3mobile.app.chat.ChatConversationKey
import io.github.ts3mobile.protocol.TextTarget
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File

class PrivateChatPanelTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Before fun matchProductionWindow() {
        rule.runOnUiThread {
            rule.activity.enableEdgeToEdge()
            @Suppress("DEPRECATION")
            rule.activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
    }

    @Test fun userMenuOpensPrivateAndSendsOnlyToThatUser() {
        val fixture = UiFixture(connected = true)
        rule.setContent { fixture.Render() }
        rule.onNodeWithText("Users").performClick()
        rule.onNodeWithContentDescription("More actions for Alex").assertDoesNotExist()
        rule.onNodeWithContentDescription("More actions for Sam").performClick()
        rule.onNodeWithText("Send private message").performClick()
        rule.onNodeWithText("Private · Sam (#2)").assertIsDisplayed()
        rule.onNodeWithTag("chat_draft").performTextInput(" Private 中文😀 | \\ ")
        rule.onNodeWithTag("chat_send").performClick()
        // Native IME/window animation can outlive Compose idling on a narrow screen.
        rule.waitUntil(timeoutMillis = 5_000) {
            runCatching { rule.onNodeWithText("Server accepted").assertIsDisplayed() }.isSuccess
        }
        rule.onNodeWithText("Server accepted").assertIsDisplayed()
        rule.runOnIdle {
            assertEquals(listOf(TextTarget.Private(2, "fixture-2")), fixture.sentTargets)
            assertEquals(listOf(" Private 中文😀 | \\ "), fixture.sentBodies)
        }
    }

    @Test fun duplicateIdentityClientsRemainVisibleAndPrivateSendStaysUnconfirmed() {
        val fixture = UiFixture(connected = true)
        val participants =
            fixture.state.value.snapshot.participants.map {
                if (it.id == 3) it.copy(uniqueIdentifier = "fixture-2") else it
            }
        fixture.state.value = fixture.state.value.copy(snapshot = fixture.state.value.snapshot.copy(participants = participants))
        fixture.chatStore.bind(1, 1, fixture.chat.value.binding, participants = participants)
        fixture.refreshChat()
        rule.setContent { fixture.Render() }
        rule.onNodeWithText("Users").performClick()
        rule.onNodeWithText("Sam").assertIsDisplayed()
        rule.onNodeWithText("Jamie").assertIsDisplayed()
        rule.onNodeWithContentDescription("More actions for Sam").performClick()
        rule.onNodeWithText("Send private message").performClick()
        rule.onNodeWithTag("chat_draft").performTextInput("ambiguous recipient")
        rule.onNodeWithTag("chat_send").assertIsNotEnabled()
        rule.onNodeWithText("User offline or identity unconfirmed · sending paused").assertIsDisplayed()
    }

    @Test fun switchingKeepsSeparateDraftsAndUnreadUntilEachConversationIsVisible() {
        val fixture = UiFixture(connected = true)
        fixture.chatStore.updateDraft("channel draft")
        fixture.receivePrivate("Sam inbound")
        fixture.receivePrivate("Jamie inbound", 3)
        val sam = fixture.chat.value.conversations.single { it.key == ChatConversationKey.User("fixture-2") }
        rule.setContent { fixture.Render() }
        rule.onNodeWithText("Chat").performClick()
        rule.onNodeWithText("channel draft").assertIsDisplayed()
        select(sam.id)
        rule.onNodeWithText("Sam inbound").assertIsDisplayed()
        rule.onNodeWithTag("chat_draft").performTextInput("Sam draft")
        rule.runOnIdle { assertEquals(1, fixture.chat.value.totalUnread) }
        select(0)
        rule.onNodeWithText("channel draft").assertIsDisplayed()
        select(sam.id)
        rule.onNodeWithText("Sam draft").assertIsDisplayed()
        rule.onNodeWithText("Jamie inbound").assertDoesNotExist()
    }

    @Test fun reusedIdPausesSendingAndKeepsOriginalDraftAndHistory() {
        val fixture = UiFixture(connected = true)
        fixture.receivePrivate("original sender")
        fixture.chatStore.openPrivate(2)
        fixture.chatStore.updateDraft("original draft")
        fixture.refreshChat()
        rule.setContent { fixture.Render() }
        rule.onNodeWithText("Chat").performClick()
        rule.onNodeWithTag("chat_send").assertIsEnabled()
        rule.runOnIdle {
            fixture.chatStore.bind(
                1,
                1,
                fixture.chat.value.binding,
                participants =
                    fixture.state.value.snapshot.participants.map {
                        if (it.id == 2) it.copy(uniqueIdentifier = "replacement") else it
                    },
            )
            fixture.refreshChat()
        }
        rule.onNodeWithTag("chat_send").assertIsNotEnabled()
        rule.onNodeWithText("User offline or identity unconfirmed · sending paused").assertIsDisplayed()
        rule.onNodeWithText("original draft").assertIsDisplayed()
        rule.onNodeWithText("original sender").assertIsDisplayed()
    }

    @Test fun closingNonemptyConversationRequiresConfirmationAndClearsOnlyThatConversation() {
        val fixture = UiFixture(connected = true)
        fixture.chatStore.updateDraft("keep channel")
        fixture.chatStore.openPrivate(2)
        fixture.chatStore.updateDraft("discard private")
        fixture.refreshChat()
        rule.setContent { fixture.Render() }
        rule.onNodeWithText("Chat").performClick()
        rule.onNodeWithContentDescription("Close private chat").performClick()
        rule.onNodeWithText("Cancel").performClick()
        rule.onNodeWithText("discard private").assertIsDisplayed()
        rule.onNodeWithContentDescription("Close private chat").performClick()
        rule.onNode(hasText("Close private chat") and hasClickAction()).performClick()
        rule.onNodeWithText("keep channel").assertIsDisplayed()
        rule.runOnIdle { assertEquals(1, fixture.chat.value.conversations.size) }
    }

    @Test fun emptyConversationClosesImmediatelyAndFullListShowsCapacityWarning() {
        val fixture = UiFixture(connected = true)
        fixture.chatStore.openPrivate(2)
        fixture.refreshChat()
        rule.setContent { fixture.Render() }
        rule.onNodeWithText("Chat").performClick()
        rule.onNodeWithContentDescription("Close private chat").performClick()
        rule.onNodeWithText("Close this conversation and clear its messages and draft?").assertDoesNotExist()
        rule.runOnIdle {
            repeat(20) { index ->
                fixture.chatStore.receive(
                    1,
                    1,
                    io.github.ts3mobile.protocol.Ts3TextEvent(
                        io.github.ts3mobile.protocol.TextTargetMode.PRIVATE,
                        1,
                        index + 10,
                        "fixture-$index",
                        "Fixture",
                        "inbound",
                    ),
                )
            }
            fixture.refreshChat()
        }
        rule.onNodeWithText("Conversation limit reached (20). Close a private chat to make room.").assertIsDisplayed()
        rule.runOnIdle { assertEquals(20, fixture.chat.value.conversations.size) }
    }

    @Test fun restoredPrivateSelectionRetainsServiceContentAndSwitchableChannel() {
        val fixture = UiFixture(connected = true)
        fixture.chatStore.updateDraft("channel memory")
        fixture.receivePrivate("private memory")
        fixture.chatStore.openPrivate(2)
        fixture.chatStore.updateDraft("private draft memory")
        fixture.refreshChat()
        val restoration = StateRestorationTester(rule)
        restoration.setContent { fixture.Render() }
        rule.onNodeWithText("Chat").performClick()
        restoration.emulateSavedInstanceStateRestore()
        rule.onNodeWithText("private memory").assertIsDisplayed()
        rule.onNodeWithText("private draft memory").assertIsDisplayed()
        select(0)
        rule.onNodeWithText("channel memory").assertIsDisplayed()
    }

    @Test fun historyPositionSurvivesConversationSwitchAndStateRestoration() {
        val fixture = UiFixture(connected = true)
        repeat(30) { fixture.receivePrivate("Private history $it\nSecond line") }
        fixture.chatStore.openPrivate(2)
        fixture.chatStore.markVisibleThrough(Long.MAX_VALUE)
        fixture.refreshChat()
        val privateId = fixture.chat.value.active.id
        val restoration = StateRestorationTester(rule)
        restoration.setContent { fixture.Render() }
        rule.onNodeWithText("Chat").performClick()
        rule.onNodeWithTag("chat_messages").performScrollToIndex(0)
        rule.onNodeWithText("Private history 0\nSecond line").assertIsDisplayed()
        select(0)
        select(privateId)
        rule.onNodeWithText("Private history 0\nSecond line").assertIsDisplayed()
        restoration.emulateSavedInstanceStateRestore()
        rule.onNodeWithText("Private history 0\nSecond line").assertIsDisplayed()
        rule.runOnIdle { fixture.receivePrivate("new while reading history") }
        rule.onNodeWithText("Private history 0\nSecond line").assertIsDisplayed()
        rule.runOnIdle { assertEquals(1, fixture.chat.value.unread) }
    }

    @Test fun chineseLargeTextPrivateComposerAndCloseRemainVisibleWithKeyboard() {
        val fixture = UiFixture(connected = true)
        fixture.chatStore.openPrivate(2)
        fixture.refreshChat()
        rule.setContent { fixture.Render(locale = "zh", fontScale = 1.8f) }
        rule.onNodeWithText("聊天").performClick()
        rule.onNodeWithTag("chat_draft").performTextInput("私聊大字体")
        rule.onNodeWithTag("chat_send").assertIsDisplayed()
        rule.onNodeWithContentDescription("关闭私聊").assertIsDisplayed()
        rule.onNodeWithContentDescription("停止麦克风").assertIsDisplayed()
        val output = File(rule.activity.getExternalFilesDir(null), "ui-previews").apply { mkdirs() }
        File(output, "private-zh-large-keyboard.png").outputStream().use {
            check(rule.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }

    private fun select(id: Long) {
        rule.onNodeWithTag("chat_conversation_picker").performClick()
        rule.onNodeWithTag("chat_conversation_$id").performClick()
    }
}
