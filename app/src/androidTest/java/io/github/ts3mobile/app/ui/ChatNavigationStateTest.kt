package io.github.ts3mobile.app.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performSemanticsAction
import io.github.ts3mobile.app.chat.ChatBinding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ChatNavigationStateTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun channelHistoryAndOffsetSurviveBothMainTabs() {
        val fixture = history()
        rule.setContent { fixture.Render() }
        rule.onNodeWithText("Chat").performClick()
        scrollToHistory()
        val before = messageTop()
        listOf("Channels", "Users").forEach { tab ->
            rule.onNodeWithText(tab).performClick()
            rule.onNodeWithText("Chat").performClick()
            rule.onNodeWithText(message(5)).assertIsDisplayed()
            assertEquals(before, messageTop(), 2f)
        }
    }

    @Test fun hiddenAppendDoesNotMoveHistoryOrReadTheNewMessage() {
        val fixture = history()
        rule.setContent { fixture.Render() }
        rule.onNodeWithText("Chat").performClick()
        scrollToHistory()
        rule.onNodeWithText("Channels").performClick()
        val readThrough = fixture.chat.value.readThrough
        rule.runOnIdle { fixture.receiveText("New while hidden") }
        rule.runOnIdle { assertEquals(readThrough, fixture.chat.value.readThrough) }
        rule.onNodeWithText("Chat").performClick()
        rule.onNodeWithText(message(5)).assertIsDisplayed()
        rule.runOnIdle { assertTrue(fixture.chat.value.unread > 0) }
        rule.onNodeWithTag("chat_new_messages").performClick()
        rule.onNodeWithText("New while hidden").assertIsDisplayed()
        rule.runOnIdle { assertEquals(0, fixture.chat.value.unread) }
    }

    @Test fun privateHistorySurvivesMainTabAndSavedStateRestore() {
        val fixture = UiFixture(connected = true)
        repeat(30) { fixture.receivePrivate(message(it)) }
        fixture.chatStore.openPrivate(2)
        fixture.chatStore.markVisibleThrough(Long.MAX_VALUE)
        fixture.refreshChat()
        val restoration = StateRestorationTester(rule)
        restoration.setContent { fixture.Render() }
        rule.onNodeWithText("Chat").performClick()
        rule.onNodeWithTag("chat_messages").performScrollToIndex(5)
        rule.onNodeWithText("Users").performClick()
        rule.onNodeWithText("Chat").performClick()
        rule.onNodeWithText(message(5)).assertIsDisplayed()
        restoration.emulateSavedInstanceStateRestore()
        rule.onNodeWithText(message(5)).assertIsDisplayed()
    }

    @Test fun reconnectKeepsHistoryButANewManualSessionClearsNavigation() {
        val fixture = history()
        rule.setContent { fixture.Render() }
        rule.onNodeWithText("Chat").performClick()
        scrollToHistory()
        rule.runOnIdle {
            fixture.chatStore.pause(1, 1)
            fixture.chatStore.bind(1, 2, ChatBinding(1, 2, 1, 1), participants = fixture.state.value.snapshot.participants)
            fixture.refreshChat()
        }
        rule.onNodeWithText(message(5)).assertIsDisplayed()
        rule.runOnIdle {
            fixture.chatStore.begin(2)
            fixture.chatStore.bind(2, 1, ChatBinding(2, 1, 1, 1), participants = fixture.state.value.snapshot.participants)
            fixture.refreshChat()
        }
        rule.onNodeWithTag("chat_messages").assertDoesNotExist()
    }

    @Test fun savedStateWhileOnChannelsKeepsTheHiddenChatAnchor() {
        val fixture = history()
        val restoration = StateRestorationTester(rule)
        restoration.setContent { fixture.Render() }
        rule.onNodeWithText("Chat").performClick()
        scrollToHistory()
        val before = messageTop()
        rule.onNodeWithText("Channels").performClick()
        restoration.emulateSavedInstanceStateRestore()
        rule.onNodeWithText("Chat").performClick()
        assertEquals(before, messageTop(), 2f)
    }

    @Test fun hiddenEvictionKeepsARetainedMessageInsteadOfItsOldIndex() {
        val fixture = history()
        rule.setContent { fixture.Render() }
        rule.onNodeWithText("Chat").performClick()
        rule.onNodeWithTag("chat_messages").performScrollToIndex(21)
        rule.onNodeWithText(message(20)).assertIsDisplayed()
        rule.onNodeWithText("Channels").performClick()
        rule.runOnIdle { repeat(480) { fixture.receiveText("Later $it") } }
        rule.onNodeWithText("Chat").performClick()
        rule.onNodeWithText(message(20)).assertIsDisplayed()
    }

    @Test fun evictedAnchorFallsForwardToTheFirstRetainedMessage() {
        val fixture = history()
        rule.setContent { fixture.Render() }
        rule.onNodeWithText("Chat").performClick()
        scrollToHistory()
        rule.onNodeWithText("Channels").performClick()
        rule.runOnIdle { repeat(500) { fixture.receiveText("Replacement $it") } }
        rule.onNodeWithText("Chat").performClick()
        rule.onNodeWithText("Replacement 0").assertIsDisplayed()
        rule.runOnIdle { assertTrue(fixture.chat.value.unread > 0) }
    }

    @Test fun replacementServiceWithTheSameScopeCannotRestoreOldNavigation() {
        val fixture = history()
        rule.setContent { fixture.Render() }
        rule.onNodeWithText("Chat").performClick()
        scrollToHistory()
        val replacement = history()
        rule.runOnIdle { fixture.chat.value = replacement.chat.value }
        rule.onNodeWithTag("chat_messages").assertDoesNotExist()
    }

    private fun history(): UiFixture =
        UiFixture(connected = true).apply {
            repeat(30) { receiveText(message(it)) }
            chatStore.markVisibleThrough(Long.MAX_VALUE)
            refreshChat()
        }

    private fun scrollToHistory() {
        // Channel history has a separator before the first incoming message.
        rule.onNodeWithTag("chat_messages").performScrollToIndex(6)
        rule.onNodeWithTag("chat_messages").performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 17f) }
        rule.onNodeWithText(message(5)).assertIsDisplayed()
    }

    private fun messageTop(): Float = rule.onNodeWithText(message(5)).fetchSemanticsNode().boundsInRoot.top

    private fun message(index: Int): String = "History $index\nSecond line"
}
