package io.github.ts3mobile.app.ui

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import io.github.ts3mobile.app.chat.ChatConversationKey
import io.github.ts3mobile.app.chat.ChatState

/** Save only numeric conversation IDs and scroll positions, never identities or content. */
@Composable
internal fun ChatConversationContent(
    state: ChatState,
    onDraft: (String) -> Unit,
    onSend: () -> Unit,
    onVisible: (ChatConversationKey, Long) -> Unit,
    onFocus: () -> Unit,
    onSelect: (ChatConversationKey) -> Unit,
    onClose: (ChatConversationKey) -> Unit,
) {
    val holder = rememberSaveableStateHolder()
    var previousIds by rememberSaveable { mutableStateOf(longArrayOf()) }
    val ids = state.conversations.map { it.id }.toSet()
    LaunchedEffect(ids) {
        (previousIds.toSet() - ids).forEach(holder::removeState)
        previousIds = ids.toLongArray()
    }
    holder.SaveableStateProvider(state.active.id) {
        val list = rememberLazyListState()
        ChatPanel(state, list, onDraft, onSend, { onVisible(state.selected, it) }, onFocus, onSelect, onClose)
    }
}
