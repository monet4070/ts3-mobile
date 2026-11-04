package io.github.ts3mobile.app.ui

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.ts3mobile.app.R
import io.github.ts3mobile.app.chat.ChatState
import kotlinx.coroutines.launch

@Composable
internal fun ChatPanel(
    state: ChatState,
    list: LazyListState,
    onDraft: (String) -> Unit,
    onSend: () -> Unit,
    onVisible: (Long) -> Unit,
    onFocus: () -> Unit,
    onSelect: (io.github.ts3mobile.app.chat.ChatConversationKey) -> Unit,
    onClose: (io.github.ts3mobile.app.chat.ChatConversationKey) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var followTail by rememberSaveable {
        mutableStateOf(state.unread == 0 && list.firstVisibleItemIndex == 0 && list.firstVisibleItemScrollOffset == 0)
    }
    var lastHandledSequence by rememberSaveable { mutableStateOf<Long?>(null) }
    val currentState by rememberUpdatedState(state)
    val currentVisible by rememberUpdatedState(onVisible)
    LaunchedEffect(list) {
        var previousPosition = list.firstVisibleItemIndex to -list.firstVisibleItemScrollOffset
        snapshotFlow {
            val layout = list.layoutInfo
            val first = layout.visibleItemsInfo.firstOrNull()
            val last = layout.visibleItemsInfo.lastOrNull()
            // canScrollForward is initially false while a restored list is
            // awaiting measurement. Only measured geometry can prove the end.
            val atEnd =
                last != null && last.index == currentState.messages.lastIndex &&
                    last.offset + last.size <= layout.viewportEndOffset
            layout.visibleItemsInfo.mapNotNull { item ->
                currentState.messages.getOrNull(item.index)?.sequence
            }.maxOrNull() to Triple(first?.index ?: 0, first?.offset ?: 0, !atEnd)
        }.collect { (visible, viewport) ->
            if (visible == null) return@collect
            val position = viewport.first to viewport.second
            // Appending changes canScrollForward without a user scroll. Preserve
            // the prior follow intent until the viewport itself moves.
            if (position != previousPosition || !viewport.third) followTail = !viewport.third
            previousPosition = position
            currentVisible(visible)
        }
    }
    val followOnAppend = followTail
    LaunchedEffect(state.messages.lastOrNull()?.sequence) {
        val latest = state.messages.lastOrNull()?.sequence
        if (latest != lastHandledSequence && followOnAppend && state.messages.isNotEmpty()) {
            list.scrollToChatEnd(state.messages.lastIndex)
        }
        lastHandledSequence = latest
    }
    Column(Modifier.fillMaxSize()) {
        ChatConversationPicker(state, onSelect, onClose)
        if (state.conversationLimitReached) {
            Text(
                stringResource(R.string.chat_conversation_limit),
                style = MaterialTheme.typography.labelSmall,
            )
        }
        if (state.overflow) Text(stringResource(R.string.chat_overflow), style = MaterialTheme.typography.labelSmall)
        if (state.invalidEvent) Text(stringResource(R.string.chat_invalid_event), style = MaterialTheme.typography.labelSmall)
        LazyColumn(Modifier.weight(1f).testTag("chat_messages"), state = list) {
            if (state.messages.isEmpty()) item { Text(stringResource(R.string.chat_empty), Modifier.padding(16.dp)) }
            items(state.messages, key = { it.id }) { ChatMessageRow(it, state.active.isPrivate) }
        }
        if (state.unread > 0 && !followTail) {
            TextButton(onClick = {
                scope.launch { if (state.messages.isNotEmpty()) list.scrollToChatEnd(state.messages.lastIndex) }
            }, modifier = Modifier.testTag("chat_new_messages")) {
                Text(pluralStringResource(R.plurals.chat_new_messages, state.unread, state.unread))
            }
        }
        ChatComposer(state, onDraft, onSend, onFocus)
    }
}

private suspend fun LazyListState.scrollToChatEnd(lastIndex: Int) {
    scrollToItem(lastIndex)
    // Also reveal the end of a last message taller than the available viewport.
    layoutInfo.visibleItemsInfo.lastOrNull()?.let { scrollBy(it.size.toFloat()) }
}
