package io.github.ts3mobile.app.chat

import io.github.ts3mobile.protocol.TextSendOutcome
import java.util.Locale

internal data class ChatHistoryAccount private constructor(val host: String, val port: Int, val ownUid: String) {
    override fun toString(): String = "ChatHistoryAccount(<redacted>)"

    companion object {
        fun parse(
            host: String,
            port: Int,
            ownUid: String,
        ): ChatHistoryAccount? {
            val address = host.trim().lowercase(Locale.ROOT)
            if (address.length !in 1..255 || address.any { it.isWhitespace() || it.isISOControl() } ||
                port !in 1..65_535 || ownUid.historyUid() == null
            ) {
                return null
            }
            return ChatHistoryAccount(address, port, ownUid)
        }
    }
}

internal fun String.historyUid(): String? =
    takeIf {
        it.length in 1..256 && it.toByteArray().size <= 256 && it.none { character -> character.isWhitespace() || character.isISOControl() }
    }

internal sealed interface ChatHistoryRoom {
    data class Channel(val id: Int) : ChatHistoryRoom

    data class Peer(val uid: String) : ChatHistoryRoom {
        override fun toString(): String = "Peer(<redacted>)"
    }
}

internal data class ChatHistoryConversation(val account: ChatHistoryAccount, val room: ChatHistoryRoom)

internal data class ChatHistoryRecord(
    val conversation: ChatHistoryConversation,
    val receivedAtMs: Long,
    val outgoing: Boolean,
    val sender: String?,
    val body: String,
    val truncated: Boolean,
) {
    override fun toString(): String = "ChatHistoryRecord(<redacted>)"

    companion object {
        fun from(
            account: ChatHistoryAccount,
            key: ChatConversationKey,
            message: ChatMessage,
            channelId: Int?,
        ): ChatHistoryRecord? {
            if (message.type != ChatMessageType.TEXT || message.kind == ChatMessageKind.CHANNEL) return null
            if (message.kind == ChatMessageKind.OUTGOING && message.outcome != TextSendOutcome.Accepted) return null
            val room =
                when (key) {
                    ChatConversationKey.Channel ->
                        message.channelId
                            ?.takeIf { it > 0 && it == channelId }
                            ?.let(ChatHistoryRoom::Channel)
                    is ChatConversationKey.User -> key.uid.historyUid()?.let(ChatHistoryRoom::Peer)
                    is ChatConversationKey.AttemptUser -> null
                } ?: return null
            return ChatHistoryRecord(
                ChatHistoryConversation(account, room),
                message.receivedAtMs,
                message.kind == ChatMessageKind.OUTGOING,
                message.sender,
                message.body,
                message.truncated,
            )
        }
    }
}
