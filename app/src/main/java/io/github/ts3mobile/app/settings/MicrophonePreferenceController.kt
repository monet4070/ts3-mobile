package io.github.ts3mobile.app.settings

import io.github.ts3mobile.audio.opus.MicrophoneSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Field edits apply synchronously; one writer owns coalesced persistence and durable revisions. */
internal class MicrophonePreferenceController(
    private val scope: CoroutineScope,
    private val store: MicrophonePreferenceStore,
    private val applySettings: (MicrophoneSettings) -> Unit,
) : AutoCloseable {
    private val lock = Any()
    private val mutable = MutableStateFlow(MicrophonePreferenceState())
    val state = mutable.asStateFlow()
    private val edited = mutableSetOf<MicrophonePreferenceField>()
    private var revision = 0L
    private var durableRevision = 0L
    private var readGeneration = 0L
    private var canWrite = false
    private var resetRevision: Long? = null
    private var closed = false
    private var reader: Job? = null
    private val requests = Channel<Boolean>(Channel.CONFLATED)
    private val writer =
        scope.launch {
            for (request in requests) {
                var immediate = request
                while (!immediate) immediate = withTimeoutOrNull(150L) { requests.receive() } ?: break
                saveLatest()
            }
        }

    init {
        load()
    }

    fun edit(
        field: MicrophonePreferenceField,
        requested: MicrophoneSettings,
    ) {
        synchronized(lock) {
            if (closed) return
            edited += field
            revision++
            publish(
                field.applyTo(mutable.value.settings, requested),
                if (canWrite) MicrophonePreferenceStatus.PENDING else mutable.value.status,
            )
            requests.trySend(field != MicrophonePreferenceField.VOLUME && field != MicrophonePreferenceField.GATE_THRESHOLD)
        }
    }

    fun commit() {
        requests.trySend(true)
    }

    /** Compatibility for callers deliberately replacing all four preferences at once. */
    fun replace(settings: MicrophoneSettings) {
        synchronized(lock) {
            if (closed) return
            edited += MicrophonePreferenceField.entries
            revision++
            publish(settings, if (canWrite) MicrophonePreferenceStatus.PENDING else mutable.value.status)
            requests.trySend(true)
        }
    }

    fun retry() {
        if (state.value.status == MicrophonePreferenceStatus.READ_FAILED) load() else commit()
    }

    fun reset() {
        synchronized(lock) {
            if (closed) return
            readGeneration++
            reader?.cancel()
            edited += MicrophonePreferenceField.entries
            revision++
            resetRevision = revision
            canWrite = true
            publish(MicrophoneSettings(), MicrophonePreferenceStatus.PENDING)
            requests.trySend(true)
        }
    }

    private fun load() {
        val generation =
            synchronized(lock) {
                if (closed || reader?.isActive == true) return
                mutable.value = mutable.value.copy(status = MicrophonePreferenceStatus.LOADING)
                ++readGeneration
            }
        reader =
            scope.launch {
                try {
                    val stored = store.read()
                    synchronized(lock) {
                        if (closed || generation != readGeneration) return@launch
                        val merged = edited.fold(stored) { settings, field -> field.applyTo(settings, mutable.value.settings) }
                        canWrite = true
                        val dirty = revision > durableRevision
                        publish(merged, if (dirty) MicrophonePreferenceStatus.PENDING else MicrophonePreferenceStatus.SAVED)
                        if (dirty) requests.trySend(true)
                    }
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    synchronized(lock) {
                        if (!closed && generation == readGeneration) {
                            mutable.value = mutable.value.copy(status = MicrophonePreferenceStatus.READ_FAILED)
                        }
                    }
                }
            }
    }

    private suspend fun saveLatest() {
        val pending =
            synchronized(lock) {
                if (closed || !canWrite || revision == durableRevision) return
                mutable.value = mutable.value.copy(status = MicrophonePreferenceStatus.SAVING)
                Pending(mutable.value.settings, revision, resetRevision != null)
            }
        try {
            store.write(pending.settings, pending.reset)
            synchronized(lock) {
                if (closed) return
                durableRevision = pending.revision
                if (pending.reset && (resetRevision ?: Long.MAX_VALUE) <= pending.revision) resetRevision = null
                mutable.value =
                    mutable.value.copy(
                        status = if (revision == durableRevision) MicrophonePreferenceStatus.SAVED else MicrophonePreferenceStatus.PENDING,
                    )
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            synchronized(lock) { if (!closed) mutable.value = mutable.value.copy(status = MicrophonePreferenceStatus.WRITE_FAILED) }
        }
    }

    private fun publish(
        settings: MicrophoneSettings,
        status: MicrophonePreferenceStatus,
    ) {
        if (mutable.value.settings != settings) applySettings(settings)
        mutable.value = MicrophonePreferenceState(settings, status)
    }

    override fun close() {
        synchronized(lock) {
            closed = true
            reader?.cancel()
            writer.cancel()
            requests.close()
        }
    }

    private data class Pending(val settings: MicrophoneSettings, val revision: Long, val reset: Boolean)
}
