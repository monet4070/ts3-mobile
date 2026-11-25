package io.github.ts3mobile.app.chat

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatHistoryControllerTest {
    private val account = checkNotNull(ChatHistoryAccount.parse("voice.example.com", 9987, "fixture-one"))
    private val conversation = ChatHistoryConversation(account, ChatHistoryRoom.Channel(10))

    @Test fun clearAndDisableNeverResurrectQueuedOrPendingMessages() =
        runTest {
            val store = FixtureHistoryStore()
            val history = ChatHistoryController(backgroundScope, store)
            runCurrent()
            assertNull(history.ticket())
            assertTrue(history.setEnabled(true))
            val beforeClear = checkNotNull(history.ticket())
            history.offer(beforeClear, record("queued before clear"))
            runCurrent()
            assertTrue(history.clear())
            history.offer(beforeClear, record("late confirmation"))
            advanceTimeBy(61)
            runCurrent()
            assertTrue(history.read(account.ownUid).isEmpty())
            val beforeDisable = checkNotNull(history.ticket())
            history.offer(beforeDisable, record("queued before disable"))
            assertTrue(history.setEnabled(false))
            assertTrue(history.setEnabled(true))
            history.offer(beforeDisable, record("stale after reenable"))
            val newEntry = record("new message")
            history.offer(checkNotNull(history.ticket()), newEntry)
            advanceTimeBy(120)
            runCurrent()
            assertEquals(listOf(newEntry), history.read(account.ownUid))
        }

    @Test fun overflowIsVisibleAndWriteFailurePausesCaptureUntilExplicitRetry() =
        runTest {
            val store = FixtureHistoryStore(initiallyEnabled = true)
            val history = ChatHistoryController(backgroundScope, store)
            runCurrent()
            val ticket = checkNotNull(history.ticket())
            repeat(70) { history.offer(ticket, record("fixture message")) }
            assertTrue(history.state.value.dropped)
            store.failWrites = true
            advanceTimeBy(61)
            runCurrent()
            assertTrue(history.state.value.failed)
            assertNull(history.ticket())
            assertFalse(history.reload())
            assertTrue(history.state.value.failed)
            assertNull(history.ticket())
            store.failWrites = false
            assertTrue(history.reload())
            val recoveredTicket = checkNotNull(history.ticket())
            val recovered = record("saved after recovery")
            history.offer(recoveredTicket, recovered)
            advanceTimeBy(200)
            runCurrent()
            assertFalse(history.state.value.failed)
            assertEquals(listOf(recovered), history.read(account.ownUid))
        }

    @Test fun clearReleasesAllQueueSlotsForNewGeneration() =
        runTest {
            val history = ChatHistoryController(backgroundScope, FixtureHistoryStore(initiallyEnabled = true))
            runCurrent()
            val oldTicket = checkNotNull(history.ticket())
            val old = record("old generation")
            history.offer(oldTicket, old)
            runCurrent()
            repeat(64) { history.offer(oldTicket, old) }
            assertFalse(history.state.value.dropped)

            assertTrue(history.clear())
            history.offer(oldTicket, old)
            val fresh = record("new generation")
            history.offer(checkNotNull(history.ticket()), fresh)
            advanceTimeBy(250)
            runCurrent()

            assertFalse(history.state.value.dropped)
            assertEquals(listOf(fresh), history.read(account.ownUid))
        }

    private fun record(body: String) = ChatHistoryRecord(conversation, 1L, false, null, body, false)
}

internal class FixtureHistoryStore(initiallyEnabled: Boolean = false) : ChatHistoryStore {
    private var optedIn = initiallyEnabled
    private val records = mutableListOf<ChatHistoryRecord>()
    var failWrites = false

    override suspend fun enabled(): Boolean = optedIn

    override suspend fun recover(): Boolean {
        if (optedIn) check(!failWrites)
        return optedIn
    }

    override suspend fun setEnabled(enabled: Boolean) {
        optedIn = enabled
        if (!enabled) records.clear()
    }

    override suspend fun append(records: List<ChatHistoryRecord>) {
        check(!failWrites)
        if (optedIn) this.records += records
    }

    override suspend fun read(ownUid: String): List<ChatHistoryRecord> = records.filter { it.conversation.account.ownUid == ownUid }

    override suspend fun delete(conversation: ChatHistoryConversation) {
        records.removeAll { it.conversation == conversation }
    }

    override suspend fun clear() = records.clear()
}
