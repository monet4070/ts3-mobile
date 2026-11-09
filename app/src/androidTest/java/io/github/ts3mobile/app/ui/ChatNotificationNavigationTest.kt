package io.github.ts3mobile.app.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.ts3mobile.app.chat.ChatNotificationDestination
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

class ChatNotificationNavigationTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun UiFixture.request(ids: Set<Long>) {
        chatNotification.value =
            ChatNotificationDestination(
                "fixture-request", checkNotNull(chat.value.sessionToken), chat.value.notificationGeneration, ids,
            )
    }

    @Test fun singleConversationWaitsForSelectionAndOnlyReadsVisibleTarget() {
        val fixture = UiFixture(connected = true)
        fixture.receivePrivate("Sam private", 2)
        fixture.receivePrivate("Jamie remains unread", 3)
        val sam = fixture.chat.value.conversations.single { it.title == "Sam" }
        rule.setContent { fixture.Render() }
        rule.runOnIdle {
            fixture.request(setOf(sam.id))
            assertEquals(2, fixture.chat.value.totalUnread)
        }
        rule.onNodeWithText("Sam private").assertIsDisplayed()
        rule.runOnIdle {
            assertEquals(sam.id, fixture.chat.value.active.id)
            assertEquals(1, fixture.chat.value.totalUnread)
            assertNull(fixture.chatNotification.value)
        }
        rule.onNodeWithText("Users").performClick()
        rule.onNodeWithTag("chat_messages").assertDoesNotExist()
    }

    @Test fun multipleConversationsOpenListWithoutReadingUntilUserChooses() {
        val fixture = UiFixture(connected = true)
        fixture.receivePrivate("Sam private", 2)
        fixture.receivePrivate("Jamie private", 3)
        val privateIds = fixture.chat.value.conversations.filter { it.isPrivate }.map { it.id }.toSet()
        val jamie = fixture.chat.value.conversations.single { it.title == "Jamie" }
        val restoration = StateRestorationTester(rule)
        restoration.setContent { fixture.Render() }
        rule.runOnIdle { fixture.request(privateIds) }
        rule.onNodeWithTag("chat_notification_conversations").assertIsDisplayed()
        rule.onNodeWithTag("chat_messages").assertDoesNotExist()
        rule.runOnIdle { assertEquals(2, fixture.chat.value.totalUnread) }
        restoration.emulateSavedInstanceStateRestore()
        rule.onNodeWithTag("chat_notification_conversations").assertIsDisplayed()
        rule.runOnIdle { assertEquals(2, fixture.chat.value.totalUnread) }
        val screenshot = File(rule.activity.getExternalFilesDir(null), "notification-ui/summary.png")
        screenshot.parentFile?.mkdirs()
        screenshot.outputStream().use {
            check(
                rule.onNodeWithTag(
                    "chat_notification_conversations",
                ).captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it),
            )
        }
        rule.onNodeWithTag("chat_notification_conversation_${jamie.id}").performClick()
        rule.onNodeWithText("Jamie private").assertIsDisplayed()
        rule.runOnIdle { assertEquals(1, fixture.chat.value.totalUnread) }
    }

    @Test fun staleSessionAndClosedConversationCannotChangeTabsOrRead() {
        val fixture = UiFixture(connected = true)
        fixture.receivePrivate("Private", 2)
        val target = fixture.chat.value.conversations.last()
        val oldSession = checkNotNull(fixture.chat.value.sessionToken)
        rule.setContent { fixture.Render() }
        rule.runOnIdle {
            fixture.chatNotification.value = ChatNotificationDestination("old", "other-session", 0, setOf(target.id))
        }
        rule.onNodeWithTag("chat_messages").assertDoesNotExist()
        rule.runOnIdle {
            assertNull(fixture.chatNotification.value)
            assertEquals(1, fixture.chat.value.totalUnread)
            fixture.chatStore.close(target.key)
            fixture.refreshChat()
            fixture.chatNotification.value = ChatNotificationDestination("closed", oldSession, 0, setOf(target.id))
        }
        rule.onNodeWithTag("chat_messages").assertDoesNotExist()
        rule.runOnIdle { assertNull(fixture.chatNotification.value) }
    }

    @Test fun notificationSettingsEntryDoesNotRequestAConnection() {
        val fixture = UiFixture()
        rule.setContent { fixture.Render() }
        rule.onNodeWithContentDescription("More options").performClick()
        rule.onNodeWithText("Private message notifications").performClick()
        rule.runOnIdle {
            assertEquals(1, fixture.notificationSettings)
            assertEquals(0, fixture.connects)
            assertTrue(fixture.transmission.none { it })
        }
    }
}
