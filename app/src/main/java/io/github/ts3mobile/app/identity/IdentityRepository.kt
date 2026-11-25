package io.github.ts3mobile.app.identity

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/** Application lifetime; contains no Activity, Service, passphrase or public private-key state. */
internal class IdentityRepository(
    private val storage: IdentityStore,
    val gate: IdentityUseGate = IdentityUseGate(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val activeVersion = MutableStateFlow(IdentityReadVersion())
    val readVersion = activeVersion.asStateFlow()

    suspend fun getOrCreateForConnection(): String =
        withContext(dispatcher) {
            storage.getOrCreate().also { activeVersion.update { version -> version.next(changing = version.changing) } }
        }

    suspend fun snapshot(): IdentitySnapshot = withContext(dispatcher) { storage.snapshot() }

    suspend fun readForBackup(): String = maintain { storage.readMaterial() }

    suspend fun replace(
        material: String,
        expectedRevision: String,
    ): IdentitySnapshot =
        changeActive {
            storage.replace(
                material,
                expectedRevision,
            )
        }

    suspend fun rollback(expectedRevision: String): IdentitySnapshot = changeActive { storage.rollback(expectedRevision) }

    private suspend fun changeActive(operation: suspend () -> IdentitySnapshot): IdentitySnapshot =
        maintain {
            activeVersion.update { it.next(changing = true) }
            try {
                operation()
            } finally {
                activeVersion.update { it.next(changing = false) }
            }
        }

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

internal data class IdentityReadVersion(val version: Long = 0, val changing: Boolean = false) {
    fun next(changing: Boolean) = IdentityReadVersion(version + 1, changing)
}
