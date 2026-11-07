package io.github.ts3mobile.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import io.github.ts3mobile.app.chat.ChatState

/** The owner stays composed on all main tabs; only opaque tokens and numeric UI state are saved. */
@Composable
internal fun ChatSessionState(
    chat: ChatState,
    content: @Composable (SaveableStateHolder) -> Unit,
) {
    val sessions = rememberSaveableStateHolder()
    val session = chat.sessionToken ?: "unbound:${chat.scope}"
    var previousSession by rememberSaveable { mutableStateOf(session) }
    LaunchedEffect(session) {
        if (previousSession != session) sessions.removeState(previousSession)
        previousSession = session
    }
    sessions.SaveableStateProvider(session) {
        val conversations = rememberSaveableStateHolder()
        var previousIds by rememberSaveable { mutableStateOf(longArrayOf()) }
        val ids = chat.conversations.map { it.id }.toSet()
        LaunchedEffect(ids) {
            (previousIds.toSet() - ids).forEach(conversations::removeState)
            previousIds = ids.toLongArray()
        }
        content(conversations)
    }
}
