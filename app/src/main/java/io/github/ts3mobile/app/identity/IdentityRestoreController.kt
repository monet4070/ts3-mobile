package io.github.ts3mobile.app.identity

import io.github.ts3mobile.app.identity.backup.IdentityBackupException
import io.github.ts3mobile.app.identity.backup.validateBackupPassphrase
import io.github.ts3mobile.app.identity.documents.BackupDocument
import io.github.ts3mobile.app.identity.documents.IdentityImportException
import io.github.ts3mobile.app.identity.documents.ImportedIdentity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Main-thread preflight/confirmation ownership; repository alone owns durable qualification. */
internal class IdentityRestoreController(
    private val scope: CoroutineScope,
    private val repository: IdentityRepository,
    private val onChanged: () -> Unit,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val read: (
        BackupDocument,
        CharArray,
    ) -> ImportedIdentity = { document, passphrase -> ImportedIdentity.read(document, passphrase) },
) : AutoCloseable {
    private val mutableState = MutableStateFlow(IdentityRestoreState())
    val state = mutableState.asStateFlow()
    private var generation = 0L
    private var picker: Long? = null
    private var password: CharArray? = null
    private var candidate: ImportedIdentity? = null
    private var job: Job? = null
    private var closed = false

    fun beginImport(input: CharArray) {
        if (!canStart()) {
            input.fill('\u0000')
            return
        }
        val secret = input.copyOf()
        input.fill('\u0000')
        val token = ++generation
        try {
            validateBackupPassphrase(secret)
        } catch (error: IdentityBackupException) {
            secret.fill('\u0000')
            fail(token, error)
            return
        }
        password = secret
        picker = token
        mutableState.value = IdentityRestoreState(IdentityRestoreStatus.SELECTING, pickerRequest = token, pickerOutstanding = true)
    }

    fun claimPickerRequest(token: Long): Boolean {
        if (!current(token) || picker != token || mutableState.value.pickerRequest != token) return false
        mutableState.value = mutableState.value.copy(pickerRequest = null)
        return true
    }

    fun picked(document: BackupDocument?) {
        val token = picker ?: return
        picker = null
        mutableState.value = mutableState.value.copy(pickerRequest = null, pickerOutstanding = false)
        if (!current(token)) return
        val secret = password ?: return
        password = null
        if (document == null) {
            secret.fill('\u0000')
            mutableState.value = IdentityRestoreState(IdentityRestoreStatus.CANCELLED)
            return
        }
        mutableState.value = IdentityRestoreState(IdentityRestoreStatus.READING)
        job =
            scope.launch {
                var imported: ImportedIdentity? = null
                try {
                    withContext(io) { imported = read(document, secret) }
                    val previous = repository.snapshot()
                    if (current(token)) {
                        candidate = imported
                        imported = null
                        mutableState.value =
                            IdentityRestoreState(
                                IdentityRestoreStatus.PREVIEW,
                                IdentityReplacementPreview(previous, checkNotNull(candidate).summary, rollback = false),
                            )
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    fail(token, error)
                } finally {
                    imported?.close()
                    secret.fill('\u0000')
                }
            }
        job?.invokeOnCompletion { secret.fill('\u0000') }
    }

    fun pickerUnavailable() {
        val token = picker ?: return
        picked(null)
        if (current(token)) mutableState.value = IdentityRestoreState(IdentityRestoreStatus.PICKER_UNAVAILABLE)
    }

    fun beginRollback() {
        if (!canStart()) return
        preparePreview(rollback = true)
    }

    /** Refreshes the displayed old state only; user must press confirm again afterwards. */
    fun refreshConfirmation() {
        val preview = mutableState.value.preview ?: return
        if (closed || mutableState.value.working) return
        preparePreview(preview.rollback)
    }

    private fun preparePreview(rollback: Boolean) {
        val token = ++generation
        mutableState.value = mutableState.value.copy(status = IdentityRestoreStatus.READING)
        job =
            scope.launch {
                try {
                    val previous = repository.snapshot()
                    val proposed =
                        if (rollback) {
                            previous.rollback ?: throw IdentityOperationException(IdentityFailure.NO_ROLLBACK)
                        } else {
                            checkNotNull(candidate).summary
                        }
                    if (current(token)) {
                        mutableState.value =
                            IdentityRestoreState(
                                IdentityRestoreStatus.PREVIEW, IdentityReplacementPreview(previous, proposed, rollback),
                            )
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    fail(token, error)
                }
            }
    }

    fun confirm() {
        val preview = mutableState.value.preview ?: return
        if (closed || mutableState.value.status != IdentityRestoreStatus.PREVIEW) return
        val token = ++generation
        var committing = candidate
        candidate = null
        mutableState.value = mutableState.value.copy(status = IdentityRestoreStatus.COMMITTING)
        job =
            scope.launch {
                try {
                    if (preview.rollback) {
                        repository.rollback(preview.previous.revision)
                    } else {
                        repository.replace(checkNotNull(committing).materialForCommit(), preview.previous.revision)
                    }
                    if (current(token)) mutableState.value = IdentityRestoreState(IdentityRestoreStatus.DONE)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: IdentityOperationException) {
                    if (current(token) && error.failure in retryableConfirmationFailures) {
                        candidate = committing
                        committing = null
                        mutableState.value = mutableState.value.copy(status = IdentityRestoreStatus.FAILED, storeFailure = error.failure)
                    } else {
                        fail(token, error)
                    }
                } catch (error: Exception) {
                    fail(token, error)
                } finally {
                    committing?.close()
                }
            }
        job?.invokeOnCompletion {
            committing?.close()
            // Even a cancelled UI caller may have completed the durable transaction.
            scope.launch { if (!closed) onChanged() }
        }
    }

    fun cancel() {
        if (closed || mutableState.value.status == IdentityRestoreStatus.CANCELLING) return
        val token = ++generation
        job?.cancel()
        password?.fill('\u0000')
        password = null
        candidate?.close()
        candidate = null
        val settling = job?.isCompleted == false
        mutableState.value =
            IdentityRestoreState(
                if (settling) IdentityRestoreStatus.CANCELLING else IdentityRestoreStatus.CANCELLED,
                pickerOutstanding = picker != null,
            )
        if (settling) {
            job?.invokeOnCompletion {
                scope.launch {
                    if (current(token)) {
                        mutableState.value =
                            IdentityRestoreState(
                                IdentityRestoreStatus.CANCELLED, pickerOutstanding = picker != null,
                            )
                    }
                }
            }
        }
    }

    override fun close() {
        cancel()
        closed = true
        picker = null
    }

    private fun canStart(): Boolean = !closed && !mutableState.value.active && job?.isCompleted != false

    private fun current(token: Long): Boolean = !closed && generation == token

    private fun fail(
        token: Long,
        error: Exception,
    ) {
        if (!current(token)) return
        candidate?.close()
        candidate = null
        mutableState.value =
            IdentityRestoreState(
                status = IdentityRestoreStatus.FAILED,
                backupFailure = (error as? IdentityBackupException)?.failure,
                importFailure = (error as? IdentityImportException)?.failure,
                storeFailure = (error as? IdentityOperationException)?.failure,
            )
    }

    private companion object {
        val retryableConfirmationFailures = setOf(IdentityFailure.BUSY, IdentityFailure.STALE_CONFIRMATION)
    }
}
