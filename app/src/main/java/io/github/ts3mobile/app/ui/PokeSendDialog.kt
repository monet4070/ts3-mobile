package io.github.ts3mobile.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.ts3mobile.app.R
import io.github.ts3mobile.app.chat.ChatState
import io.github.ts3mobile.app.chat.PokeRecipient
import io.github.ts3mobile.protocol.MAX_POKE_INPUT_BYTES
import io.github.ts3mobile.protocol.MAX_TEXT_EVENT_BYTES
import io.github.ts3mobile.protocol.Ts3Participant
import io.github.ts3mobile.protocol.boundedText

@Composable
internal fun PokeSendDialog(
    recipient: PokeRecipient,
    chat: ChatState,
    participants: List<Ts3Participant>,
    onSend: (PokeRecipient, String) -> Boolean,
    onDismiss: () -> Unit,
) {
    var reason by remember(recipient) { mutableStateOf("") }
    var failed by remember(recipient) { mutableStateOf(false) }
    val tooLong = reason.toByteArray(Charsets.UTF_8).size > MAX_POKE_INPUT_BYTES
    val targetCurrent = recipient.isCurrent(chat, participants)
    val available = targetCurrent && !chat.sending
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.poke_send_title, recipient.nickname, recipient.target.clientId)) },
        text = {
            Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = reason,
                    onValueChange = { reason = it.boundedText(MAX_TEXT_EVENT_BYTES) },
                    label = { Text(stringResource(R.string.poke_reason)) },
                    modifier = Modifier.testTag("poke_reason"),
                    maxLines = 3,
                    isError = tooLong,
                    supportingText = { Text(stringResource(R.string.poke_limit, MAX_POKE_INPUT_BYTES)) },
                )
                val hint =
                    when {
                        !targetCurrent -> R.string.poke_unavailable
                        chat.sending -> R.string.chat_busy
                        failed && chat.conversationLimitReached -> R.string.chat_conversation_limit
                        failed -> R.string.poke_not_sent
                        else -> null
                    }
                hint?.let { Text(stringResource(it)) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = available && !tooLong,
                modifier = Modifier.testTag("poke_confirm"),
                onClick = { if (onSend(recipient, reason)) onDismiss() else failed = true },
            ) { Text(stringResource(R.string.poke_action)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
