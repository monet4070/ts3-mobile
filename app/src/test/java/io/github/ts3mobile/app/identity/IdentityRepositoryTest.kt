package io.github.ts3mobile.app.identity

import androidx.datastore.preferences.core.edit
import io.github.ts3mobile.protocol.Ts3IdentityCodec
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class IdentityRepositoryTest {
    @Test fun cancellationAfterCommitCannotReleaseMaintenanceBeforeAcknowledgementAndReadback() =
        runTest {
            val fixture = IdentityStoreFixture()
            val first = Ts3IdentityCodec.generate(0)
            val second = Ts3IdentityCodec.generate(0)
            fixture.edit { it[IdentityStore.ACTIVE] = fixture.cipher.encrypt(first.toByteArray()) }
            val repository = IdentityRepository(fixture.storage, dispatcher = StandardTestDispatcher(testScheduler))
            val revision = repository.snapshot().revision
            val ack = CompletableDeferred<Unit>()
            val entered = CompletableDeferred<Unit>()
            val commitCall = fixture.calls + 1
            fixture.after = {
                if (it == commitCall) {
                    entered.complete(Unit)
                    ack.await()
                }
            }
            val ui = launch { repository.replace(second, revision) }
            entered.await()
            ui.cancel()
            runCurrent()
            assertFalse(ui.isCompleted)
            assertEquals(IdentityUseState.MAINTENANCE, repository.gate.state.value)
            assertNull(repository.gate.beginConnection())
            assertNull(repository.gate.beginMaintenance())
            ack.complete(Unit)
            ui.join()
            assertEquals(IdentityUseState.IDLE, repository.gate.state.value)
            assertEquals(Ts3IdentityCodec.inspect(second), repository.snapshot().active)
            assertNull(fixture.values.value[IdentityStore.PENDING])
        }

    @Test fun cancelledCallerBeforeMaintenanceEntryCannotStartADurableCommit() =
        runTest {
            val fixture = IdentityStoreFixture()
            val first = Ts3IdentityCodec.generate(0)
            fixture.edit { it[IdentityStore.ACTIVE] = fixture.cipher.encrypt(first.toByteArray()) }
            val repository = IdentityRepository(fixture.storage, dispatcher = StandardTestDispatcher(testScheduler))
            val before = repository.snapshot()
            val original = fixture.values.value
            val second = Ts3IdentityCodec.generate(0)
            val ui =
                launch {
                    currentCoroutineContext().cancel()
                    repository.replace(second, before.revision)
                }
            ui.join()
            assertEquals(original, fixture.values.value)
            assertEquals(IdentityUseState.IDLE, repository.gate.state.value)
        }

    @Test fun connectionAndAllSupersededWorkBlockMaintenanceUntilActuallySettled() =
        runTest {
            val gate = IdentityUseGate()
            val old = checkNotNull(IdentityConnectionLease.acquire(gate))
            val finish = CompletableDeferred<Unit>()
            val job = checkNotNull(old.prepare(backgroundScope) { withContext(NonCancellable) { finish.await() } })
            job.start()
            runCurrent()
            old.retire()
            job.cancel()
            val newer = checkNotNull(IdentityConnectionLease.acquire(gate))
            newer.retire()
            assertFalse(job.isCompleted)
            assertNull(gate.beginMaintenance())
            finish.complete(Unit)
            job.join()
            assertNotNull(gate.beginMaintenance())
        }

    @Test fun retiringRefusesLateJobsAndDoesNotReleaseANewerOwner() =
        runTest {
            val gate = IdentityUseGate()
            val old = checkNotNull(IdentityConnectionLease.acquire(gate))
            old.retire()
            assertNull(old.prepare(backgroundScope) { error("must not launch") })
            val newer = checkNotNull(IdentityConnectionLease.acquire(gate))
            old.retire()
            assertNull(gate.beginMaintenance())
            newer.retire()
            assertEquals(IdentityUseState.IDLE, gate.state.value)
        }

    @Test fun cancellationBeforeFirstCreationAcknowledgementRetainsTheConnectionOwner() =
        runTest {
            val fixture = IdentityStoreFixture()
            val repository = IdentityRepository(fixture.storage, dispatcher = StandardTestDispatcher(testScheduler))
            val owner = checkNotNull(IdentityConnectionLease.acquire(repository.gate))
            val ack = CompletableDeferred<Unit>()
            val entered = CompletableDeferred<Unit>()
            fixture.after = {
                entered.complete(Unit)
                ack.await()
            }
            val job = checkNotNull(owner.prepare(backgroundScope) { repository.getOrCreateForConnection() })
            job.start()
            entered.await()
            job.cancel()
            owner.retire()
            assertNull(repository.gate.beginMaintenance())
            ack.complete(Unit)
            job.cancelAndJoin()
            assertTrue(fixture.values.value[IdentityStore.ACTIVE] != null)
            assertEquals(IdentityUseState.IDLE, repository.gate.state.value)
        }
}
