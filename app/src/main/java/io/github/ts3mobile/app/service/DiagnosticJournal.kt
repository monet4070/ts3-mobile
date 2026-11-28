package io.github.ts3mobile.app.service

import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Closed categories prevent exception text, addresses or protocol payloads entering the journal. */
internal enum class DiagnosticEventKind {
    SERVICE_CREATED,
    SERVICE_DESTROYED,
    CONNECT_REQUESTED,
    CONNECTION_ATTEMPT,
    RECONNECT_ATTEMPT,
    USER_DISCONNECT,
    TASK_REMOVED,
    EXIT_STARTED,
    EXIT_REQUEST_SENT,
    EXIT_SERVER_CONFIRMED,
    EXIT_TIMED_OUT,
    EXIT_FAILED,
    EXIT_NO_ACTIVE_SESSION,
    EXIT_UNSUPPORTED,
    EXIT_CLEANUP_OVERRUN,
    EXIT_CLEANUP_FAILED,
    CLONES_RECOVERY_STARTED,
    CLONES_RECOVERY_ENDED,
    CONNECTING,
    CONNECTED,
    RECONNECTING,
    DISCONNECTED,
    ERROR,
    RETRYABLE_FAILURE,
    TERMINAL_FAILURE,
    TRANSPORT_DISCONNECTED,
    NETWORK_CHANGED,
    NETWORK_PENDING,
    NETWORK_UNAVAILABLE,
    NETWORK_BLOCKED,
    FOREGROUND_TYPES,
    CPU_LOCK_ACQUIRED,
    CPU_LOCK_RELEASED,
    POWER_STATE,
    WATCH_EXECUTION_GAP,
    PREVIOUS_PROCESS_EXIT,
    MICROPHONE_DEFERRED,
    MICROPHONE_FAILED,
    MICROPHONE_STOP_TIMEOUT,
    PLAYBACK_FAILED,
    HISTORY_WRITE_FAILED,
    NETWORK_HANDOFF_RETRY_DEFERRED,
}

internal data class DiagnosticEvent(
    val timeMs: Long,
    val kind: DiagnosticEventKind,
    val code: Long = 0L,
)

/** A small atomic, privacy-safe event journal that survives service and process recreation. */
internal class DiagnosticJournal(
    private val file: File,
    private val clockMs: () -> Long = System::currentTimeMillis,
    private val capacity: Int = 128,
    loadImmediately: Boolean = true,
) {
    private val events = ArrayDeque<DiagnosticEvent>()
    private val persistenceLock = Any()
    private var loaded = false
    var failedWrites: Long = 0L
        private set

    init {
        require(capacity in 1..128)
        if (loadImmediately) loadFromDisk()
    }

    /** Reads previously persisted events off the calling thread without dropping in-memory appends. */
    fun loadFromDisk() {
        synchronized(this) {
            if (loaded) return
        }
        synchronized(persistenceLock) {
            if (synchronized(this) { loaded }) return
            val diskEvents = mutableListOf<DiagnosticEvent>()
            var readFailed = false
            // Reject oversized/corrupt input rather than importing unknown text into diagnostics.
            if (file.isFile && file.length() <= MAX_FILE_BYTES) {
                try {
                    file.useLines { lines ->
                        lines.take(capacity).mapNotNull(::decode).forEach(diskEvents::add)
                    }
                } catch (_: IOException) {
                    readFailed = true
                } catch (_: SecurityException) {
                    readFailed = true
                }
            }
            synchronized(this) {
                if (loaded) return@synchronized
                if (readFailed) failedWrites++
                if (diskEvents.isNotEmpty()) {
                    val combined = ArrayDeque<DiagnosticEvent>(diskEvents.size + events.size)
                    diskEvents.forEach(combined::addLast)
                    events.forEach(combined::addLast)
                    while (combined.size > capacity) combined.removeFirst()
                    events.clear()
                    events.addAll(combined)
                }
                loaded = true
            }
        }
    }

    fun record(
        kind: DiagnosticEventKind,
        code: Long = 0L,
        timeMs: Long = clockMs(),
    ) {
        append(kind, code, timeMs)
        persist()
    }

    /** Callbacks append without waiting for disk; the service coalesces persistence requests. */
    @Synchronized
    fun append(
        kind: DiagnosticEventKind,
        code: Long = 0L,
        timeMs: Long = clockMs(),
    ) {
        events.addLast(DiagnosticEvent(timeMs.coerceAtLeast(0L), kind, code))
        while (events.size > capacity) events.removeFirst()
    }

    fun persist() {
        loadFromDisk()
        synchronized(persistenceLock) {
            val payload = synchronized(this) { events.joinToString("\n", transform = ::encode) }
            val temporary = File(file.parentFile, "${file.name}.tmp")
            try {
                file.parentFile?.mkdirs()
                temporary.writeText(payload, Charsets.UTF_8)
                try {
                    Files.move(
                        temporary.toPath(),
                        file.toPath(),
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
            } catch (_: IOException) {
                synchronized(this) { failedWrites++ }
            } catch (_: SecurityException) {
                synchronized(this) { failedWrites++ }
            }
        }
    }

    @Synchronized
    fun toRedactedJson(): String =
        buildString {
            append("{\"schema\":\"ts3-mobile-events/v1\",\"failedWrites\":")
            append(failedWrites)
            append(",\"events\":[")
            append(
                events.joinToString(",") {
                    "{\"timeMs\":${it.timeMs},\"kind\":\"${it.kind.name}\",\"code\":${it.code}}"
                },
            )
            append("]}")
        }

    private fun encode(event: DiagnosticEvent): String = "${event.timeMs}\t${event.kind.name}\t${event.code}"

    private fun decode(line: String): DiagnosticEvent? {
        val fields = line.split('\t')
        if (fields.size != 3) return null
        val time = fields[0].toLongOrNull()?.takeIf { it >= 0 } ?: return null
        val kind = DiagnosticEventKind.entries.firstOrNull { it.name == fields[1] } ?: return null
        val code = fields[2].toLongOrNull() ?: return null
        return DiagnosticEvent(time, kind, code)
    }

    companion object {
        private const val MAX_FILE_BYTES = 32_768L
    }
}
