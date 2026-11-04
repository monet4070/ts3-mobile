package io.github.ts3mobile.app.service

import io.github.ts3mobile.app.chat.ChatBinding
import io.github.ts3mobile.app.chat.ChatStore
import io.github.ts3mobile.protocol.Ts3Participant
import io.github.ts3mobile.protocol.Ts3PokeEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PokeAlertObserverTest {
    private val peer = Ts3Participant(8, 10, "Fixture", false, false, false, "fixture-uid")
    private val binding = ChatBinding(1, 1, 10, 7)

    private fun store() =
        ChatStore().apply {
            begin(1)
            bind(1, 1, binding, participants = listOf(peer))
        }

    private fun ChatStore.poke(body: String = "") = receivePoke(1, 1, Ts3PokeEvent(8, "fixture-uid", "Fixture", body))

    @Test fun foregroundPokesRemainVisibleInAppWithoutASecondSystemAlert() =
        runTest {
            val store = store()
            var shown = 0
            val observer = PokeAlertObserver(backgroundScope, store.state, { shown++ }, {})
            observer.setAppVisible(true)
            runCurrent()
            store.poke()
            runCurrent()
            assertEquals(0, shown)
            assertEquals("", store.state.value.pokeNotice?.body)
            observer.close()
        }

    @Test fun eachNewBackgroundPokeAlertsOnceWithoutExposingContentToTheSink() =
        runTest {
            val store = store()
            var shown = 0
            val observer = PokeAlertObserver(backgroundScope, store.state, { shown++ }, {})
            runCurrent()
            store.poke("private reason")
            runCurrent()
            store.updateDraft("unrelated edit")
            runCurrent()
            assertEquals(1, shown)
            store.poke("second")
            runCurrent()
            assertEquals(2, shown)
            observer.close()
        }

    @Test fun returningToForegroundClearsTheAlertWithoutReplayingItOnBackground() =
        runTest {
            val store = store()
            var shown = 0
            var active = false
            val observer =
                PokeAlertObserver(backgroundScope, store.state, {
                    shown++
                    active = true
                }, { active = false })
            runCurrent()
            store.poke()
            runCurrent()
            assertTrue(active)
            observer.setAppVisible(true)
            runCurrent()
            assertFalse(active)
            observer.setAppVisible(false)
            runCurrent()
            assertEquals(1, shown)
            assertFalse(active)
            observer.close()
        }

    @Test fun aPokeAlreadyShownInForegroundDoesNotBecomeANewBackgroundAlert() =
        runTest {
            val store = store()
            var shown = 0
            val observer = PokeAlertObserver(backgroundScope, store.state, { shown++ }, {})
            observer.setAppVisible(true)
            runCurrent()
            store.poke()
            runCurrent()
            observer.setAppVisible(false)
            runCurrent()
            assertEquals(0, shown)
            observer.close()
        }

    @Test fun dismissalAndSessionEndClearPendingNotifications() =
        runTest {
            val store = store()
            var active = false
            val observer = PokeAlertObserver(backgroundScope, store.state, { active = true }, { active = false })
            runCurrent()
            store.poke()
            runCurrent()
            store.dismissPoke(checkNotNull(store.state.value.pokeNotice).id)
            runCurrent()
            assertFalse(active)
            store.poke()
            runCurrent()
            assertTrue(active)
            store.clear()
            runCurrent()
            assertFalse(active)
            observer.close()
        }

    @Test fun staleAndOwnEventsCannotTriggerAnAlert() =
        runTest {
            val store = store()
            var shown = 0
            val observer = PokeAlertObserver(backgroundScope, store.state, { shown++ }, {})
            runCurrent()
            store.receivePoke(1, 2, Ts3PokeEvent(8, "fixture-uid", "Fixture", ""))
            store.receivePoke(1, 1, Ts3PokeEvent(7, "self-uid", "Self", ""))
            runCurrent()
            assertEquals(0, shown)
            observer.close()
        }

    @Test fun closingTheObserverPreventsLateNotifications() =
        runTest {
            val store = store()
            var shown = 0
            var active = false
            val observer =
                PokeAlertObserver(backgroundScope, store.state, {
                    shown++
                    active = true
                }, { active = false })
            runCurrent()
            store.poke()
            runCurrent()
            observer.close()
            store.poke("late")
            runCurrent()
            assertEquals(1, shown)
            assertFalse(active)
        }
}
