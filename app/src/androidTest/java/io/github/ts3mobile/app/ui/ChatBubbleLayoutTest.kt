package io.github.ts3mobile.app.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import io.github.ts3mobile.app.chat.ChatMessageKind
import io.github.ts3mobile.protocol.TextSendOutcome
import io.github.ts3mobile.protocol.TextTargetMode
import io.github.ts3mobile.protocol.Ts3TextEvent
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

class ChatBubbleLayoutTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun channelChatUsesLeftAndRightBubblesWithCenteredSystemNotes() = preview(dark = false, large = false)

    @Test fun chineseDarkLargeTextKeepsLongBubblesInsideTheConversation() = preview(dark = true, large = true)

    private fun preview(
        dark: Boolean,
        large: Boolean,
    ) {
        val fixture = UiFixture(connected = true)
        fixture.chatStore.receive(1, 1, Ts3TextEvent(TextTargetMode.CHANNEL, 1, 2, "fixture-2", "Sam", "晚上好，今天的语音测试准备好了吗？"))
        fixture.refreshChat()
        fixture.chatStore.updateDraft("准备好了，我们先检查麦克风，再试一下耳机。")
        val binding = checkNotNull(fixture.chat.value.binding)
        val pending = checkNotNull(fixture.chatStore.beginSend(binding))
        fixture.chatStore.complete(binding, pending.id, TextSendOutcome.Accepted)
        fixture.refreshChat()
        val incoming = fixture.chat.value.messages.first { it.kind == ChatMessageKind.INCOMING }
        val outgoing = fixture.chat.value.messages.first { it.kind == ChatMessageKind.OUTGOING }
        rule.setContent { fixture.Render(locale = "zh-CN", dark = dark, fontScale = if (large) 1.8f else 1f) }
        rule.onNodeWithText("聊天").performClick()
        val conversation = rule.onNodeWithTag("chat_messages").fetchSemanticsNode().boundsInRoot
        // A short viewport may virtualize either bubble; compare horizontal geometry after revealing each one.
        rule.onNodeWithTag("chat_messages").performScrollToNode(hasTestTag("chat_bubble_" + incoming.id))
        val left = rule.onNodeWithTag("chat_bubble_" + incoming.id).fetchSemanticsNode().boundsInRoot
        rule.onNodeWithTag("chat_messages").performScrollToNode(hasTestTag("chat_bubble_" + outgoing.id))
        val right = rule.onNodeWithTag("chat_bubble_" + outgoing.id).fetchSemanticsNode().boundsInRoot
        assertTrue("Incoming bubble must start left of outgoing bubble", left.left < right.left)
        assertTrue("Outgoing bubble must end to the right", right.right > left.right)
        assertTrue(left.width < conversation.width && right.width < conversation.width)
        assertTrue(left.left >= conversation.left && right.right <= conversation.right)
        rule.onNodeWithTag("chat_draft").assertIsDisplayed()
        rule.onNodeWithTag("chat_send").assertIsDisplayed()
        if (!large) {
            val system = fixture.chat.value.messages.first { it.kind == ChatMessageKind.CHANNEL }
            rule.onNodeWithTag("chat_messages").performScrollToNode(hasTestTag("chat_system_" + system.id))
            val note = rule.onNodeWithTag("chat_system_" + system.id).fetchSemanticsNode().boundsInRoot
            assertTrue(kotlin.math.abs(note.center.x - conversation.center.x) < 2f)
        }
        val file = File(rule.activity.getExternalFilesDir(null), "ui-hierarchy/chat-" + (if (dark) "dark-large" else "light") + ".png")
        file.parentFile?.mkdirs()
        file.outputStream().use {
            check(rule.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }
}
