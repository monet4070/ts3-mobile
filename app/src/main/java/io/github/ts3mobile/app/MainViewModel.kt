package io.github.ts3mobile.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.ts3mobile.app.history.ServerHistoryEntry
import io.github.ts3mobile.app.history.ServerHistoryPrefill
import io.github.ts3mobile.app.history.successServerHistoryStore
import io.github.ts3mobile.app.service.MicrophoneMode
import io.github.ts3mobile.app.settings.ConnectionFormEdits
import io.github.ts3mobile.app.settings.ConnectionSettingsVault
import io.github.ts3mobile.app.settings.restoreRememberedConnection
import io.github.ts3mobile.app.settings.toRememberedConnection
import io.github.ts3mobile.protocol.ServerConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException

data class ConnectionFormState(
    val host: String = "",
    val port: String = "9987",
    val nickname: String = "TS3 Mobile",
    val password: String = "",
    val submitted: Boolean = false,
    val memoryError: Boolean = false,
) {
    fun toServerConfigOrNull(): ServerConfig? {
        val config =
            ServerConfig(
                host = host,
                port = port.toIntOrNull() ?: return null,
                nickname = nickname,
                password = password,
            ).normalized()
        return config.takeIf { it.validationError() == null }
    }
}

/**
 * Applies a selected history entry to the form. Choosing a different endpoint
 * clears the password so one server's password is never sent to another
 * address; the same endpoint keeps whatever the user already typed.
 */
internal fun ConnectionFormState.withSelectedHistoryEntry(entry: ServerHistoryEntry): ConnectionFormState {
    val switchingEndpoint = host != entry.host || port != entry.port.toString()
    return copy(
        host = entry.host,
        port = entry.port.toString(),
        password = if (switchingEndpoint) "" else password,
        submitted = false,
    )
}

class MainViewModel(application: Application) : AndroidViewModel(application) {
    // Pending permission flow survives activity recreation; form memory is encrypted separately.
    internal var pendingConnection: ServerConfig? = null
    internal var notificationPermissionResolved = false
    internal var pendingMicrophoneMode: MicrophoneMode? = null
    private val historyStore = successServerHistoryStore(application)
    private val settingsVault = ConnectionSettingsVault(application)
    private val settingsLoaded = CompletableDeferred<Unit>()
    private val saveRequests = Channel<Unit>(Channel.CONFLATED)
    private val saveMutex = Mutex()
    private var editedFields = ConnectionFormEdits()
    private var hasRememberedConnection = false
    private var submitting = false
    private val mutableForm = MutableStateFlow(ConnectionFormState())
    val form = mutableForm.asStateFlow()
    private val mutableHistory = MutableStateFlow<List<ServerHistoryEntry>>(emptyList())
    val history = mutableHistory.asStateFlow()
    private val mutableHistoryError = MutableStateFlow(false)
    val historyError = mutableHistoryError.asStateFlow()

    /** True once the user edited the address or port, so a late load cannot overwrite it. */
    private var connectionFieldsEdited = false
    private var historyLoaded = false

    init {
        viewModelScope.launch {
            try {
                settingsVault.read()?.let { saved ->
                    hasRememberedConnection = true
                    mutableForm.update { it.restoreRememberedConnection(saved, editedFields) }
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutableForm.update { it.copy(memoryError = true) }
            } finally {
                settingsLoaded.complete(Unit)
            }
        }
        viewModelScope.launch {
            settingsLoaded.await()
            for (request in saveRequests) {
                delay(150L)
                rememberCurrentForm()
            }
        }
        viewModelScope.launch {
            settingsLoaded.await()
            historyStore.entries
                .retryWhen { error, attempt ->
                    if (error !is IOException) throw error
                    mutableHistoryError.value = true
                    delay((attempt + 1).coerceAtMost(5) * 1_000L)
                    true
                }
                .catch { error ->
                    if (error is CancellationException) throw error
                    mutableHistoryError.value = true
                }
                .collect { entries ->
                    mutableHistoryError.value = false
                    onHistoryChanged(entries)
                }
        }
    }

    fun setHost(value: String) {
        editedFields = editedFields.copy(host = true, password = true)
        connectionFieldsEdited = true
        update { copy(host = value, password = if (value == host) password else "", submitted = false) }
    }

    fun setPort(value: String) {
        editedFields = editedFields.copy(port = true, password = true)
        connectionFieldsEdited = true
        update { copy(port = value.filter(Char::isDigit), password = if (value == port) password else "", submitted = false) }
    }

    fun setNickname(value: String) {
        editedFields = editedFields.copy(nickname = true)
        update { copy(nickname = value, submitted = false) }
    }

    fun setPassword(value: String) {
        editedFields = editedFields.copy(password = true)
        update { copy(password = value, submitted = false) }
    }

    fun applyHistoryEntry(entry: ServerHistoryEntry) {
        editedFields = editedFields.copy(host = true, port = true, password = true)
        connectionFieldsEdited = true
        mutableHistoryError.value = false
        update { withSelectedHistoryEntry(entry) }
    }

    fun removeHistoryEntry(entry: ServerHistoryEntry) {
        viewModelScope.launch {
            try {
                historyStore.remove(entry)
                mutableHistoryError.value = false
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutableHistoryError.value = true
            }
        }
    }

    fun submit(): ServerConfig? {
        mutableForm.update { it.copy(submitted = true) }
        return mutableForm.value.toServerConfigOrNull()
    }

    fun submitAndRemember(onReady: (ServerConfig) -> Unit) {
        if (submitting) return
        val config = submit() ?: return
        submitting = true
        viewModelScope.launch {
            try {
                settingsLoaded.await()
                rememberCurrentForm()
                onReady(config)
            } finally {
                submitting = false
            }
        }
    }

    private suspend fun rememberCurrentForm() =
        saveMutex.withLock {
            try {
                settingsVault.write(mutableForm.value.toRememberedConnection())
                mutableForm.update { it.copy(memoryError = false) }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutableForm.update { it.copy(memoryError = true) }
            }
        }

    private inline fun update(transform: ConnectionFormState.() -> ConnectionFormState) {
        mutableForm.update { it.transform() }
        saveRequests.trySend(Unit)
    }

    private fun onHistoryChanged(entries: List<ServerHistoryEntry>) {
        mutableHistory.value = entries
        if (historyLoaded) return
        historyLoaded = true
        if (hasRememberedConnection) return
        val prefill = ServerHistoryPrefill.hostAndPort(entries, connectionFieldsEdited) ?: return
        mutableForm.update { it.copy(host = prefill.first, port = prefill.second) }
        if (!mutableForm.value.memoryError) saveRequests.trySend(Unit)
    }
}
