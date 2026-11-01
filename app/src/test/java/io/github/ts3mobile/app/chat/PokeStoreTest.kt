package io.github.ts3mobile.app.chat

import io.github.ts3mobile.protocol.TextEventIssue
import io.github.ts3mobile.protocol.TextSendOutcome
import io.github.ts3mobile.protocol.TextTarget
import io.github.ts3mobile.protocol.TextTargetMode
import io.github.ts3mobile.protocol.Ts3Participant
import io.github.ts3mobile.protocol.Ts3PokeEvent
import io.github.ts3mobile.protocol.Ts3TextEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PokeStoreTest {
    private val binding = ChatBinding(1, 1, 10, 7)
    private val peer = Ts3Participant(8, 10, "Fixture", false, false, false, "fixture-uid")
    private val recipient = PokeRecipient(binding, TextTarget.Private(8, "fixture-uid"), "Fixture")

    private fun store(
        maxMessages: Int = 500,
        maxConversations: Int = 20,
    ) = ChatStore(maxMessages = maxMessages, maxConversations = maxConversations).apply {
        begin(1)
        bind(1, 1, binding, participants = listOf(peer))
    }

    private fun event(body: String = "reason") = Ts3PokeEvent(8, "fixture-uid", "Fixture", body)

    @Test fun emptyPokeHasDistinctTypeKeepsDraftsAndSharesPendingGuard() {
        val store = store()
        store.updateDraft("channel draft")
        store.openPrivate(8)
        store.updateDraft("private draft")
        store.select(ChatConversationKey.Channel)
        val claim = store.claimPoke(binding, recipient, "")!!
        assertEquals(ChatMessageType.POKE, claim.message.type)
        assertEquals("", claim.message.body)
        assertEquals("private draft", store.state.value.draft)
        assertNull(store.claimPoke(binding, recipient, "second"))
        assertNull(store.claimSend(binding))
        assertFalse(store.close(claim.conversation))
        store.complete(binding, claim.message.id, TextSendOutcome.Accepted)
        store.select(ChatConversationKey.Channel)
        assertEquals("channel draft", store.state.value.draft)
    }

    @Test fun staleDialogCannotFollowReusedClientIdOrNewAttempt() {
        val store = store()
        store.bind(1, 1, binding, participants = listOf(peer.copy(uniqueIdentifier = "replacement")))
        assertNull(store.claimPoke(binding, recipient, "old target"))
        store.bind(1, 2, binding.copy(attempt = 2), participants = listOf(peer))
        assertNull(store.claimPoke(binding.copy(attempt = 2), recipient, "old attempt"))
        assertFalse(recipient.isCurrent(store.state.value, listOf(peer)))
    }

    @Test fun unknownOrDuplicateUidSelfAndOversizedReasonAreNotClaimed() {
        val store = store()
        assertNull(store.claimPoke(binding, recipient.copy(target = TextTarget.Private(8)), ""))
        assertNull(store.claimPoke(binding, recipient.copy(target = TextTarget.Private(7, "fixture-uid")), ""))
        assertNull(store.claimPoke(binding, recipient, "😀".repeat(26)))
        store.bind(1, 1, binding, participants = listOf(peer, peer.copy(id = 9)))
        assertNull(store.claimPoke(binding, recipient, ""))
        assertFalse(recipient.isCurrent(store.state.value, listOf(peer, peer.copy(id = 9))))
        assertFalse(recipient.toString().contains("fixture-uid"))
        assertFalse(recipient.toString().contains("Fixture"))
    }

    @Test fun channelSwitchDoesNotInvalidateAnOtherwiseCurrentPrivatePoke() {
        val store = store()
        val moved = binding.copy(channelId = 20)
        store.bind(1, 1, moved, switching = true, participants = listOf(peer))
        assertTrue(recipient.isCurrent(store.state.value, listOf(peer)))
        assertNotNull(store.claimPoke(moved, recipient, "private target"))
    }

    @Test fun incomingPokeSharesConversationUnreadAndLatestNoticeCannotBeDismissedByOldId() {
        val store = store()
        store.receivePoke(1, 1, event("first"))
        val first = store.state.value.pokeNotice!!
        store.receivePoke(1, 1, event(""))
        val latest = store.state.value.pokeNotice!!
        assertEquals(ChatMessageType.POKE, latest.type)
        assertEquals("", latest.body)
        assertEquals(2, store.state.value.totalUnread)
        assertEquals(ChatConversationKey.Channel, store.state.value.selected)
        store.dismissPoke(first.id)
        assertEquals(latest.id, store.state.value.pokeNotice?.id)
        store.dismissPoke(latest.id)
        assertNull(store.state.value.pokeNotice)
        assertEquals(2, store.state.value.conversations.last().messages.size)
        store.clear()
        assertEquals(1, store.state.value.conversations.size)
    }

    @Test fun staleMalformedAndOwnPokesCannotCreateNotice() {
        val store = store()
        store.receivePoke(1, 2, event())
        store.receivePoke(1, 1, event().copy(invokerId = 7))
        store.receivePoke(1, 1, event().copy(issue = TextEventIssue.MISSING_BODY))
        store.receivePoke(1, 1, event().copy(invokerId = null))
        assertNull(store.state.value.pokeNotice)
        assertEquals(1, store.state.value.conversations.size)
        assertTrue(store.state.value.invalidEvent)
    }

    @Test fun pokeHistoryAndNoticeStayWithinSharedRetentionAndCapacity() {
        val store = store(maxMessages = 3, maxConversations = 2)
        store.receivePoke(1, 1, event("😀".repeat(10_000)))
        assertTrue(store.state.value.pokeNotice!!.truncated)
        assertTrue(store.state.value.pokeNotice!!.retainedBytes <= 16 * 1_024 + 256)
        store.receivePoke(1, 1, event().copy(invokerUid = "other", invokerId = 9))
        assertTrue(store.state.value.conversationLimitReached)
        assertEquals(2, store.state.value.conversations.size)
        repeat(5) { store.receive(1, 1, Ts3TextEvent(TextTargetMode.CHANNEL, 10, 9, null, "Peer", "next")) }
        assertNull(store.state.value.pokeNotice)
        assertEquals(3, store.state.value.conversations.sumOf { it.messages.size })
    }
}
