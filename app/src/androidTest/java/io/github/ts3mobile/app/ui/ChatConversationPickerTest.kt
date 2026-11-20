package io.github.ts3mobile.app.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.ts3mobile.app.chat.ChatBinding
import io.github.ts3mobile.app.chat.ChatConversation
import io.github.ts3mobile.app.chat.ChatConversationKey
import io.github.ts3mobile.app.chat.ChatState
import io.github.ts3mobile.app.ui.theme.Ts3MobileTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ChatConversationPickerTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val privateChat = ChatConversation(1, ChatConversationKey.User("fixture"), "A long synthetic private conversation name")
    private val state = ChatState(binding = ChatBinding(1, 1, 2, 1), conversations = listOf(ChatConversation(), privateChat))

    @Test fun compactHeaderRemainsOneTouchableControl() {
        var selected: ChatConversationKey? = null
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1f)) {
                Ts3MobileTheme { ChatConversationPicker(state, { selected = it }, {}) }
            }
        }
        rule.onNodeWithTag("chat_conversation_picker")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .assertHeightIsEqualTo(48.dp)
            .performTouchInput { click(Offset(right - 8.dp.toPx(), centerY)) }
        rule.onNodeWithTag("chat_conversation_1").assertIsDisplayed().performClick()
        rule.runOnIdle { assertEquals(privateChat.key, selected) }
    }

    @Test fun longPrivateTitleLeavesTheMenuAndCloseControlReachableAtLargeText() {
        var selected: ChatConversationKey? = null
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.8f)) {
                Ts3MobileTheme(darkTheme = true) {
                    Box(Modifier.width(320.dp)) {
                        ChatConversationPicker(state.copy(selected = privateChat.key), { selected = it }, {})
                    }
                }
            }
        }
        rule.onNodeWithContentDescription("Close private chat").assertIsDisplayed()
        rule.onNodeWithTag("chat_conversation_picker").assertIsDisplayed().performClick()
        rule.onNodeWithTag("chat_conversation_0").assertIsDisplayed().performClick()
        rule.runOnIdle { assertEquals(ChatConversationKey.Channel, selected) }
    }
}
