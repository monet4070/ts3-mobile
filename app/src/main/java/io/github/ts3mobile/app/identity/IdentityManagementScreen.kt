package io.github.ts3mobile.app.identity

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.ts3mobile.app.R
import io.github.ts3mobile.app.ui.AppIcon
import io.github.ts3mobile.app.ui.GuideAnchor
import io.github.ts3mobile.app.ui.SettingsPage
import io.github.ts3mobile.app.ui.SettingsSection
import io.github.ts3mobile.app.ui.UiIcon
import io.github.ts3mobile.app.ui.connectionFieldColors
import io.github.ts3mobile.app.ui.guideAnchor
import io.github.ts3mobile.protocol.Ts3IdentitySummary

@Composable
internal fun IdentityManagementScreen(
    state: IdentityExportState,
    useState: IdentityUseState,
    onExport: (CharArray, CharArray) -> Unit,
    onRefresh: () -> Unit,
    onCancel: () -> Unit,
    onClose: () -> Unit,
    restoreActive: Boolean = false,
    restoreContent: (@Composable () -> Unit)? = null,
) {
    var creatingBackup by remember { mutableStateOf(false) }
    val idle = !state.working && !state.pickerOutstanding && !restoreActive
    val canExport = idle && useState == IdentityUseState.IDLE && state.snapshot?.active != null && !state.snapshot.unreadable
    SettingsPage(R.string.identity_title, onBack = onClose) {
        SettingsSection(
            stringResource(R.string.identity_operations),
            UiIcon.Lock,
            Modifier.guideAnchor(GuideAnchor.IDENTITY_OPERATIONS),
        ) {
            Button(onClick = { creatingBackup = true }, enabled = canExport, modifier = Modifier.fillMaxWidth()) {
                IdentityActionLabel(UiIcon.Download, R.string.identity_export)
            }
            val loading = state.status == IdentityExportStatus.LOADING
            if (!loading && state.status != IdentityExportStatus.READY) {
                val verified = state.status == IdentityExportStatus.VERIFIED
                IdentityStatusLine(state.status.label(), if (verified) UiIcon.Check else UiIcon.Info)
            }
            if (!loading && (state.working || state.pickerOutstanding)) {
                OutlinedButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
            }
            restoreContent?.invoke()
        }
        CurrentIdentitySection(state.snapshot, idle, onRefresh)
    }
    if (creatingBackup) {
        CreateBackupDialog(canExport, onDismiss = { creatingBackup = false }, onExport = { secret, repeated ->
            creatingBackup = false
            onExport(secret, repeated)
        })
    }
}

@Composable
private fun CreateBackupDialog(
    enabled: Boolean,
    onDismiss: () -> Unit,
    onExport: (CharArray, CharArray) -> Unit,
) {
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    val title = stringResource(R.string.identity_new_backup_password)
    val passwordLabel = stringResource(R.string.identity_password)
    val confirmationLabel = stringResource(R.string.identity_password_confirm)
    val saveLabel = stringResource(R.string.identity_choose_backup_location)
    val cancelLabel = stringResource(R.string.action_cancel)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                BackupPasswordField(password, passwordLabel, enabled) { password = it }
                BackupPasswordField(confirmation, confirmationLabel, enabled) { confirmation = it }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val secret = password.toCharArray()
                    val repeated = confirmation.toCharArray()
                    password = ""
                    confirmation = ""
                    onExport(secret, repeated)
                },
                enabled = enabled && password.isNotEmpty() && confirmation.isNotEmpty(),
            ) { Text(saveLabel) }
        },
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text(cancelLabel) } },
    )
}

@Composable
private fun CurrentIdentitySection(
    snapshot: IdentitySnapshot?,
    idle: Boolean,
    onRefresh: () -> Unit,
) {
    var showDetails by remember { mutableStateOf(false) }
    val summary = snapshot?.active
    SettingsSection(stringResource(R.string.identity_current), UiIcon.Shield) {
        when {
            snapshot == null && !idle -> IdentityStatusLine(R.string.identity_loading, UiIcon.Info)
            snapshot == null || snapshot.unreadable -> {
                IdentityStatusLine(R.string.identity_unreadable, UiIcon.Info, error = true)
                OutlinedButton(onClick = onRefresh, enabled = idle) { IdentityActionLabel(UiIcon.Refresh, R.string.identity_refresh) }
            }
            summary != null ->
                OutlinedButton(onClick = { showDetails = true }) {
                    Text(stringResource(R.string.identity_view_details))
                }
            else -> Text(stringResource(R.string.identity_empty), style = MaterialTheme.typography.titleSmall)
        }
    }
    if (showDetails && summary != null && !snapshot.unreadable) {
        AlertDialog(
            onDismissRequest = { showDetails = false },
            title = { Text(stringResource(R.string.identity_current)) },
            text = { IdentitySummaryView(summary) },
            confirmButton = { Button(onClick = { showDetails = false }) { Text(stringResource(R.string.action_close)) } },
        )
    }
}

@Composable
internal fun IdentitySummaryView(summary: Ts3IdentitySummary) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            stringResource(R.string.identity_uid, summary.uid),
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
        )
        Text(
            stringResource(R.string.identity_security, summary.keyOffset, summary.securityLevel),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
internal fun IdentityActionLabel(
    icon: UiIcon,
    label: Int,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AppIcon(icon, null, Modifier.size(16.dp))
        Text(stringResource(label))
    }
}

@Composable
internal fun IdentityStatusLine(
    label: Int,
    icon: UiIcon,
    error: Boolean = false,
) {
    val tint = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AppIcon(icon, null, Modifier.size(16.dp), tint)
        Text(stringResource(label), style = MaterialTheme.typography.titleSmall, color = tint)
    }
}

@Composable
internal fun BackupPasswordField(
    value: String,
    label: String,
    enabled: Boolean,
    onValue: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { if (it.length <= 2_048) onValue(it) },
        label = { Text(label) },
        enabled = enabled,
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Password),
        colors = connectionFieldColors(),
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun IdentityExportStatus.label(): Int =
    when (this) {
        IdentityExportStatus.READY -> R.string.identity_ready
        IdentityExportStatus.LOADING -> R.string.identity_loading
        IdentityExportStatus.PREPARING -> R.string.identity_preparing
        IdentityExportStatus.SELECTING -> R.string.identity_selecting
        IdentityExportStatus.WRITING -> R.string.identity_writing
        IdentityExportStatus.VERIFIED -> R.string.identity_verified
        IdentityExportStatus.UNVERIFIED -> R.string.identity_unverified
        IdentityExportStatus.CANCELLED -> R.string.identity_cancelled
        IdentityExportStatus.PICKER_UNAVAILABLE -> R.string.identity_picker_unavailable
        IdentityExportStatus.INVALID_PASSWORD -> R.string.identity_invalid_password
        IdentityExportStatus.PASSWORD_MISMATCH -> R.string.identity_password_mismatch
        IdentityExportStatus.CONNECTION_BUSY -> R.string.identity_disconnect_first
        IdentityExportStatus.IDENTITY_UNAVAILABLE -> R.string.identity_unreadable
        IdentityExportStatus.FAILED -> R.string.identity_failed
    }
