package io.github.ts3mobile.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.ts3mobile.app.ConnectionFormState
import io.github.ts3mobile.app.R
import io.github.ts3mobile.app.history.ServerHistoryEntry
import io.github.ts3mobile.protocol.ConnectionPhase

@Composable
internal fun ConnectionForm(
    form: ConnectionFormState,
    history: List<ServerHistoryEntry>,
    historyError: Boolean,
    phase: ConnectionPhase,
    onHostChanged: (String) -> Unit,
    onPortChanged: (String) -> Unit,
    onNicknameChanged: (String) -> Unit,
    onPasswordChanged: (String) -> Unit,
    onHistorySelected: (ServerHistoryEntry) -> Unit,
    onHistoryRemoved: (ServerHistoryEntry) -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val busy = phase == ConnectionPhase.CONNECTING || phase == ConnectionPhase.RECONNECTING || phase == ConnectionPhase.DISCONNECTING
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        Column(Modifier.widthIn(max = 520.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            IconTile(UiIcon.Radio, Modifier.size(48.dp))
            Spacer(Modifier.height(2.dp))
            Text(stringResource(R.string.connect_title), style = MaterialTheme.typography.headlineSmall)
            Text(
                stringResource(R.string.connect_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Panel(Modifier.widthIn(max = 520.dp).fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                SectionLabel(stringResource(R.string.section_server_details))
                ConnectionFields(form, !busy, onHostChanged, onPortChanged, onNicknameChanged, onPasswordChanged)
                Spacer(Modifier.height(2.dp))
                if (busy) {
                    OutlinedButton(
                        onClick = onDisconnect,
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        shape = MaterialTheme.shapes.small,
                        enabled = phase != ConnectionPhase.DISCONNECTING,
                    ) {
                        if (phase != ConnectionPhase.DISCONNECTING) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(10.dp))
                        }
                        Text(
                            stringResource(
                                when (phase) {
                                    ConnectionPhase.RECONNECTING -> R.string.action_cancel_reconnect
                                    ConnectionPhase.DISCONNECTING -> R.string.action_disconnecting
                                    else -> R.string.action_cancel_connect
                                },
                            ),
                        )
                    }
                } else {
                    Button(
                        onClick = onConnect,
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        shape = MaterialTheme.shapes.small,
                    ) {
                        AppIcon(UiIcon.Connect, null)
                        Spacer(Modifier.width(10.dp))
                        Text(stringResource(if (phase == ConnectionPhase.ERROR) R.string.action_reconnect else R.string.action_connect))
                        Spacer(Modifier.weight(1f))
                        AppIcon(UiIcon.ArrowRight, null, Modifier.size(18.dp))
                    }
                }
            }
        }
        if (history.isNotEmpty() || historyError) {
            ServerHistorySection(
                history = history,
                historyError = historyError,
                enabled = !busy,
                onSelected = onHistorySelected,
                onRemoved = onHistoryRemoved,
                modifier = Modifier.widthIn(max = 520.dp).fillMaxWidth(),
            )
        }
        Row(
            Modifier.widthIn(max = 520.dp).fillMaxWidth().padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppIcon(UiIcon.Shield, null, Modifier.size(16.dp), MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                stringResource(R.string.connect_privacy_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
