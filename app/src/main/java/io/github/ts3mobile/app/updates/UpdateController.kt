package io.github.ts3mobile.app.updates

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.net.SocketTimeoutException

/** Main-confined page state. A replaced channel cannot publish an older request's result. */
internal class UpdateController(
    private val scope: CoroutineScope,
    private val source: UpdateSource,
    private val store: UpdateChannelStore,
) : AutoCloseable {
    private val mutable = MutableStateFlow(UpdateState())
    val state = mutable.asStateFlow()
    private var preferences: Job? = null
    private var request: Job? = null
    private var revision = 0L
    private var closed = false
    private val requestMutex = Mutex()

    init {
        readPreference()
    }

    fun check() {
        if (closed || mutable.value.preferenceStatus != UpdatePreferenceStatus.SAVED || request?.isActive == true) return
        val token = ++revision
        val channel = mutable.value.channel
        mutable.value = mutable.value.copy(check = UpdateCheck.Checking)
        request =
            scope.launch {
                val result =
                    try {
                        requestMutex.withLock { source.latest(channel) }?.let { UpdateCheck.Complete(it) } ?: UpdateCheck.NoRelease
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: UpdateCheckException) {
                        UpdateCheck.Failed(error.failure)
                    } catch (_: SocketTimeoutException) {
                        UpdateCheck.Failed(UpdateFailure.TIMEOUT)
                    } catch (_: IOException) {
                        UpdateCheck.Failed(UpdateFailure.NETWORK)
                    } catch (error: Exception) {
                        System.err.println("TS3_UPDATE: check failed: ${error.javaClass.simpleName}")
                        UpdateCheck.Failed(UpdateFailure.SERVER)
                    }
                if (!closed && revision == token) mutable.value = mutable.value.copy(check = result)
            }
    }

    fun select(channel: UpdateChannel) {
        if (closed || mutable.value.preferenceStatus in
            setOf(
                UpdatePreferenceStatus.LOADING,
                UpdatePreferenceStatus.SAVING,
                UpdatePreferenceStatus.READ_FAILED,
            )
        ) {
            return
        }
        if (mutable.value.channel == channel && mutable.value.preferenceStatus == UpdatePreferenceStatus.SAVED) return
        revision++
        request?.cancel()
        mutable.value = UpdateState(channel, UpdatePreferenceStatus.SAVING)
        writePreference(channel)
    }

    fun retryPreference() {
        if (closed) return
        when (mutable.value.preferenceStatus) {
            UpdatePreferenceStatus.READ_FAILED -> readPreference()
            UpdatePreferenceStatus.WRITE_FAILED -> {
                mutable.value = mutable.value.copy(preferenceStatus = UpdatePreferenceStatus.SAVING)
                writePreference(mutable.value.channel)
            }
            else -> Unit
        }
    }

    private fun readPreference() {
        mutable.value = mutable.value.copy(preferenceStatus = UpdatePreferenceStatus.LOADING)
        preferences =
            scope.launch {
                try {
                    val channel = store.read()
                    if (!closed) {
                        mutable.value = UpdateState(channel, UpdatePreferenceStatus.SAVED)
                        check()
                    }
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    if (!closed) mutable.value = mutable.value.copy(preferenceStatus = UpdatePreferenceStatus.READ_FAILED)
                }
            }
    }

    private fun writePreference(channel: UpdateChannel) {
        preferences =
            scope.launch {
                try {
                    store.write(channel)
                    if (!closed) {
                        mutable.value = mutable.value.copy(preferenceStatus = UpdatePreferenceStatus.SAVED)
                        // The canceled IO call may still be unwinding; it no longer owns page state.
                        request = null
                        check()
                    }
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    if (!closed) mutable.value = mutable.value.copy(preferenceStatus = UpdatePreferenceStatus.WRITE_FAILED)
                }
            }
    }

    override fun close() {
        closed = true
        revision++
        preferences?.cancel()
        request?.cancel()
    }
}
