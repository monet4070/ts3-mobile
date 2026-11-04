package io.github.ts3mobile.app.ui

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import io.github.ts3mobile.app.R
import io.github.ts3mobile.app.chat.ChatMessageKind
import io.github.ts3mobile.app.chat.ChatMessageType
import io.github.ts3mobile.app.chat.ChatState
import io.github.ts3mobile.protocol.TextSendOutcome

/** Completed sends get brief feedback without changing the active tab or conversation. */
@Composable
internal fun PokeFeedbackEffect(
    chat: ChatState,
    snackbar: SnackbarHostState,
) {
    val latest =
        chat.conversations.asSequence().flatMap { it.messages.asSequence() }
            .filter { it.type == ChatMessageType.POKE && it.kind == ChatMessageKind.OUTGOING }.maxByOrNull { it.sequence }
    var reported by remember(chat.scope) { mutableStateOf(latest?.takeIf { it.outcome != null }?.id) }
    val context = LocalContext.current
    LaunchedEffect(chat.scope, latest?.id, latest?.outcome) {
        val result = latest?.outcome ?: return@LaunchedEffect
        if (reported == latest.id) return@LaunchedEffect
        reported = latest.id
        val message =
            when (result) {
                TextSendOutcome.Accepted -> R.string.poke_sent
                TextSendOutcome.Unknown -> R.string.poke_result_unknown
                TextSendOutcome.Unsupported -> R.string.poke_unsupported
                is TextSendOutcome.Rejected -> R.string.poke_rejected
                is TextSendOutcome.NotSent -> R.string.poke_not_sent
            }
        snackbar.showSnackbar(context.getString(message))
    }
}
