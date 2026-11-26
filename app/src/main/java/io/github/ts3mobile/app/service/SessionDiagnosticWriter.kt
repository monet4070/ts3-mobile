package io.github.ts3mobile.app.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

internal class SessionDiagnosticWriter(
    private val journal: DiagnosticJournal,
    scope: CoroutineScope,
    persist: () -> Unit = journal::persist,
) {
    private val requests = Channel<Unit>(Channel.CONFLATED)
    private val loaded = CompletableDeferred<Unit>()

    init {
        scope.launch {
            try {
                journal.loadFromDisk()
            } finally {
                loaded.complete(Unit)
            }
            for (request in requests) persist()
        }
    }

    fun append(
        kind: DiagnosticEventKind,
        code: Long,
        timeMs: Long,
    ) {
        journal.append(kind, code, timeMs)
        requests.trySend(Unit)
    }

    suspend fun awaitLoaded() = loaded.await()

    fun toRedactedJson(): String = journal.toRedactedJson()
}
