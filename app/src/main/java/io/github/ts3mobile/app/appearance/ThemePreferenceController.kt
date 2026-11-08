package io.github.ts3mobile.app.appearance

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** One bounded operation at a time; visual preferences have no service or audio collaborator. */
internal class ThemePreferenceController(private val scope: CoroutineScope, private val store: ThemePreferenceStore) : AutoCloseable {
    private val lock = Any()
    private val mutable = MutableStateFlow(ThemePreferenceState())
    val state = mutable.asStateFlow()
    private var closed = false
    private var operation: Job? = null
    private var pendingReset = false

    init {
        read()
    }

    fun select(mode: ThemeMode) {
        synchronized(lock) {
            val current = mutable.value
            if (closed || current.busy || current.status == ThemePreferenceStatus.READ_FAILED) return
            if (current.mode == mode && current.status == ThemePreferenceStatus.SAVED) return
            write(mode, reset = false)
        }
    }

    fun retry() {
        synchronized(lock) {
            if (closed) return
            when (mutable.value.status) {
                ThemePreferenceStatus.READ_FAILED -> read()
                ThemePreferenceStatus.WRITE_FAILED -> write(mutable.value.mode, reset = false)
                else -> Unit
            }
        }
    }

    fun reset() {
        synchronized(lock) {
            if (!closed && !mutable.value.busy) write(ThemeMode.LIGHT, reset = true)
        }
    }

    private fun read() {
        mutable.value = mutable.value.copy(status = ThemePreferenceStatus.LOADING)
        operation =
            scope.launch {
                try {
                    val mode = store.read()
                    synchronized(lock) { if (!closed) mutable.value = ThemePreferenceState(mode, ThemePreferenceStatus.SAVED) }
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    synchronized(lock) { if (!closed) mutable.value = mutable.value.copy(status = ThemePreferenceStatus.READ_FAILED) }
                }
            }
    }

    private fun write(
        mode: ThemeMode,
        reset: Boolean,
    ) {
        val shouldReset = reset || pendingReset
        pendingReset = shouldReset
        mutable.value = ThemePreferenceState(mode, ThemePreferenceStatus.SAVING)
        operation =
            scope.launch {
                try {
                    store.write(mode, shouldReset)
                    synchronized(lock) {
                        if (!closed) {
                            pendingReset = false
                            mutable.value = ThemePreferenceState(mode, ThemePreferenceStatus.SAVED)
                        }
                    }
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    synchronized(lock) { if (!closed) mutable.value = ThemePreferenceState(mode, ThemePreferenceStatus.WRITE_FAILED) }
                }
            }
    }

    override fun close() {
        synchronized(lock) {
            closed = true
            operation?.cancel()
        }
    }
}
