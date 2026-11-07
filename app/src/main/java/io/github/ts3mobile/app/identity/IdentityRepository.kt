package io.github.ts3mobile.app.identity

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Application lifetime; contains no Activity, Service, passphrase or public private-key state. */
internal class IdentityRepository(
    private val storage: IdentityStore,
    val gate: IdentityUseGate = IdentityUseGate(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun getOrCreateForConnection(): String = withContext(dispatcher) { storage.getOrCreate() }

    suspend fun snapshot(): IdentitySnapshot = withContext(dispatcher) { storage.snapshot() }

    suspend fun readForBackup(): String = maintain { storage.readMaterial() }

    suspend fun replace(
        material: String,
        expectedRevision: String,
    ): IdentitySnapshot =
        maintain {
            storage.replace(
                material,
                expectedRevision,
            )
        }

    suspend fun rollback(expectedRevision: String): IdentitySnapshot = maintain { storage.rollback(expectedRevision) }

    private suspend fun <T> maintain(operation: suspend () -> T): T {
        currentCoroutineContext().ensureActive()
        val token = gate.beginMaintenance() ?: throw IdentityOperationException(IdentityFailure.BUSY)
        // Includes dispatch, durable acknowledgement and readback; caller cancellation cannot
        // strand a lease or allow a connection while DataStore can still commit.
        return withContext(NonCancellable + dispatcher) {
            try {
                operation()
            } finally {
                gate.finishMaintenance(token)
            }
        }
    }
}
