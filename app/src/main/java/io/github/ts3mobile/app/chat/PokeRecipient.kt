package io.github.ts3mobile.app.chat

import io.github.ts3mobile.protocol.TextTarget
import io.github.ts3mobile.protocol.Ts3Participant

/** Captured when the user opens the dialog; never follows an ID into another attempt. */
data class PokeRecipient(val binding: ChatBinding?, val target: TextTarget.Private, val nickname: String) {
    fun isCurrent(
        state: ChatState,
        participants: List<Ts3Participant>,
    ): Boolean =
        state.connected && binding != null && state.binding?.scope == binding.scope && state.binding?.attempt == binding.attempt &&
            target.clientId != state.ownClientId && target.expectedUid.chatUid() != null &&
            participants.singleOrNull { it.uniqueIdentifier == target.expectedUid }?.id == target.clientId

    override fun toString(): String = "PokeRecipient(content=<redacted>)"
}
