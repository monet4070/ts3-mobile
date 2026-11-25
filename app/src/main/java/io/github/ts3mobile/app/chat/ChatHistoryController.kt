package io.github.ts3mobile.app.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal data class ChatHistoryStatus(
    val ready: Boolean = false,
    val enabled: Boolean = false,
    val busy: Boolean = false,
    val failed: Boolean = false,
    val dropped: Boolean = false,
    val generation: Long = 0,
)

internal class ChatHistoryController(
    scope: CoroutineScope,
    private val store: ChatHistoryStore,
) {
    private data class Pending(val ticket: Long, val record: ChatHistoryRecord)

    private val operations = Mutex()
    private val queue = Channel<Pending>(64)
    private val status = MutableStateFlow(ChatHistoryStatus())
    val state = status.asStateFlow()

    init {
        scope.launch {
            reload()
            for (first in queue) {
                delay(60)
                val batch = mutableListOf(first)
                while (batch.size < 32) batch += queue.tryReceive().getOrNull() ?: break
                flush(batch)
            }
        }
    }

    fun ticket(): Long? =
        status.value.let { snapshot ->
            snapshot.generation.takeIf { snapshot.ready && snapshot.enabled && !snapshot.busy && !snapshot.failed }
        }

    fun offer(
        ticket: Long,
        record: ChatHistoryRecord,
    ) {
        if (ticket != ticket()) return
        if (queue.trySend(Pending(ticket, record)).isFailure) {
            status.update { if (it.generation == ticket) it.copy(dropped = true) else it }
        }
    }

    suspend fun reload(): Boolean = change { store.enabled() }

    suspend fun setEnabled(enabled: Boolean): Boolean =
        change {
            store.setEnabled(enabled)
            enabled
        }

    suspend fun read(ownUid: String): List<ChatHistoryRecord> =
        operations.withLock {
            if (!status.value.enabled) emptyList() else store.read(ownUid)
        }

    suspend fun delete(conversation: ChatHistoryConversation): Boolean =
        change {
            store.delete(conversation)
            store.enabled()
        }

    suspend fun clear(): Boolean =
        change {
            store.clear()
            store.enabled()
        }

    private suspend fun flush(batch: List<Pending>) =
        operations.withLock {
            val current = ticket() ?: return@withLock
            val records = batch.filter { it.ticket == current }.map { it.record }
            if (records.isEmpty()) return@withLock
            try {
                store.append(records)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                status.update { it.copy(failed = true) }
            }
        }

    private suspend fun change(operation: suspend () -> Boolean): Boolean =
        operations.withLock {
            withContext(NonCancellable) {
                val current = status.value
                val editing = current.copy(busy = true, generation = current.generation + 1)
                status.value = editing
                try {
                    val enabled = operation()
                    status.value = ChatHistoryStatus(ready = true, enabled = enabled, generation = editing.generation)
                    true
                } catch (cancelled: CancellationException) {
                    status.value = editing.copy(busy = false, failed = true)
                    throw cancelled
                } catch (_: Exception) {
                    status.value = editing.copy(busy = false, failed = true)
                    false
                }
            }
        }
}
