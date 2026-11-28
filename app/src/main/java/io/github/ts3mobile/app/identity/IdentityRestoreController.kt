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
    private class PendingPicker(val token: Long, val source: IdentityImportSource, val password: CharArray? = null) {
        fun clear() = password?.fill('\u0000')
    }

    private val mutableState = MutableStateFlow(IdentityRestoreState())
    val state = mutableState.asStateFlow()
    private var generation = 0L
    private var picker: PendingPicker? = null
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
            fail(token, error, IdentityImportSource.BACKUP)
            return
        }
        requestPicker(PendingPicker(token, IdentityImportSource.BACKUP, secret))
    }

    fun beginDesktopImport() {
        if (!canStart()) return
        requestPicker(PendingPicker(++generation, IdentityImportSource.DESKTOP))
    }

    private fun requestPicker(request: PendingPicker) {
        picker = request
        mutableState.value =
            IdentityRestoreState(
                IdentityRestoreStatus.SELECTING,
                pickerRequest = request.token,
                pickerOutstanding = true,
                source = request.source,
            )
    }

    fun claimPickerRequest(token: Long): Boolean {
        if (!current(token) || picker?.token != token || mutableState.value.pickerRequest != token) return false
        mutableState.value = mutableState.value.copy(pickerRequest = null)
        return true
    }

    fun picked(document: BackupDocument?) {
        val request = picker ?: return
        val token = request.token
        picker = null
        mutableState.value = mutableState.value.copy(pickerRequest = null, pickerOutstanding = false)
        if (!current(token)) {
            request.clear()
            return
        }
        if (document == null) {
            request.clear()
            mutableState.value = IdentityRestoreState(IdentityRestoreStatus.CANCELLED)
            return
        }
        mutableState.value = IdentityRestoreState(IdentityRestoreStatus.READING, source = request.source)
        job =
            scope.launch {
                var imported: ImportedIdentity? = null
                try {
                    withContext(io) {
                        imported =
                            when (request.source) {
                                IdentityImportSource.BACKUP -> read(document, checkNotNull(request.password))
                                IdentityImportSource.DESKTOP -> ImportedIdentity.readDesktop(document)
                            }
                    }
                    val previous = repository.snapshot()
                    if (current(token)) {
                        candidate = imported
                        imported = null
                        publishPreview(IdentityReplacementPreview(previous, checkNotNull(candidate).summary, false, request.source))
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    fail(token, error)
                } finally {
                    imported?.close()
                    request.clear()
                }
            }
        job?.invokeOnCompletion { request.clear() }
    }

    fun pickerUnavailable() {
        val token = picker?.token ?: return
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
        preparePreview(preview.rollback, preview.source)
    }

    private fun preparePreview(
        rollback: Boolean,
        source: IdentityImportSource? = null,
    ) {
        val token = ++generation
        mutableState.value = mutableState.value.copy(status = IdentityRestoreStatus.READING, source = source)
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
                    if (current(token)) publishPreview(IdentityReplacementPreview(previous, proposed, rollback, source))
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    fail(token, error)
                }
            }
    }

    private fun publishPreview(preview: IdentityReplacementPreview) {
        mutableState.value =
            if (preview.isAlreadyCurrent) {
                candidate?.close()
                candidate = null
                IdentityRestoreState(IdentityRestoreStatus.ALREADY_CURRENT, source = preview.source)
            } else {
                IdentityRestoreState(IdentityRestoreStatus.PREVIEW, preview, source = preview.source)
            }
    }

    fun confirm(allowLowerSecurity: Boolean = false) {
        val preview = mutableState.value.preview ?: return
        if (closed || mutableState.value.status != IdentityRestoreStatus.PREVIEW) return
        if (preview.isDesktopDowngrade && !allowLowerSecurity) return
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
                    if (current(token)) mutableState.value = IdentityRestoreState(IdentityRestoreStatus.DONE, source = preview.source)
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
        picker?.clear()
        if (mutableState.value.pickerRequest != null) picker = null
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
        source: IdentityImportSource? = mutableState.value.source,
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
                source = source,
            )
    }

    private companion object {
        val retryableConfirmationFailures = setOf(IdentityFailure.BUSY, IdentityFailure.STALE_CONFIRMATION)
    }
}
