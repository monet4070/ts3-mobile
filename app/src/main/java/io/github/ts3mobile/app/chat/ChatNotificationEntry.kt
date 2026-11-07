package io.github.ts3mobile.app.chat

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Main-thread owner, retained across Activity recreation; latest intent/user navigation wins. */
internal class ChatNotificationEntry {
    private var pending: String? = null
    private val mutable = MutableStateFlow<ChatNotificationDestination?>(null)
    val destination = mutable.asStateFlow()

    fun accept(token: String?) {
        cancel()
        pending = token?.takeIf { it.matches(Regex("[a-f0-9-]{36}")) }
    }

    fun resolve(consume: (String) -> ChatNotificationDestination?) {
        val token = pending ?: return
        pending = null
        mutable.value = consume(token)
    }

    fun consumed(requestId: String) {
        if (mutable.value?.requestId == requestId) mutable.value = null
    }

    fun cancel() {
        pending = null
        mutable.value = null
    }
}
