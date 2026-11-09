package io.github.ts3mobile.app.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import io.github.ts3mobile.app.chat.ChatConversationKey
import io.github.ts3mobile.app.chat.ChatState

/** Save only numeric conversation IDs and scroll positions, never identities or content. */
@Composable
internal fun ChatConversationContent(
    state: ChatState,
    holder: SaveableStateHolder,
    onDraft: (String) -> Unit,
    onSend: () -> Unit,
    onVisible: (ChatConversationKey, Long) -> Unit,
    onFocus: () -> Unit,
    onSelect: (ChatConversationKey) -> Unit,
    onClose: (ChatConversationKey) -> Unit,
) {
    holder.SaveableStateProvider(state.active.id) {
        var anchor by rememberSaveable { mutableStateOf<Long?>(null) }
        var offset by rememberSaveable { mutableStateOf(0) }
        val messages by rememberUpdatedState(state.messages)
        val list =
            remember {
                val index = anchor?.let { sequence -> messages.indexOfFirst { it.sequence >= sequence } } ?: 0
                val retainedIndex = if (index < 0) messages.lastIndex.coerceAtLeast(0) else index
                LazyListState(retainedIndex, if (messages.getOrNull(retainedIndex)?.sequence == anchor) offset else 0)
            }
        LaunchedEffect(list) {
            snapshotFlow {
                list.layoutInfo.visibleItemsInfo.firstOrNull()?.key to list.firstVisibleItemScrollOffset
            }.collect { (id, scrollOffset) ->
                messages.firstOrNull { it.id == id }?.let { message ->
                    anchor = message.sequence
                    offset = scrollOffset
                }
            }
        }
        ChatPanel(state, list, onDraft, onSend, { onVisible(state.selected, it) }, onFocus, onSelect, onClose)
    }
}
