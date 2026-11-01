package io.github.ts3mobile.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.ts3mobile.app.R
import io.github.ts3mobile.app.chat.ChatMessage

@Composable
internal fun PokeNoticeDialog(
    notice: ChatMessage,
    onDismiss: (Long) -> Unit,
    onReleasePtt: () -> Unit,
) {
    LaunchedEffect(notice.id) { onReleasePtt() }
    AlertDialog(
        onDismissRequest = { onDismiss(notice.id) },
        title = { Text(stringResource(R.string.poke_received_title, notice.sender ?: stringResource(R.string.chat_unknown_sender))) },
        text = {
            Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                Text(notice.body.ifEmpty { stringResource(R.string.poke_empty_reason) })
                if (notice.truncated) Text(stringResource(R.string.chat_truncated))
            }
        },
        confirmButton = {
            TextButton(onClick = { onDismiss(notice.id) }) { Text(stringResource(R.string.action_close)) }
        },
    )
}
