package io.github.ts3mobile.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.ts3mobile.app.R
import io.github.ts3mobile.app.service.TeamSpeakServiceState
import io.github.ts3mobile.protocol.ControlStatus
import io.github.ts3mobile.protocol.boundedText

@Composable
internal fun PresenceDialog(
    state: TeamSpeakServiceState,
    onAway: (Boolean, String) -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    var away by remember { mutableStateOf(state.presence.away) }
    var message by remember { mutableStateOf(state.presence.awayMessage) }
    InputDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.presence_title)) },
        text = {
            Column(
                Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(away, { away = it })
                    Text(stringResource(R.string.presence_away))
                }
                OutlinedTextField(
                    message,
                    { message = it.boundedText(256) },
                    label = { Text(stringResource(R.string.presence_message)) },
                    enabled = away,
                )
                Text(stringResource(R.string.presence_sync_hint))
                Text(
                    stringResource(
                        if (state.presence.pending) {
                            R.string.presence_pending
                        } else if (state.presence.outcome?.status == ControlStatus.ACCEPTED) {
                            R.string.presence_synced
                        } else {
                            R.string.presence_unconfirmed
                        },
                    ),
                )
                if (state.presence.outcome != null && state.presence.outcome.status != ControlStatus.ACCEPTED && !state.presence.pending) {
                    TextButton(onClick = onRetry) { Text(stringResource(R.string.action_retry_control)) }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onAway(away, message) }) { Text(stringResource(R.string.presence_apply)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
    )
}
