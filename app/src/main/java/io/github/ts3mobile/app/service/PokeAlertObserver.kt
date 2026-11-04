package io.github.ts3mobile.app.service

import io.github.ts3mobile.app.chat.ChatState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** Only new background pokes alert; foreground and ended sessions clear the notification. */
internal class PokeAlertObserver(
    scope: CoroutineScope,
    private val chat: StateFlow<ChatState>,
    private val showAlert: () -> Unit,
    private val clearAlert: () -> Unit,
) : AutoCloseable {
    private val visible = MutableStateFlow(false)
    private val lock = Any()
    private var handled: Pair<Long, Long>? = null
    private var closed = false
    private val watch =
        scope.launch {
            combine(chat, visible) { state, foreground -> Triple(state.scope, state.pokeNotice?.id, foreground) }
                .distinctUntilChanged().collect {
                    synchronized(lock) {
                        if (closed) return@collect
                        val state = chat.value
                        val notice = state.pokeNotice
                        val key = state.scope?.let { session -> notice?.let { session to it.id } }
                        if (key == null || visible.value) clearAlert()
                        if (key != null && key != handled) {
                            handled = key
                            if (!visible.value) showAlert()
                        }
                    }
                }
        }

    fun setAppVisible(foreground: Boolean) {
        synchronized(lock) {
            visible.value = foreground
            if (foreground && !closed) clearAlert()
        }
    }

    override fun close() {
        synchronized(lock) {
            closed = true
            watch.cancel()
            clearAlert()
        }
    }
}
