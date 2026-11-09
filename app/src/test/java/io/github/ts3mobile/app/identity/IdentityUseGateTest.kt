package io.github.ts3mobile.app.identity

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class IdentityUseGateTest {
    @Test fun handshakingReconnectingOrExitingOwnerBlocksMaintenanceUntilActuallySettled() {
        val gate = IdentityUseGate()
        val connection = checkNotNull(gate.beginConnection())
        // UI may already show ERROR or DISCONNECTED; ownership still exists.
        repeat(3) { assertNull(gate.beginMaintenance()) }
        gate.finishConnection(connection)
        assertNotNull(gate.beginMaintenance())
    }

    @Test fun maintenanceBlocksConnectRegistrationAndAnotherMutationUntilFinallyRelease() {
        val gate = IdentityUseGate()
        val lease = checkNotNull(gate.beginMaintenance())
        assertTrue(gate.ownsMaintenance(lease))
        assertNull(gate.beginConnection())
        assertNull(gate.beginMaintenance())
        gate.finishMaintenance(lease)
        assertFalse(gate.ownsMaintenance(lease))
        assertNotNull(gate.beginConnection())
    }

    @Test fun lateOldExitOrMaintenanceReleaseCannotMakeANewerOwnerIdle() {
        val gate = IdentityUseGate()
        val first = checkNotNull(gate.beginConnection())
        val second = checkNotNull(gate.beginConnection())
        gate.finishConnection(first)
        assertNull(gate.beginMaintenance())
        gate.finishConnection(second)
        val oldMaintenance = checkNotNull(gate.beginMaintenance())
        gate.finishMaintenance(oldMaintenance)
        val current = checkNotNull(gate.beginMaintenance())
        gate.finishMaintenance(oldMaintenance)
        assertTrue(gate.ownsMaintenance(current))
    }

    @Test fun newerOwnerFinishingCannotReleaseUnfinishedSupersededWork() {
        val gate = IdentityUseGate()
        val old = checkNotNull(gate.beginConnection())
        val newer = checkNotNull(gate.beginConnection())
        gate.finishConnection(newer)
        assertNull(gate.beginMaintenance())
        gate.finishConnection(old)
        assertNotNull(gate.beginMaintenance())
    }

    @Test fun concurrentConnectAndCommitQualificationHaveExactlyOneWinner() {
        repeat(100) {
            val gate = IdentityUseGate()
            val start = CountDownLatch(1)
            val done = CountDownLatch(2)
            val connection = AtomicReference<Long?>()
            val maintenance = AtomicReference<Long?>()
            val connect =
                Thread {
                    start.await()
                    connection.set(gate.beginConnection())
                    done.countDown()
                }.apply { start() }
            val maintain =
                Thread {
                    start.await()
                    maintenance.set(gate.beginMaintenance())
                    done.countDown()
                }.apply { start() }
            start.countDown()
            assertTrue(done.await(2, TimeUnit.SECONDS))
            connect.join()
            maintain.join()
            assertTrue((connection.get() != null) xor (maintenance.get() != null))
        }
    }
}
