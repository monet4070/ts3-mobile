package io.github.ts3mobile.app.identity

import io.github.ts3mobile.app.identity.backup.IdentityBackupException
import io.github.ts3mobile.app.identity.backup.IdentityBackupFailure
import io.github.ts3mobile.app.identity.backup.validateBackupPassphrase
import io.github.ts3mobile.app.identity.documents.BackupDocument
import io.github.ts3mobile.app.identity.documents.PreparedIdentityBackup
import io.github.ts3mobile.protocol.Ts3IdentitySummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal enum class IdentityExportStatus {
    READY,
    LOADING,
    PREPARING,
    SELECTING,
    WRITING,
    VERIFIED,
    UNVERIFIED,
    CANCELLED,
    INVALID_PASSWORD,
    PASSWORD_MISMATCH,
    PICKER_UNAVAILABLE,
    CONNECTION_BUSY,
    IDENTITY_UNAVAILABLE,
    FAILED,
}

internal data class IdentityExportState(
    val snapshot: IdentitySnapshot? = null,
    val status: IdentityExportStatus = IdentityExportStatus.LOADING,
    val pickerRequest: Long? = null,
    val pickerOutstanding: Boolean = false,
    val exported: Ts3IdentitySummary? = null,
) {
    val working: Boolean get() = status in setOf(IdentityExportStatus.LOADING, IdentityExportStatus.PREPARING, IdentityExportStatus.WRITING)
}

/** Main-thread operation ownership; private resources never enter state or saved state. */
internal class IdentityExportController(
    private val scope: CoroutineScope,
    private val readIdentity: suspend () -> String,
    private val readSnapshot: suspend () -> IdentitySnapshot,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val prepare: (String, CharArray) -> PreparedIdentityBackup = { material, password ->
        PreparedIdentityBackup.prepare(material, password)
    },
) : AutoCloseable {
    private val mutableState = MutableStateFlow(IdentityExportState())
    val state = mutableState.asStateFlow()
    private var generation = 0L
    private var job: Job? = null
    private var pending: PreparedIdentityBackup? = null
    private var picker: Long? = null
    private var closed = false

    fun refresh() {
        if (closed || picker != null || mutableState.value.working && job != null) return
        val token = ++generation
        mutableState.value = mutableState.value.copy(status = IdentityExportStatus.LOADING)
        job =
            scope.launch {
                try {
                    val snapshot = readSnapshot()
                    if (current(token)) mutableState.value = IdentityExportState(snapshot, IdentityExportStatus.READY)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    publish(token, IdentityExportStatus.IDENTITY_UNAVAILABLE)
                }
            }
    }

    /** Takes ownership of both input arrays, including validation and rejected requests. */
    fun export(
        password: CharArray,
        confirmation: CharArray,
    ) {
        if (closed || picker != null || mutableState.value.working) {
            password.fill('\u0000')
            confirmation.fill('\u0000')
            return
        }
        val token = ++generation
        pending?.close()
        pending = null
        val matches = password.contentEquals(confirmation)
        val secret = password.copyOf()
        password.fill('\u0000')
        confirmation.fill('\u0000')
        try {
            if (!matches) {
                publish(token, IdentityExportStatus.PASSWORD_MISMATCH)
                secret.fill('\u0000')
                return
            }
            validateBackupPassphrase(secret)
        } catch (_: IdentityBackupException) {
            publish(token, IdentityExportStatus.INVALID_PASSWORD)
            secret.fill('\u0000')
            return
        }
        mutableState.value = mutableState.value.copy(status = IdentityExportStatus.PREPARING, exported = null)
        job =
            scope.launch {
                var prepared: PreparedIdentityBackup? = null
                try {
                    // Assign inside withContext so cancellation at dispatch-back still reaches cleanup.
                    withContext(io) { prepared = prepare(readIdentity(), secret) }
                    if (current(token)) {
                        pending = prepared
                        prepared = null
                        picker = token
                        mutableState.value =
                            mutableState.value.copy(
                                status = IdentityExportStatus.SELECTING, pickerRequest = token,
                                pickerOutstanding = true, exported = pending?.summary,
                            )
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: IdentityOperationException) {
                    publish(
                        token,
                        if (error.failure == IdentityFailure.BUSY) {
                            IdentityExportStatus.CONNECTION_BUSY
                        } else {
                            IdentityExportStatus.IDENTITY_UNAVAILABLE
                        },
                    )
                } catch (error: IdentityBackupException) {
                    publish(
                        token,
                        if (error.failure == IdentityBackupFailure.INVALID_PASSPHRASE) {
                            IdentityExportStatus.INVALID_PASSWORD
                        } else {
                            IdentityExportStatus.FAILED
                        },
                    )
                } catch (_: Exception) {
                    publish(token, IdentityExportStatus.FAILED)
                } finally {
                    prepared?.close()
                    secret.fill('\u0000')
                }
            }
        // A scope cancelled before the body starts must not retain the supplied password.
        job?.invokeOnCompletion { secret.fill('\u0000') }
    }

    fun claimPickerRequest(token: Long): Boolean {
        if (!current(token) || picker != token || mutableState.value.pickerRequest != token) return false
        mutableState.value = mutableState.value.copy(pickerRequest = null)
        return true
    }

    /** ActivityResult callbacks carry no token; the single outstanding slot owns their identity. */
    fun picked(document: BackupDocument?) {
        val token = picker ?: return
        picker = null
        mutableState.value = mutableState.value.copy(pickerOutstanding = false, pickerRequest = null)
        if (!current(token)) return
        val prepared = pending ?: return
        pending = null
        if (document == null) {
            prepared.close()
            publish(token, IdentityExportStatus.CANCELLED)
            return
        }
        publish(token, IdentityExportStatus.WRITING)
        job =
            scope.launch {
                try {
                    withContext(io) { prepared.writeAndVerify(document) }
                    publish(token, IdentityExportStatus.VERIFIED)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    publish(token, IdentityExportStatus.UNVERIFIED)
                } finally {
                    prepared.close()
                }
            }
        job?.invokeOnCompletion { prepared.close() }
    }

    fun pickerUnavailable() {
        val token = picker ?: return
        picked(null)
        publish(token, IdentityExportStatus.PICKER_UNAVAILABLE)
    }

    fun cancel() {
        if (closed) return
        generation++
        job?.cancel()
        pending?.close()
        pending = null
        mutableState.value = mutableState.value.copy(status = IdentityExportStatus.CANCELLED, pickerRequest = null)
    }

    override fun close() {
        cancel()
        closed = true
        picker = null
    }

    private fun current(token: Long): Boolean = !closed && generation == token

    private fun publish(
        token: Long,
        status: IdentityExportStatus,
    ) {
        if (current(token)) mutableState.value = mutableState.value.copy(status = status)
    }
}
