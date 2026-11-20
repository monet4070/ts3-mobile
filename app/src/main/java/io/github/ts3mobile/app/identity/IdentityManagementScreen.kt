package io.github.ts3mobile.app.identity

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.ts3mobile.app.R
import io.github.ts3mobile.app.ui.SettingsPage
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
    // Never save password inputs to the Activity instance state or retained controller.
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var showRestore by rememberSaveable { mutableStateOf(false) }
    val idle = !state.working && !state.pickerOutstanding && !restoreActive
    SettingsPage(R.string.identity_title, onBack = onClose) {
        Text(stringResource(R.string.identity_explanation))
        state.snapshot?.let { snapshot ->
            if (snapshot.unreadable) Text(stringResource(R.string.identity_unreadable), color = MaterialTheme.colorScheme.error)
            snapshot.active?.let { IdentitySummaryView(it) }
            if (snapshot.active == null && !snapshot.unreadable) Text(stringResource(R.string.identity_empty))
            if (snapshot.rollback != null) Text(stringResource(R.string.identity_local_rollback))
        }
        if (useState == IdentityUseState.CONNECTION) Text(stringResource(R.string.identity_disconnect_first))
        if (restoreContent != null) {
            androidx.compose.foundation.layout.Row {
                OutlinedButton(onClick = { showRestore = false }, enabled = idle) { Text(stringResource(R.string.identity_export_tab)) }
                OutlinedButton(onClick = {
                    password = ""
                    confirmation = ""
                    showRestore = true
                }, enabled = idle) {
                    Text(stringResource(R.string.identity_restore_tab))
                }
            }
        }
        if (showRestore && restoreContent != null) {
            restoreContent()
        } else {
            Text(stringResource(R.string.identity_backup_guidance))
            BackupPasswordField(password, R.string.identity_password, idle) { password = it }
            BackupPasswordField(confirmation, R.string.identity_password_confirm, idle) { confirmation = it }
            Button(
                onClick = {
                    val secret = password.toCharArray()
                    val repeated = confirmation.toCharArray()
                    password = ""
                    confirmation = ""
                    onExport(secret, repeated)
                },
                enabled =
                    idle && useState == IdentityUseState.IDLE &&
                        state.snapshot?.active != null && state.snapshot.unreadable.not(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.identity_export)) }
            if (state.status != IdentityExportStatus.READY) Text(stringResource(state.status.label()))
            if (state.status == IdentityExportStatus.VERIFIED) state.exported?.let { IdentitySummaryView(it) }
            if (state.working || state.pickerOutstanding) {
                OutlinedButton(onClick = {
                    password = ""
                    confirmation = ""
                    onCancel()
                }) { Text(stringResource(R.string.action_cancel)) }
            }
            OutlinedButton(onClick = onRefresh, enabled = idle) { Text(stringResource(R.string.identity_refresh)) }
        }
    }
}

@Composable
internal fun IdentitySummaryView(summary: Ts3IdentitySummary) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.identity_uid, summary.uid), style = MaterialTheme.typography.bodyMedium)
        Text(stringResource(R.string.identity_security, summary.keyOffset, summary.securityLevel))
    }
}

@Composable
internal fun BackupPasswordField(
    value: String,
    label: Int,
    enabled: Boolean,
    onValue: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { if (it.length <= 2_048) onValue(it) },
        label = { Text(stringResource(label)) },
        enabled = enabled,
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Password),
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
