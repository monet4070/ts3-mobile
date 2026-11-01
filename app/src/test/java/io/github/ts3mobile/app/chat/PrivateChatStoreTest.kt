package io.github.ts3mobile.app.chat

import io.github.ts3mobile.protocol.TextSendOutcome
import io.github.ts3mobile.protocol.TextTarget
import io.github.ts3mobile.protocol.TextTargetMode
import io.github.ts3mobile.protocol.Ts3Participant
import io.github.ts3mobile.protocol.Ts3TextEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivateChatStoreTest {
    private val binding = ChatBinding(1, 1, 10, 7)
    private val sam = Ts3Participant(8, 10, "Sam", false, false, false, "sam-uid")
    private val jane = sam.copy(id = 9, nickname = "Sam", uniqueIdentifier = "jane-uid")

    private fun store(max: Int = 20) =
        ChatStore(maxConversations = max).apply {
            begin(1)
            bind(1, 1, binding, participants = listOf(sam, jane))
        }

    private fun incoming(
        peer: Ts3Participant,
        body: String = "private",
    ) = Ts3TextEvent(
        TextTargetMode.PRIVATE,
        7,
        peer.id,
        peer.uniqueIdentifier,
        peer.nickname,
        body,
    )

    @Test fun draftsAndUnreadBelongToConversationAndNicknameNeverMergesUsers() {
        val store = store()
        store.updateDraft("channel draft")
        store.openPrivate(8)
        store.updateDraft("private draft")
        store.select(ChatConversationKey.Channel)
        store.receive(1, 1, incoming(sam))
        store.receive(1, 1, incoming(jane))
        assertEquals(0, store.state.value.unread)
        assertEquals(2, store.state.value.totalUnread)
        assertEquals("channel draft", store.state.value.draft)
        store.markVisibleThrough(ChatConversationKey.Channel, Long.MAX_VALUE)
        assertEquals(2, store.state.value.totalUnread)
        store.select(ChatConversationKey.User("sam-uid"))
        assertEquals("private draft", store.state.value.draft)
        assertEquals(1, store.state.value.unread)
        store.markVisibleThrough(store.state.value.messages.last().sequence)
        assertEquals(1, store.state.value.totalUnread)
        assertEquals(3, store.state.value.conversations.size)
    }

    @Test fun reusedClientIdCannotReceiveOldUidMessageAndFreshMappingMayUseNewId() {
        val store = store()
        store.openPrivate(8)
        store.updateDraft("original target")
        val request = store.claimSend(binding)!!
        store.bind(1, 1, binding, participants = listOf(sam.copy(uniqueIdentifier = "impostor")))
        assertNull(store.state.value.target)
        assertFalse(store.canSubmit(request))
        store.complete(binding, request.message.id, TextSendOutcome.Unknown)
        store.updateDraft("next")
        store.bind(1, 1, binding, participants = listOf(sam.copy(id = 18)))
        assertEquals(TextTarget.Private(18, "sam-uid"), store.state.value.target)
        assertTrue(store.state.value.canSend)
    }

    @Test fun reconnectKeepsStableConversationReadonlyUntilUidMapsAgain() {
        val store = store()
        store.openPrivate(8)
        store.receive(1, 1, incoming(sam))
        store.updateDraft("keep")
        store.pause(1, 1)
        assertFalse(store.state.value.canSend)
        store.bind(1, 2, binding.copy(attempt = 2), participants = listOf(jane.copy(id = 8)))
        assertNull(store.state.value.target)
        assertEquals("keep", store.state.value.draft)
        assertEquals(1, store.state.value.messages.size)
        store.receive(1, 1, incoming(sam, "stale"))
        assertEquals(1, store.state.value.messages.size)
        store.bind(1, 2, binding.copy(attempt = 2), participants = listOf(sam.copy(id = 20)))
        assertEquals(TextTarget.Private(20, "sam-uid"), store.state.value.target)
    }

    @Test fun missingUidConversationCannotSurviveAttemptOrClientOccupancy() {
        val store = store()
        val unknown = sam.copy(uniqueIdentifier = "")
        store.bind(1, 1, binding, participants = listOf(unknown))
        store.openPrivate(8)
        val original = store.state.value.selected
        store.updateDraft("for current occupant")
        store.bind(1, 1, binding, participants = emptyList())
        store.bind(1, 1, binding, participants = listOf(unknown))
        assertNull(store.state.value.target)
        store.openPrivate(8)
        assertFalse(original == store.state.value.selected)
        store.pause(1, 1)
        assertEquals(listOf(ChatConversationKey.Channel), store.state.value.conversations.map { it.key })
        assertEquals(ChatConversationKey.Channel, store.state.value.selected)
    }

    @Test fun privateEventsRequireOurTargetAndOwnEchoNeverCreatesConversation() {
        val store = store()
        store.receive(1, 1, incoming(sam).copy(targetId = 99))
        store.receive(1, 1, incoming(sam).copy(invokerId = 7, targetId = 8))
        assertEquals(1, store.state.value.conversations.size)
        assertTrue(store.state.value.invalidEvent)
        store.receive(1, 1, incoming(sam).copy(invokerUid = null))
        val conversation = store.state.value.conversations.last()
        assertTrue(conversation.key is ChatConversationKey.AttemptUser)
        assertEquals(TextTarget.Private(8, "sam-uid"), conversation.target)
    }

    @Test fun newAttemptCannotAcceptPrivateMessagesUsingPreviousOwnClientId() {
        val store = store()
        store.pause(1, 1)
        store.bind(1, 2, binding.copy(attempt = 2, ownClientId = null), participants = listOf(sam))
        store.receive(1, 2, incoming(sam))
        assertNull(store.state.value.ownClientId)
        assertEquals(1, store.state.value.conversations.size)
        assertTrue(store.state.value.invalidEvent)
        store.bind(1, 2, binding.copy(attempt = 2, ownClientId = 17), participants = listOf(sam))
        store.receive(1, 2, incoming(sam).copy(targetId = 17))
        assertEquals(2, store.state.value.conversations.size)
    }

    @Test fun incomingUidWinsOverCurrentReusedClientMappingButRemainsReadonly() {
        val store = store()
        store.bind(1, 1, binding, participants = listOf(sam.copy(uniqueIdentifier = "new occupant")))
        store.receive(1, 1, incoming(sam))
        store.select(ChatConversationKey.User("sam-uid"))
        assertEquals("private", store.state.value.messages.single().body)
        assertNull(store.state.value.target)
    }

    @Test fun conversationCapIsGlobalAndClosingFreesCapacity() {
        val store = store(3)
        store.receive(1, 1, incoming(sam))
        store.receive(1, 1, incoming(jane))
        val third = sam.copy(id = 30, uniqueIdentifier = "third")
        store.receive(1, 1, incoming(third))
        assertEquals(3, store.state.value.conversations.size)
        assertTrue(store.state.value.conversationLimitReached)
        store.close(ChatConversationKey.User("sam-uid"))
        store.receive(1, 1, incoming(third))
        assertEquals(3, store.state.value.conversations.size)
        assertFalse(store.state.value.conversationLimitReached)
        assertTrue(store.state.value.conversations.any { it.key == ChatConversationKey.User("third") })
    }

    @Test fun sharedCountBudgetEvictsGloballyAndCannotClosePendingConversation() {
        val store = ChatStore(maxMessages = 4)
        store.begin(1)
        store.bind(1, 1, binding, participants = listOf(sam))
        store.openPrivate(8)
        store.updateDraft("pending")
        val request = store.claimSend(binding)!!
        assertFalse(store.close(store.state.value.selected))
        store.select(ChatConversationKey.Channel)
        repeat(8) { store.receive(1, 1, Ts3TextEvent(TextTargetMode.CHANNEL, null, 9, null, "Fixture", "$it")) }
        assertEquals(4, store.state.value.conversations.sumOf { it.messages.size })
        assertTrue(store.state.value.conversations.flatMap { it.messages }.any { it.id == request.message.id && it.isSending })
        store.complete(binding, request.message.id, TextSendOutcome.Accepted)
        assertTrue(store.close(request.conversation))
        assertFalse(store.close(ChatConversationKey.Channel))
    }

    @Test fun byteBudgetIsSharedAcrossChannelAndPrivateAndStringsAreRedacted() {
        val store = ChatStore(maxBytes = 17_000)
        store.begin(1)
        store.bind(1, 1, binding, participants = listOf(sam))
        store.receive(1, 1, incoming(sam, "中".repeat(3_000)))
        store.receive(1, 1, Ts3TextEvent(TextTargetMode.CHANNEL, null, 9, null, "Fixture", "中".repeat(3_000)))
        assertTrue(store.state.value.conversations.sumOf { c -> c.messages.sumOf { it.retainedBytes } } <= 17_000)
        assertTrue(store.state.value.overflow)
        assertFalse(store.state.value.conversations.last().toString().contains("Sam"))
        assertFalse(ChatConversationKey.User("private-uid").toString().contains("private-uid"))
    }

    @Test fun privateSendingDoesNotDependOnCurrentChannelAndAmbiguousUidIsReadonly() {
        val store = store()
        val noChannel = binding.copy(channelId = null)
        store.bind(1, 1, noChannel, participants = listOf(sam))
        assertTrue(store.openPrivate(8))
        store.updateDraft("private")
        assertTrue(store.state.value.canSend)
        assertEquals(TextTarget.Private(8, "sam-uid"), store.claimSend(noChannel)?.target)
        store.bind(1, 1, noChannel, participants = listOf(sam, sam.copy(id = 20)))
        assertNull(store.state.value.target)
    }
}
