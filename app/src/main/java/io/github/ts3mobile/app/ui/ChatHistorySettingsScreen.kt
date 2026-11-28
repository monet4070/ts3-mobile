package io.github.ts3mobile.app.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.ts3mobile.app.R
import io.github.ts3mobile.app.chat.ChatHistoryController
import io.github.ts3mobile.app.identity.IdentityReadVersion
import io.github.ts3mobile.app.identity.IdentitySnapshot
import kotlinx.coroutines.launch

private enum class HistoryAction { STOP, CLEAR }

@Composable
internal fun ChatHistorySettingsScreen(
    history: ChatHistoryController,
    identity: IdentityReadVersion,
    readIdentity: suspend () -> IdentitySnapshot,
    onBack: () -> Unit,
) {
    val status by history.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var browsing by remember { mutableStateOf(false) }
    var action by remember { mutableStateOf<HistoryAction?>(null) }
    if (browsing && status.enabled) {
        ChatHistoryBrowser(history, status, identity, readIdentity) { browsing = false }
        return
    }
    SettingsPage(R.string.chat_history_title, onBack) {
        val canToggle = status.ready && !status.busy
        SettingsSection(stringResource(R.string.chat_history_storage), UiIcon.Lock) {
            SettingsToggleRow(
                stringResource(R.string.chat_history_enable),
                status.enabled,
                onCheckedChange = {
                    if (it) scope.launch { history.setEnabled(true) } else action = HistoryAction.STOP
                },
                enabled = canToggle,
                modifier = Modifier.guideAnchor(GuideAnchor.HISTORY_TOGGLE),
            )
            Text(
                stringResource(R.string.chat_history_explanation),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (status.enabled) {
            SettingsActionGroup(
                listOf(
                    SettingsAction(
                        R.string.chat_history_browse,
                        UiIcon.Archive,
                        onClick = if (!status.busy) ({ browsing = true }) else null,
                    ),
                ),
            )
        }
        if (status.busy || !status.ready && !status.failed) FeatureNotice(UiIcon.Refresh, stringResource(R.string.chat_history_loading))
        if (status.failed) {
            FeatureNotice(UiIcon.Info, stringResource(R.string.chat_history_failed), error = true)
            FeatureAction(
                UiIcon.Refresh,
                stringResource(R.string.action_retry_control),
                { scope.launch { history.reload() } },
                enabled = !status.busy,
            )
        }
        if (status.dropped) FeatureNotice(UiIcon.Info, stringResource(R.string.chat_history_dropped), error = true)
        if (status.enabled || status.failed) {
            FeatureAction(
                UiIcon.Trash,
                stringResource(R.string.chat_history_clear),
                { action = HistoryAction.CLEAR },
                modifier = Modifier.fillMaxWidth(),
                enabled = !status.busy,
            )
        }
    }
    action?.let { confirmed ->
        val title =
            when (confirmed) {
                HistoryAction.STOP -> R.string.chat_history_turn_off
                HistoryAction.CLEAR -> R.string.chat_history_clear
            }
        HistoryDeletionDialog(title, R.string.chat_history_clear_explanation, { action = null }) {
            action = null
            scope.launch {
                when (confirmed) {
                    HistoryAction.STOP -> history.setEnabled(false)
                    HistoryAction.CLEAR -> history.clear()
                }
            }
        }
    }
}

@Composable
internal fun HistoryDeletionDialog(
    title: Int,
    explanation: Int,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    InputDialog(
        onDismissRequest = onDismiss,
        title = { FeatureTitle(UiIcon.Trash, stringResource(title)) },
        text = { FeatureNotice(UiIcon.Info, stringResource(explanation)) },
        confirmButton = {
            FeatureAction(
                UiIcon.Trash,
                stringResource(R.string.chat_history_delete_confirm),
                onConfirm,
                style = FeatureActionStyle.DESTRUCTIVE,
            )
        },
        dismissButton = { FeatureAction(UiIcon.Close, stringResource(R.string.action_cancel), onDismiss) },
    )
}
