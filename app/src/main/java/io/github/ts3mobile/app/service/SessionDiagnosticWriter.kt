package io.github.ts3mobile.app.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

internal class SessionDiagnosticWriter(
    private val journal: DiagnosticJournal,
    scope: CoroutineScope,
    persist: () -> Unit = journal::persist,
) {
    private val requests = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch {
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

    fun toRedactedJson(): String = journal.toRedactedJson()
}
