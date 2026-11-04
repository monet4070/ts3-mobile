package io.github.ts3mobile.app.chat

import io.github.ts3mobile.protocol.TextSendOutcome

enum class ChatMessageKind { INCOMING, OUTGOING, CHANNEL }

enum class ChatMessageType { TEXT, POKE }

data class ChatBinding(val scope: Long, val attempt: Long, val channelId: Int?, val ownClientId: Int?)

data class ChatMessage(
    val id: Long,
    val sequence: Long,
    val receivedAtMs: Long,
    val kind: ChatMessageKind,
    val channelId: Int?,
    val sender: String?,
    val body: String,
    val outcome: TextSendOutcome? = null,
    val truncated: Boolean = false,
    val type: ChatMessageType = ChatMessageType.TEXT,
) {
    val isSending: Boolean get() = kind == ChatMessageKind.OUTGOING && outcome == null
    val retainedBytes: Int get() = body.toByteArray(Charsets.UTF_8).size + sender.orEmpty().toByteArray(Charsets.UTF_8).size

    override fun toString(): String = "ChatMessage(id=$id, sequence=$sequence, kind=$kind, content=<redacted>)"
}
