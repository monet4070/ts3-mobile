package io.github.ts3mobile.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.ts3mobile.app.ConnectionFormState
import io.github.ts3mobile.app.R
import io.github.ts3mobile.app.history.ServerHistoryEntry
import io.github.ts3mobile.protocol.ConnectionPhase

@OptIn(ExperimentalLayoutApi::class)
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
    statusText: String? = null,
    onOpenBookmarks: () -> Unit = {},
) {
    val busy = phase == ConnectionPhase.CONNECTING || phase == ConnectionPhase.RECONNECTING || phase == ConnectionPhase.DISCONNECTING
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        statusText?.let { StatusMessage(phase, it) }
        if (form.memoryError) StatusMessage(ConnectionPhase.ERROR, stringResource(R.string.connection_memory_failed))
        Text(
            stringResource(R.string.connect_title),
            modifier = Modifier.widthIn(max = 520.dp).fillMaxWidth().padding(vertical = 8.dp),
            style = MaterialTheme.typography.headlineSmall,
        )
        Panel(Modifier.widthIn(max = 520.dp).fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    SectionLabel(stringResource(R.string.section_server_details), Modifier.align(Alignment.CenterVertically))
                    TextButton(onClick = onOpenBookmarks, enabled = !busy) {
                        AppIcon(UiIcon.Bookmark, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.bookmark_open))
                    }
                }
                ConnectionFields(form, !busy, onHostChanged, onPortChanged, onNicknameChanged, onPasswordChanged)
                Spacer(Modifier.height(2.dp))
                if (busy) {
                    OutlinedButton(
                        onClick = onDisconnect,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
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
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
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
    }
}
