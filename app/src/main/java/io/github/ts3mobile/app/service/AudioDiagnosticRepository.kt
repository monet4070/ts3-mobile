package io.github.ts3mobile.app.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException

/** Application-owned writer, so a retiring service cannot overwrite a replacement service's archive. */
internal class AudioDiagnosticRepository(
    file: File,
    scope: CoroutineScope,
    private val clockMs: () -> Long = System::currentTimeMillis,
) {
    private val storage = AudioDiagnosticStorage(file)
    private val archive = AudioDiagnosticArchive(storage.read())
    private val requests = Channel<Unit>(Channel.CONFLATED)
    private val mutableStatus = MutableStateFlow(archive.status())
    val status = mutableStatus.asStateFlow()

    init {
        archive.recoverInterrupted()
        scope.launch {
            for (request in requests) {
                delay(100) // Coalesce a route/focus burst without doing I/O on the producing thread.
                persist()
                delay(900) // Bound checkpoint fsyncs even during pathological output/route churn.
            }
        }
        changed()
    }

    fun begin(): Long = archive.begin(clockMs()).also { changed() }

    fun sample(value: AudioDiagnosticSample) {
        archive.sample(value)
        changed()
    }

    fun event(
        session: Long,
        kind: AudioDiagnosticEvent,
        value: Long = 0,
    ) {
        archive.event(AudioDiagnosticEntry(clockMs(), session, kind, value))
        changed()
    }

    fun end(
        session: Long,
        kind: AudioDiagnosticEvent = AudioDiagnosticEvent.STOPPED,
    ) {
        archive.end(session, clockMs(), kind)
        changed()
    }

    fun mark(): Boolean {
        val session = archive.state().sessions.lastOrNull()?.id ?: return false
        event(session, AudioDiagnosticEvent.USER_MARK)
        return true
    }

    fun export(): String = AudioDiagnosticFormat.json(archive.state(), clockMs())

    private fun changed() {
        mutableStatus.value = archive.status()
        requests.trySend(Unit)
    }

    private fun persist() {
        val checkpoint = archive.checkpoint()
        val now = clockMs()
        try {
            storage.write(checkpoint, now)
            archive.saved(checkpoint, now)
        } catch (_: IOException) {
            archive.failedWrite()
        } catch (_: SecurityException) {
            archive.failedWrite()
        }
        mutableStatus.value = archive.status()
    }
}
