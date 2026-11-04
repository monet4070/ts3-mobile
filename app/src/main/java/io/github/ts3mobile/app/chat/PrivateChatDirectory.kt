package io.github.ts3mobile.app.chat

import io.github.ts3mobile.protocol.TextTarget
import io.github.ts3mobile.protocol.Ts3Participant
import io.github.ts3mobile.protocol.boundedText

/** Snapshot identity mapping only. Nicknames are display values, never identity keys. */
internal class PrivateChatDirectory {
    private var attempt: Long? = null
    private var nextOccupancy = 0L
    private var peers = emptyMap<Int, Peer>()

    fun clear() {
        attempt = null
        peers = emptyMap()
    }

    fun update(
        attempt: Long,
        participants: List<Ts3Participant>,
        ownId: Int?,
    ) {
        if (this.attempt != attempt) peers = emptyMap()
        this.attempt = attempt
        peers =
            participants.groupBy { it.id }.mapNotNull { (id, entries) ->
                val participant = entries.singleOrNull()?.takeIf { it.id > 0 && it.id != ownId } ?: return@mapNotNull null
                val uid = participant.uniqueIdentifier.chatUid()
                val previous = peers[id]
                val occupancy = if (previous != null && previous.uid == uid) previous.occupancy else ++nextOccupancy
                id to Peer(id, uid, participant.nickname.boundedText(256), occupancy)
            }.toMap()
    }

    fun keyFor(clientId: Int): ChatConversationKey? {
        val peer = peers[clientId] ?: return null
        return peer.uid?.let(ChatConversationKey::User) ?: fallbackKey(clientId)
    }

    fun fallbackKey(clientId: Int): ChatConversationKey.AttemptUser =
        ChatConversationKey.AttemptUser(checkNotNull(attempt), clientId, peers[clientId]?.occupancy ?: -1)

    fun target(key: ChatConversationKey): TextTarget.Private? = peer(key)?.let { TextTarget.Private(it.id, it.uid) }

    fun title(key: ChatConversationKey): String? = peer(key)?.title

    private fun peer(key: ChatConversationKey): Peer? =
        when (key) {
            ChatConversationKey.Channel -> null
            is ChatConversationKey.User -> peers.values.singleOrNull { it.uid == key.uid }
            is ChatConversationKey.AttemptUser -> peers[key.clientId]?.takeIf { attempt == key.attempt && it.occupancy == key.occupancy }
        }

    private class Peer(val id: Int, val uid: String?, val title: String, val occupancy: Long)
}

internal fun String?.chatUid(): String? = this?.takeIf { it.isNotBlank() && it.length <= 256 }
