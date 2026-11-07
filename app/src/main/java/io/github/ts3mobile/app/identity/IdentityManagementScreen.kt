package io.github.ts3mobile.app.identity

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.ts3mobile.app.R
import io.github.ts3mobile.protocol.Ts3IdentitySummary

@Composable
internal fun IdentityManagementScreen(
    state: IdentityExportState,
    useState: IdentityUseState,
    onExport: (CharArray, CharArray) -> Unit,
    onRefresh: () -> Unit,
    onCancel: () -> Unit,
    onClose: () -> Unit,
) {
    // Never save password inputs to the Activity instance state or retained controller.
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    val idle = !state.working && !state.pickerOutstanding
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.windowInsetsPadding(WindowInsets.safeDrawing).imePadding().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.identity_title), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.identity_explanation))
            state.snapshot?.let { snapshot ->
                if (snapshot.unreadable) Text(stringResource(R.string.identity_unreadable), color = MaterialTheme.colorScheme.error)
                snapshot.active?.let { IdentitySummaryView(it) }
                if (snapshot.active == null && !snapshot.unreadable) Text(stringResource(R.string.identity_empty))
                if (snapshot.rollback != null) Text(stringResource(R.string.identity_local_rollback))
            }
            if (useState == IdentityUseState.CONNECTION) Text(stringResource(R.string.identity_disconnect_first))
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
                enabled = idle && useState == IdentityUseState.IDLE && state.snapshot?.active != null && state.snapshot.unreadable.not(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.identity_export)) }
            Text(stringResource(state.status.label()))
            if (state.status == IdentityExportStatus.VERIFIED) state.exported?.let { IdentitySummaryView(it) }
            if (state.working || state.pickerOutstanding) {
                TextButton(onClick = {
                    password = ""
                    confirmation = ""
                    onCancel()
                }) { Text(stringResource(R.string.action_cancel)) }
            }
            TextButton(onClick = onRefresh, enabled = idle) { Text(stringResource(R.string.identity_refresh)) }
            TextButton(onClick = onClose) { Text(stringResource(R.string.action_close)) }
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
private fun BackupPasswordField(
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
