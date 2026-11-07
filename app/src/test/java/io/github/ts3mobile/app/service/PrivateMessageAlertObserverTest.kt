package io.github.ts3mobile.app.service

import io.github.ts3mobile.app.chat.ChatBinding
import io.github.ts3mobile.app.chat.ChatConversationKey
import io.github.ts3mobile.app.chat.ChatStore
import io.github.ts3mobile.protocol.TextTargetMode
import io.github.ts3mobile.protocol.Ts3Participant
import io.github.ts3mobile.protocol.Ts3PokeEvent
import io.github.ts3mobile.protocol.Ts3TextEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PrivateMessageAlertObserverTest {
    private val peers = listOf(peer(2), peer(3))

    private fun peer(id: Int) = Ts3Participant(id, 10, "Fixture $id", false, false, false, "fixture-$id")

    private fun store(maxMessages: Int = 500) =
        ChatStore(maxMessages = maxMessages).apply {
            begin(1)
            bind(1, 1, ChatBinding(1, 1, 10, 1), participants = peers)
        }

    private fun ChatStore.receive(
        id: Int = 2,
        attempt: Long = 1,
    ) = receive(1, attempt, Ts3TextEvent(TextTargetMode.PRIVATE, 1, id, "fixture-$id", "Fixture $id", "Private body"))

    private class Alerts(scope: TestScope, store: ChatStore) {
        val registry = ChatNotificationRegistry({ scope.testScheduler.currentTime })
        val shown = mutableListOf<Pair<String, Boolean>>()
        var active = false
        val observer =
            PrivateMessageAlertObserver(scope.backgroundScope, store.state, registry, { token, silent ->
                shown += token to silent
                active = true
            }, { active = false })
    }

    @Test fun firstSubscriptionDoesNotReplayExistingUnread() =
        runTest {
            val store = store().apply { receive() }
            val alerts = Alerts(this, store)
            runCurrent()
            advanceTimeBy(3_000)
            runCurrent()
            assertTrue(alerts.shown.isEmpty())
            assertEquals(1, store.state.value.totalUnread)
            alerts.observer.close()
        }

    @Test fun onlyAcceptedOrdinaryPrivateTextCanAlert() =
        runTest {
            val store = store()
            val alerts = Alerts(this, store)
            runCurrent()
            store.receive(1, 1, Ts3TextEvent(TextTargetMode.CHANNEL, 10, 2, "fixture-2", "Fixture", "Channel"))
            store.receivePoke(1, 1, Ts3PokeEvent(2, "fixture-2", "Fixture", "Poke"))
            store.receive(id = 1)
            store.receive(attempt = 9)
            store.openPrivate(2)
            store.updateDraft("Outgoing")
            store.beginSend(checkNotNull(store.state.value.binding))
            store.markVisibleThrough(Long.MAX_VALUE)
            runCurrent()
            advanceTimeBy(2_001)
            runCurrent()
            assertTrue(alerts.shown.isEmpty())
            assertEquals(0, store.state.value.privateMessageRevision)
            store.receive()
            runCurrent()
            advanceTimeBy(2_000)
            runCurrent()
            assertEquals(1, alerts.shown.size)
            alerts.observer.close()
        }

    @Test fun fixedWindowDoesNotDebounceAnUnendingStream() =
        runTest {
            val store = store()
            val alerts = Alerts(this, store)
            runCurrent()
            repeat(4) {
                store.receive()
                runCurrent()
                advanceTimeBy(500)
            }
            runCurrent()
            assertEquals(1, alerts.shown.size)
            assertFalse(alerts.shown.first().second)
            store.receive()
            runCurrent()
            advanceTimeBy(2_000)
            runCurrent()
            assertEquals(2, alerts.shown.size)
            assertTrue(alerts.shown.last().second)
            alerts.observer.close()
        }

    @Test fun conflationAndEvictionStillSummarizeEveryRetainedConversation() =
        runTest {
            val store = store(maxMessages = 2)
            val alerts = Alerts(this, store)
            runCurrent()
            store.receive(2)
            repeat(30) { store.receive(3) }
            // Deliberately do not yield between messages; the first body is evicted.
            assertTrue(store.state.value.conversations.single { it.key == ChatConversationKey.User("fixture-2") }.messages.isEmpty())
            runCurrent()
            advanceTimeBy(2_000)
            runCurrent()
            val destination = alerts.registry.consume(alerts.shown.single().first, store.state.value)
            assertEquals(2, destination?.conversationIds?.size)
            assertEquals(31, store.state.value.privateMessageRevision)
            alerts.observer.close()
        }

    @Test fun foregroundBarrierCancelsWindowAndDoesNotReplayUncollectedEvents() =
        runTest {
            val store = store()
            val alerts = Alerts(this, store)
            runCurrent()
            store.receive()
            runCurrent()
            advanceTimeBy(1_999)
            alerts.observer.setAppVisible(true)
            store.receive(3)
            alerts.observer.setAppVisible(false)
            runCurrent()
            advanceTimeBy(3_000)
            runCurrent()
            assertTrue(alerts.shown.isEmpty())
            assertEquals(2, store.state.value.totalUnread)
            store.receive()
            runCurrent()
            advanceTimeBy(2_000)
            runCurrent()
            assertEquals(1, alerts.shown.size)
            alerts.observer.close()
        }

    @Test fun foregroundClearsNotificationWithoutReadingMessagesOrInvalidatingClick() =
        runTest {
            val store = store()
            val alerts = Alerts(this, store)
            runCurrent()
            store.receive()
            runCurrent()
            advanceTimeBy(2_000)
            runCurrent()
            val token = alerts.shown.single().first
            alerts.observer.setAppVisible(true)
            assertFalse(alerts.active)
            assertEquals(1, store.state.value.totalUnread)
            assertTrue(alerts.registry.consume(token, store.state.value) != null)
            alerts.observer.close()
        }

    @Test fun closingConversationBeforeDeadlineSuppressesItsNotification() =
        runTest {
            val store = store()
            val alerts = Alerts(this, store)
            runCurrent()
            store.receive()
            runCurrent()
            store.close(ChatConversationKey.User("fixture-2"))
            advanceTimeBy(2_000)
            runCurrent()
            assertTrue(alerts.shown.isEmpty())
            alerts.observer.close()
        }

    @Test fun conflatedDisconnectReconnectInvalidatesOldTokenAndKeepsNewArrival() =
        runTest {
            val store = store()
            val alerts = Alerts(this, store)
            runCurrent()
            store.receive()
            runCurrent()
            advanceTimeBy(2_000)
            runCurrent()
            val token = alerts.shown.single().first
            store.pause(1, 1)
            store.bind(1, 2, ChatBinding(1, 2, 10, 1), participants = peers)
            store.receive(attempt = 2)
            runCurrent()
            assertNull(alerts.registry.consume(token, store.state.value))
            advanceTimeBy(1_999)
            runCurrent()
            assertEquals(1, alerts.shown.size)
            advanceTimeBy(1)
            runCurrent()
            assertEquals(2, alerts.shown.size)
            assertFalse(alerts.shown.last().second)
            alerts.observer.close()
        }

    @Test fun sessionEndAndObserverCloseCancelLateWork() =
        runTest {
            val store = store()
            val alerts = Alerts(this, store)
            runCurrent()
            store.receive()
            runCurrent()
            store.clear()
            runCurrent()
            advanceTimeBy(2_000)
            runCurrent()
            assertTrue(alerts.shown.isEmpty())
            alerts.observer.close()
            store.begin(1)
            store.bind(1, 1, ChatBinding(1, 1, 10, 1), participants = peers)
            store.receive()
            advanceTimeBy(3_000)
            runCurrent()
            assertTrue(alerts.shown.isEmpty())
        }
}
