package io.github.ts3mobile.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.ts3mobile.app.R
import io.github.ts3mobile.app.chat.ChatHistoryController
import io.github.ts3mobile.app.chat.ChatHistoryConversation
import io.github.ts3mobile.app.chat.ChatHistoryRecord
import io.github.ts3mobile.app.chat.ChatHistoryRoom
import io.github.ts3mobile.app.chat.ChatHistoryStatus
import io.github.ts3mobile.app.chat.ChatMessage
import io.github.ts3mobile.app.chat.ChatMessageKind
import io.github.ts3mobile.app.identity.IdentityReadVersion
import io.github.ts3mobile.app.identity.IdentitySnapshot
import io.github.ts3mobile.protocol.TextSendOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

private sealed interface HistoryRead {
    data object Loading : HistoryRead

    data object Failed : HistoryRead

    data object NoIdentity : HistoryRead

    data class Ready(val records: List<ChatHistoryRecord>) : HistoryRead
}

@Composable
internal fun ChatHistoryBrowser(
    history: ChatHistoryController,
    status: ChatHistoryStatus,
    identity: IdentityReadVersion,
    readIdentity: suspend () -> IdentitySnapshot,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    var result by remember(history, identity, status.generation, refresh) { mutableStateOf<HistoryRead>(HistoryRead.Loading) }
    var selected by remember(history, identity) { mutableStateOf<ChatHistoryConversation?>(null) }
    var deleting by remember(history, identity, status.generation) { mutableStateOf<ChatHistoryConversation?>(null) }
    LaunchedEffect(history, identity, status.generation, status.busy, refresh) {
        if (!status.busy && !identity.changing) {
            result =
                try {
                    val active = readIdentity()
                    val uid = active.active?.uid.takeUnless { active.unreadable }
                    if (uid == null) HistoryRead.NoIdentity else HistoryRead.Ready(history.read(uid))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    HistoryRead.Failed
                }
        }
    }
    val back = { if (selected != null) selected = null else onBack() }
    BackHandler(onBack = back)
    SettingsScaffold(R.string.chat_history_browse, back) { padding ->
        Box(
            Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding),
            contentAlignment = Alignment.TopCenter,
        ) {
            val records = (result as? HistoryRead.Ready)?.records.orEmpty()
            val grouped = remember(records) { records.asReversed().groupBy { it.conversation } }
            LazyColumn(
                Modifier.widthIn(max = 620.dp).fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val target = selected
                if (status.failed) item { FeatureNotice(UiIcon.Info, stringResource(R.string.chat_history_failed), error = true) }
                when (val read = result) {
                    HistoryRead.Loading -> item { FeatureNotice(UiIcon.Refresh, stringResource(R.string.chat_history_loading)) }
                    HistoryRead.Failed -> item { FeatureNotice(UiIcon.Info, stringResource(R.string.chat_history_failed), error = true) }
                    HistoryRead.NoIdentity -> item { FeatureNotice(UiIcon.Lock, stringResource(R.string.chat_history_identity_missing)) }
                    is HistoryRead.Ready ->
                        when {
                            target != null -> {
                                val messages = grouped[target].orEmpty()
                                item {
                                    SettingsSection(conversationLabel(target, messages), conversationIcon(target.room)) {
                                        Text(
                                            conversationServer(target),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                                if (messages.isNotEmpty()) {
                                    item {
                                        FeatureAction(
                                            UiIcon.Trash,
                                            stringResource(R.string.chat_history_delete_room),
                                            { deleting = target },
                                            enabled = !status.busy,
                                        )
                                    }
                                    val ordered = messages.asReversed()
                                    itemsIndexed(ordered) { index, record ->
                                        val locale = LocalConfiguration.current.locales[0]
                                        val dates = remember(locale) { DateFormat.getDateInstance(DateFormat.MEDIUM, locale) }
                                        val date = dates.format(Date(record.receivedAtMs))
                                        if (index == 0 || dates.format(Date(ordered[index - 1].receivedAtMs)) != date) {
                                            SectionLabel(date)
                                        }
                                        ChatMessageRow(record.asReadOnlyMessage(index.toLong() + 1), target.room is ChatHistoryRoom.Peer)
                                    }
                                } else {
                                    item { FeatureNotice(UiIcon.Archive, stringResource(R.string.chat_history_empty)) }
                                }
                            }
                            read.records.isEmpty() -> item { FeatureNotice(UiIcon.Archive, stringResource(R.string.chat_history_empty)) }
                            else ->
                                items(grouped.entries.toList()) { (conversation, messages) ->
                                    Panel(Modifier.fillMaxWidth()) {
                                        FeatureListRow(
                                            conversationIcon(conversation.room),
                                            conversationLabel(conversation, messages),
                                            { selected = conversation },
                                            detail = conversationServer(conversation),
                                        )
                                    }
                                }
                        }
                }
                item {
                    FeatureAction(
                        UiIcon.Refresh,
                        stringResource(R.string.chat_history_refresh),
                        { refresh++ },
                        enabled = !status.busy && !identity.changing,
                    )
                }
            }
        }
    }
    deleting?.let { conversation ->
        HistoryDeletionDialog(R.string.chat_history_delete_room, R.string.chat_history_delete_room_explanation, { deleting = null }) {
            deleting = null
            scope.launch { if (history.delete(conversation)) selected = null }
        }
    }
}

private fun conversationIcon(room: ChatHistoryRoom): UiIcon = if (room is ChatHistoryRoom.Channel) UiIcon.Channel else UiIcon.Chat

@Composable
private fun conversationLabel(
    conversation: ChatHistoryConversation,
    records: List<ChatHistoryRecord>,
): String =
    when (val room = conversation.room) {
        is ChatHistoryRoom.Channel -> stringResource(R.string.chat_history_channel, room.id)
        is ChatHistoryRoom.Peer -> {
            val display = records.firstNotNullOfOrNull { it.sender?.takeIf(String::isNotBlank) }
            val peer = room.uid.takeLast(8)
            stringResource(R.string.chat_history_peer, display?.let { "$it · $peer" } ?: peer)
        }
    }

@Composable
private fun conversationServer(conversation: ChatHistoryConversation): String =
    stringResource(R.string.chat_history_conversation_server, conversation.account.host, conversation.account.port)

private fun ChatHistoryRecord.asReadOnlyMessage(id: Long) =
    ChatMessage(
        id,
        id,
        receivedAtMs,
        if (outgoing) ChatMessageKind.OUTGOING else ChatMessageKind.INCOMING,
        (conversation.room as? ChatHistoryRoom.Channel)?.id,
        sender,
        body,
        TextSendOutcome.Accepted.takeIf { outgoing },
        truncated,
    )
