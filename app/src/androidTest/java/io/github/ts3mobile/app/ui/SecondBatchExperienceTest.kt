package io.github.ts3mobile.app.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import io.github.ts3mobile.app.chat.ChatNotificationEntry
import io.github.ts3mobile.app.service.ChatNotificationRegistry
import io.github.ts3mobile.app.service.PrivateMessageNotificationIntent
import io.github.ts3mobile.app.service.PrivateMessageNotifications
import io.github.ts3mobile.audio.opus.MicrophoneLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Combined production policy/UI paths, with synthetic chat and explicitly fake capture feedback. */
class SecondBatchExperienceTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun notificationDestinationTuningPttAndTabLifecycleKeepDraftAndOtherUnread() {
        val fixture = UiFixture(connected = true)
        fixture.receivePrivate("Synthetic Sam private", 2)
        fixture.receivePrivate("Synthetic Jamie private", 3)
        val sam = fixture.chat.value.conversations.single { it.title == "Sam" }
        val registry = ChatNotificationRegistry(android.os.SystemClock::elapsedRealtime)
        val token = checkNotNull(registry.issue(fixture.chat.value, setOf(sam.id)))
        val intent = PrivateMessageNotificationIntent.create(rule.activity, token)
        val notification = PrivateMessageNotifications(rule.activity).build(token, false)
        if (android.os.Build.VERSION.SDK_INT >= 31) assertTrue(notification.contentIntent.isImmutable)
        val entry = ChatNotificationEntry()
        entry.accept(PrivateMessageNotificationIntent.token(intent))
        entry.resolve { registry.consume(it, fixture.chat.value) }
        fixture.chatNotification.value = entry.destination.value
        rule.setContent { fixture.Render() }
        rule.onNodeWithText("Synthetic Sam private").assertIsDisplayed()
        rule.runOnIdle {
            assertEquals(sam.id, fixture.chat.value.active.id)
            assertEquals(1, fixture.chat.value.totalUnread)
        }
        rule.onNodeWithTag("chat_draft").performTextInput("Retained private draft")
        rule.runOnUiThread {
            WindowInsetsControllerCompat(rule.activity.window, rule.activity.window.decorView).hide(WindowInsetsCompat.Type.ime())
        }
        rule.waitUntil(2_000) { rule.onAllNodesWithContentDescription("More options").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithContentDescription("More options").performClick()
        rule.onNodeWithText("Microphone settings").performClick()
        rule.onNodeWithContentDescription("Microphone is off").assertIsDisplayed()
        val ptt = rule.onNode(hasContentDescription("Push to talk") and hasAnyAncestor(isDialog()))
        ptt.performTouchInput {
            down(center)
            advanceEventTime(100)
        }
        rule.runOnIdle {
            assertEquals(true, fixture.transmission.last())
            // Fake feedback models the already-tested controller, never AudioRecord.
            fixture.state.value = fixture.state.value.copy(isCapturing = true, isTransmitting = true)
            fixture.microphoneLevels.value = MicrophoneLevel(1, 0, 0, System.nanoTime(), -30.0, false, true, true, -40)
        }
        rule.onNodeWithContentDescription("Microphone active · sending").assertIsDisplayed()
        rule.onNode(hasContentDescription("Talking") and hasAnyAncestor(isDialog())).performTouchInput { cancel() }
        rule.runOnIdle {
            assertEquals(false, fixture.transmission.last())
            fixture.state.value = fixture.state.value.copy(isCapturing = false, isTransmitting = false)
            fixture.microphoneLevels.value = null
        }
        rule.onNodeWithContentDescription("Microphone is off").assertIsDisplayed()
        rule.onNodeWithText("Close").performClick()
        rule.waitUntil(2_000) { fixture.microphoneLevels.subscriptionCount.value == 0 }
        rule.onNodeWithText("Users").performClick()
        rule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        fixture.receivePrivate("Another synthetic Jamie private", 3)
        rule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        rule.onNodeWithText("Chat").performClick()
        rule.onNodeWithText("Synthetic Sam private").assertIsDisplayed()
        rule.onNodeWithTag("chat_draft").assertTextContains("Retained private draft")
        rule.runOnIdle {
            assertEquals(2, fixture.chat.value.totalUnread)
            assertTrue(fixture.sentBodies.isEmpty())
            assertTrue(fixture.sentPokes.isEmpty())
            assertFalse(fixture.state.value.isCapturing)
        }
    }
}
