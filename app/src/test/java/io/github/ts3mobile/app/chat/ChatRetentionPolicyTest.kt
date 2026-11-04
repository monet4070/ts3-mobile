package io.github.ts3mobile.app.chat

import io.github.ts3mobile.protocol.TextSendOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatRetentionPolicyTest {
    private val privateKey = ChatConversationKey.User("fixture-uid")

    private fun message(
        id: Long,
        body: String = "body",
        pending: Boolean = false,
    ) = ChatMessage(id, id, 0, ChatMessageKind.OUTGOING, null, null, body, if (pending) null else TextSendOutcome.Accepted)

    @Test fun evictionUsesGlobalSequenceAndPreservesPendingAcrossConversations() {
        val state =
            ChatState(
                conversations =
                    listOf(
                        ChatConversation(messages = listOf(message(2))),
                        ChatConversation(3, privateKey, messages = listOf(message(1, pending = true), message(3))),
                    ),
            )
        val updated = ChatRetentionPolicy(3, 100).append(state, privateKey, message(4))
        assertEquals(listOf(1L, 3L, 4L), updated.conversations.flatMap { it.messages }.map { it.id })
        assertTrue(updated.overflow)
    }

    @Test fun utf8BytesAreSharedAndOldestCompletedMessageIsEvicted() {
        val state =
            ChatState(
                conversations =
                    listOf(
                        ChatConversation(messages = listOf(message(1, "中文"))),
                        ChatConversation(2, privateKey),
                    ),
            )
        val updated = ChatRetentionPolicy(10, 8).append(state, privateKey, message(3, "中文"))
        assertTrue(updated.conversations.first().messages.isEmpty())
        assertEquals(listOf(3L), updated.conversations.last().messages.map { it.id })
    }
}
