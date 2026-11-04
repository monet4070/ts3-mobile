package io.github.ts3mobile.app.chat

import io.github.ts3mobile.protocol.MAX_TEXT_EVENT_BYTES
import io.github.ts3mobile.protocol.TextEventIssue
import io.github.ts3mobile.protocol.TextSendOutcome
import io.github.ts3mobile.protocol.TextTargetMode
import io.github.ts3mobile.protocol.Ts3TextEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatStoreTest {
    private var now = 100L
    private val store = ChatStore(maxMessages = 5, maxBytes = 17_000, clockMs = { now })
    private val binding = ChatBinding(1, 1, 10, 7)

    private fun activate() {
        store.begin(1)
        store.bind(1, 1, binding)
    }

    private fun incoming(
        body: String = "hello",
        invoker: Int = 8,
    ) = Ts3TextEvent(
        TextTargetMode.CHANNEL,
        null,
        invoker,
        "uid",
        "Fixture",
        body,
    )

    @Test fun budgetsEvictCompletedMessagesButKeepPendingAndMonotonicUnread() {
        activate()
        store.updateDraft("pending")
        val pending = store.beginSend(binding)!!
        repeat(8) { store.receive(1, 1, incoming("$it")) }
        assertEquals(5, store.state.value.messages.size)
        assertTrue(store.state.value.messages.any { it.id == pending.id && it.isSending })
        assertTrue(store.state.value.overflow)
        val sequences = store.state.value.messages.map { it.sequence }
        assertEquals(sequences.sorted(), sequences)
        assertEquals(4, store.state.value.unread)
        store.markVisibleThrough(sequences.last())
        assertEquals(0, store.state.value.unread)
        now = -100
        store.receive(1, 1, incoming("later"))
        assertEquals(1, store.state.value.unread)
        assertTrue(store.state.value.messages.last().sequence > sequences.last())
    }

    @Test fun utf8ByteBudgetIncludesSenderAndPendingRecord() {
        activate()
        repeat(3) { store.receive(1, 1, incoming("中".repeat(3_000))) }
        assertTrue(store.state.value.messages.sumOf { it.retainedBytes } <= 17_000)
        assertEquals(1, store.state.value.messages.size)
        assertTrue(store.state.value.overflow)
    }

    @Test fun singleFlightPreservesNextDraftAndAllowsDeliberateDuplicateAfterCompletion() {
        activate()
        store.updateDraft(" hello ")
        val first = store.beginSend(binding)!!
        assertEquals(" hello ", first.body)
        store.updateDraft("next")
        assertNull(store.beginSend(binding))
        store.complete(binding, first.id, TextSendOutcome.Accepted)
        assertEquals("next", store.state.value.draft)
        store.updateDraft(" hello ")
        val second = store.beginSend(binding)!!
        assertNotEquals(first.id, second.id)
        assertEquals(first.body, second.body)
    }

    @Test fun reconnectRetainsContentButRejectsOldAttemptAndLateResults() {
        activate()
        store.updateDraft("out")
        val pending = store.beginSend(binding)!!
        store.updateDraft("retained draft")
        store.pause(1, 1)
        assertFalse(store.state.value.canSend)
        assertEquals(TextSendOutcome.Unknown, store.state.value.messages.last().outcome)
        val replacement = binding.copy(attempt = 2)
        store.bind(1, 2, replacement)
        store.complete(binding, pending.id, TextSendOutcome.Accepted)
        store.receive(1, 1, incoming("stale"))
        assertEquals(TextSendOutcome.Unknown, store.state.value.messages.last().outcome)
        assertEquals("retained draft", store.state.value.draft)
        assertFalse(store.state.value.messages.any { it.body == "stale" })
        assertTrue(store.state.value.canSend)
    }

    @Test fun unknownIsFinalAndOwnEchoDoesNotDuplicateButOtherRepeatsRemain() {
        activate()
        store.updateDraft("same")
        val pending = store.beginSend(binding)!!
        store.complete(binding, pending.id, TextSendOutcome.Unknown)
        store.complete(binding, pending.id, TextSendOutcome.Accepted)
        store.receive(1, 1, incoming("same", 7))
        repeat(2) { store.receive(1, 1, incoming("same", 8)) }
        store.receive(1, 1, incoming("same", 9))
        assertEquals(3, store.state.value.unread)
        assertEquals(4, store.state.value.messages.count { it.body == "same" })
        assertEquals(TextSendOutcome.Unknown, store.state.value.messages.first { it.id == pending.id }.outcome)
    }

    @Test fun inputValidationSwitchingAndChannelSeparatorHaveNoUnread() {
        activate()
        store.updateDraft("  \n ")
        assertFalse(store.state.value.canSend)
        store.updateDraft("中".repeat(342))
        assertFalse(store.state.value.canSend)
        store.updateDraft("valid")
        store.bind(1, 1, binding, switching = true)
        assertFalse(store.state.value.canSend)
        store.bind(1, 1, binding.copy(channelId = 20))
        assertNull(store.beginSend(binding))
        assertEquals(listOf(10, 20), store.state.value.messages.map { it.channelId })
        assertEquals(0, store.state.value.unread)
    }

    @Test fun visiblePositionDoesNotReadMessagesBelowItAndSurvivesEviction() {
        activate()
        store.receive(1, 1, incoming("first"))
        val firstSequence = store.state.value.messages.last().sequence
        store.receive(1, 1, incoming("second"))
        store.markVisibleThrough(firstSequence)
        assertEquals(1, store.state.value.unread)
        repeat(8) { store.receive(1, 1, incoming()) }
        store.markVisibleThrough(0)
        assertEquals(firstSequence, store.state.value.readThrough)
        assertEquals(5, store.state.value.unread)
    }

    @Test fun lateBindingCannotRollBackReplacementAttempt() {
        activate()
        store.bind(1, 2, binding.copy(attempt = 2))
        assertFalse(store.bind(1, 1, binding))
        assertEquals(2L, store.state.value.binding?.attempt)
        assertFalse(store.pause(1, 1))
        assertTrue(store.state.value.connected)
    }

    @Test fun clearAndNewManualScopeRemoveAllPrivateStateAndRejectStaleCallbacks() {
        activate()
        store.receive(1, 1, incoming())
        store.updateDraft("secret draft")
        store.begin(2)
        store.receive(1, 1, incoming("stale"))
        store.clear(1)
        assertEquals(2L, store.state.value.scope)
        assertTrue(store.state.value.messages.isEmpty())
        assertEquals("", store.state.value.draft)
        store.clear(2)
        assertNull(store.state.value.scope)
        store.updateDraft("must not remain after exit")
        assertEquals("", store.state.value.draft)
    }

    @Test fun missingBodyAndLargeEventsAreClassifiedAndModelStringsAreRedacted() {
        activate()
        store.receive(1, 1, incoming().copy(issue = TextEventIssue.MISSING_BODY))
        assertTrue(store.state.value.invalidEvent)
        store.receive(1, 1, incoming("😀".repeat(10_000)))
        val message = store.state.value.messages.last()
        assertTrue(message.truncated)
        assertTrue(message.retainedBytes <= MAX_TEXT_EVENT_BYTES + 256)
        store.updateDraft("private-draft")
        assertFalse(store.state.value.toString().contains("private-draft"))
        assertFalse(message.toString().contains("Fixture"))
        assertFalse(message.toString().contains("😀"))
    }
}
