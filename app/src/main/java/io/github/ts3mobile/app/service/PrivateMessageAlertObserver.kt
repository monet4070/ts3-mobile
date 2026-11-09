package io.github.ts3mobile.app.service

import io.github.ts3mobile.app.chat.ChatState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Fixed windows, bounded summaries and synchronous foreground barriers; no content in the sink. */
internal class PrivateMessageAlertObserver(
    private val scope: CoroutineScope,
    private val chat: StateFlow<ChatState>,
    private val registry: ChatNotificationRegistry,
    private val showAlert: (token: String, onlyAlertOnce: Boolean) -> Unit,
    private val clearAlert: () -> Unit,
    private val windowMs: Long = 2_000,
) : AutoCloseable {
    private val lock = Any()
    private var foreground = false
    private var closed = false
    private var session = chat.value.sessionToken to chat.value.notificationGeneration
    private var highWater = chat.value.privateMessageRevision
    private val pending = mutableSetOf<Long>()
    private val summarized = mutableSetOf<Long>()
    private var posted = false
    private var timer: Job? = null
    private val watch = scope.launch { chat.collect { synchronized(lock) { observe(chat.value) } } }

    private fun observe(
        state: ChatState,
        armWindow: Boolean = true,
    ) {
        if (closed) return
        val currentSession = state.sessionToken to state.notificationGeneration
        if (currentSession != session || !state.connected) {
            clear()
            registry.clear()
            session = currentSession
            highWater = 0
        }
        val previous = highWater
        highWater = state.privateMessageRevision
        if (foreground || !state.connected || state.sessionToken == null) return
        pending += state.conversations.filter { it.isPrivate && it.privateMessageRevision > previous }.map { it.id }
        val retainedIds = state.conversations.map { it.id }.toSet()
        pending.retainAll(retainedIds)
        summarized.retainAll(retainedIds)
        if (summarized.isEmpty() && posted) {
            clearAlert()
            posted = false
        }
        if (armWindow && pending.isNotEmpty() && timer == null) {
            val windowSession = session
            timer =
                scope.launch {
                    delay(windowMs)
                    synchronized(lock) {
                        timer = null
                        val current = chat.value
                        // A replacement session gets its own full window, even after conflation.
                        if (windowSession != current.sessionToken to current.notificationGeneration) {
                            observe(current)
                            return@synchronized
                        }
                        observe(current, armWindow = false)
                        if (!closed && !foreground && pending.isNotEmpty()) {
                            summarized += pending
                            pending.clear()
                            registry.issue(current, summarized)?.let { token ->
                                showAlert(token, posted)
                                posted = true
                            }
                        }
                    }
                }
        }
    }

    fun setAppVisible(visible: Boolean) {
        synchronized(lock) {
            if (closed) return
            // Events accepted before this visibility transition cannot replay after it.
            observe(chat.value)
            foreground = visible
            if (visible) clear()
        }
    }

    private fun clear() {
        timer?.cancel()
        timer = null
        pending.clear()
        summarized.clear()
        posted = false
        clearAlert()
    }

    override fun close() {
        synchronized(lock) {
            closed = true
            watch.cancel()
            clear()
            registry.clear()
        }
    }
}
