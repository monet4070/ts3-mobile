package io.github.ts3mobile.audio.opus

import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class PlaybackAdmissionTest {
    @Test fun muteUnmuteCannotReviveAnOldPacketAndDoesNotRevokeAnotherParticipant() {
        val admission = PlaybackAdmission()
        admission.withTicket(1) { a ->
            admission.withTicket(2) { b ->
                admission.invalidateParticipants(setOf(1))
                admission.invalidateParticipants(setOf(1))
                assertFalse(admission.accepts(a))
                assertTrue(admission.accepts(b))
                admission.withTicket(1) { fresh -> assertTrue(admission.accepts(fresh)) }
                assertFalse(admission.accepts(a))
            }
        }
    }

    @Test fun globalTransitionsRevokeEveryInFlightPreparation() {
        val admission = PlaybackAdmission()
        admission.withTicket(1) { a ->
            admission.withTicket(2) { b ->
                admission.invalidateAll()
                assertFalse(admission.accepts(a))
                assertFalse(admission.accepts(b))
            }
        }
        admission.withTicket(2) { fresh -> assertTrue(admission.accepts(fresh)) }
    }

    @Test fun completedAndFailedPreparationsCannotLeaveLiveTickets() {
        val admission = PlaybackAdmission()
        lateinit var completed: PlaybackAdmission.Ticket
        lateinit var failed: PlaybackAdmission.Ticket
        admission.withTicket(1) { completed = it }
        assertThrows(IllegalStateException::class.java) {
            admission.withTicket(1) {
                failed = it
                error("Synthetic inspect failure")
            }
        }
        admission.withTicket(1) { fresh ->
            assertTrue(admission.accepts(fresh))
            assertFalse(admission.accepts(completed))
            assertFalse(admission.accepts(failed))
        }
    }

    @Test fun revocationDoesNotWaitForBlockedPreparation() {
        val admission = PlaybackAdmission()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val accepted = AtomicBoolean(true)
        val worker =
            Thread {
                admission.withTicket(1) { ticket ->
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    accepted.set(admission.accepts(ticket))
                }
            }
        try {
            worker.start()
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            admission.invalidateParticipants(setOf(1))
            assertTrue(worker.isAlive)
            release.countDown()
            worker.join(1000)
            assertFalse(worker.isAlive)
            assertFalse(accepted.get())
        } finally {
            release.countDown()
            worker.join(1000)
        }
    }
}
