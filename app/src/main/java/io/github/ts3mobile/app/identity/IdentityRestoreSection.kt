package io.github.ts3mobile.app.identity

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.ts3mobile.app.R
import io.github.ts3mobile.app.identity.backup.IdentityBackupFailure
import io.github.ts3mobile.app.identity.documents.IdentityImportFailure
import io.github.ts3mobile.app.ui.SettingsSection
import io.github.ts3mobile.app.ui.UiIcon

@Composable
internal fun IdentityRestoreSection(
    state: IdentityRestoreState,
    useState: IdentityUseState,
    hasRollback: Boolean,
    otherBusy: Boolean,
    onImport: (CharArray) -> Unit,
    onRollback: () -> Unit,
    onConfirm: (Boolean) -> Unit,
    onRefreshConfirmation: () -> Unit,
    onCancel: () -> Unit,
    onEndConnection: () -> Unit,
    onImportDesktop: (() -> Unit)? = null,
) {
    var restoringBackup by remember { mutableStateOf(false) }
    val enabled = !otherBusy && !state.active
    OutlinedButton(onClick = { restoringBackup = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
        IdentityActionLabel(UiIcon.Folder, R.string.identity_restore_backup)
    }
    if (onImportDesktop != null) {
        OutlinedButton(onClick = onImportDesktop, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
            IdentityActionLabel(UiIcon.FileText, R.string.identity_desktop_import)
        }
    }
    if (hasRollback) {
        OutlinedButton(onClick = onRollback, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
            IdentityActionLabel(UiIcon.History, R.string.identity_rollback)
        }
    }
    if (useState == IdentityUseState.CONNECTION && state.preview == null) {
        OutlinedButton(onClick = onEndConnection, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.identity_end_connection))
        }
    }
    if (state.status != IdentityRestoreStatus.READY && state.preview == null) {
        val complete = state.status in setOf(IdentityRestoreStatus.DONE, IdentityRestoreStatus.ALREADY_CURRENT)
        IdentityStatusLine(
            state.message(),
            if (complete) UiIcon.Check else UiIcon.Info,
            error = state.status == IdentityRestoreStatus.FAILED,
        )
        if (state.active && state.status !in setOf(IdentityRestoreStatus.COMMITTING, IdentityRestoreStatus.CANCELLING)) {
            OutlinedButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
        }
    }
    if (restoringBackup) {
        RestoreBackupDialog(enabled, onDismiss = { restoringBackup = false }, onImport = { secret ->
            restoringBackup = false
            onImport(secret)
        })
    }
    state.preview?.let { preview ->
        val committing = state.status == IdentityRestoreStatus.COMMITTING
        var allowLowerSecurity by remember(preview, state.status) { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { if (!committing) onCancel() },
            title = {
                Text(
                    stringResource(if (preview.rollback) R.string.identity_confirm_rollback else R.string.identity_confirm_replace),
                )
            },
            text = {
                Column(
                    Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    SettingsSection(stringResource(R.string.identity_current), UiIcon.Shield) {
                        preview.previous.active?.let { IdentitySummaryView(it) }
                            ?: Text(stringResource(R.string.identity_no_usable_current))
                        if (preview.previous.unreadable) IdentityStatusLine(R.string.identity_unreadable, UiIcon.Info, error = true)
                    }
                    SettingsSection(stringResource(R.string.identity_proposed), UiIcon.Archive) {
                        IdentitySummaryView(preview.proposed)
                    }
                    if (preview.previous.active?.uid != preview.proposed.uid) {
                        Text(stringResource(R.string.identity_permission_impact), style = MaterialTheme.typography.titleSmall)
                    }
                    if (preview.isDesktopDowngrade) {
                        Row(
                            Modifier.toggleable(
                                value = allowLowerSecurity,
                                enabled = state.status == IdentityRestoreStatus.PREVIEW,
                                role = Role.Checkbox,
                                onValueChange = { allowLowerSecurity = it },
                            ),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Checkbox(allowLowerSecurity, onCheckedChange = null)
                            Text(stringResource(R.string.identity_desktop_allow_lower))
                        }
                    }
                    if (state.status != IdentityRestoreStatus.PREVIEW && state.status != IdentityRestoreStatus.READY) {
                        IdentityStatusLine(state.message(), UiIcon.Info, error = state.status == IdentityRestoreStatus.FAILED)
                    }
                    if (state.storeFailure in setOf(IdentityFailure.BUSY, IdentityFailure.STALE_CONFIRMATION)) {
                        OutlinedButton(onClick = onRefreshConfirmation, enabled = !state.working) {
                            Text(stringResource(R.string.identity_refresh_confirmation))
                        }
                    }
                    if (useState == IdentityUseState.CONNECTION) {
                        OutlinedButton(onClick = onEndConnection) { Text(stringResource(R.string.identity_end_connection)) }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { onConfirm(allowLowerSecurity) },
                    enabled =
                        state.status == IdentityRestoreStatus.PREVIEW && useState == IdentityUseState.IDLE &&
                            (!preview.isDesktopDowngrade || allowLowerSecurity),
                ) {
                    Text(stringResource(R.string.identity_confirm_action))
                }
            },
            dismissButton = {
                OutlinedButton(onClick = onCancel, enabled = !committing) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

@Composable
private fun RestoreBackupDialog(
    enabled: Boolean,
    onDismiss: () -> Unit,
    onImport: (CharArray) -> Unit,
) {
    var passphrase by remember { mutableStateOf("") }
    val title = stringResource(R.string.identity_restore_backup)
    val passwordLabel = stringResource(R.string.identity_existing_password)
    val chooseLabel = stringResource(R.string.identity_import)
    val cancelLabel = stringResource(R.string.action_cancel)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { BackupPasswordField(passphrase, passwordLabel, enabled) { passphrase = it } },
        confirmButton = {
            Button(
                enabled = enabled && passphrase.isNotEmpty(),
                onClick = {
                    val secret = passphrase.toCharArray()
                    passphrase = ""
                    onImport(secret)
                },
            ) { Text(chooseLabel) }
        },
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text(cancelLabel) } },
    )
}

private fun IdentityRestoreState.message(): Int {
    backupFailure?.let {
        return when (it) {
            IdentityBackupFailure.AUTHENTICATION_FAILED -> R.string.identity_auth_failed
            IdentityBackupFailure.INVALID_PASSPHRASE -> R.string.identity_invalid_password
            IdentityBackupFailure.RESOURCE_LIMIT -> R.string.identity_resource_limit
            IdentityBackupFailure.UNSUPPORTED_VERSION, IdentityBackupFailure.UNSUPPORTED_ALGORITHM -> R.string.identity_unsupported_backup
            IdentityBackupFailure.INVALID_FORMAT -> R.string.identity_invalid_backup
            IdentityBackupFailure.CRYPTO_UNAVAILABLE -> R.string.identity_failed
        }
    }
    importFailure?.let {
        return when (it) {
            IdentityImportFailure.INVALID_IDENTITY ->
                if (source == IdentityImportSource.DESKTOP) R.string.identity_desktop_invalid else R.string.identity_invalid_material
            IdentityImportFailure.DOCUMENT_UNREADABLE -> R.string.identity_document_unreadable
            IdentityImportFailure.RESOURCE_LIMIT -> R.string.identity_desktop_size
        }
    }
    storeFailure?.let {
        return when (it) {
            IdentityFailure.BUSY -> R.string.identity_confirmation_busy
            IdentityFailure.STALE_CONFIRMATION -> R.string.identity_confirmation_stale
            IdentityFailure.NO_ROLLBACK -> R.string.identity_no_rollback
            IdentityFailure.INVALID_MATERIAL -> R.string.identity_invalid_material
            IdentityFailure.EMPTY, IdentityFailure.UNREADABLE -> R.string.identity_unreadable
            IdentityFailure.STORAGE_FAILED, IdentityFailure.VERIFICATION_FAILED -> R.string.identity_store_failed
        }
    }
    val desktop = source == IdentityImportSource.DESKTOP
    return when (status) {
        IdentityRestoreStatus.READY -> R.string.identity_import_ready
        IdentityRestoreStatus.SELECTING -> if (desktop) R.string.identity_desktop_selecting else R.string.identity_import_selecting
        IdentityRestoreStatus.READING -> if (desktop) R.string.identity_desktop_reading else R.string.identity_import_reading
        IdentityRestoreStatus.PREVIEW -> R.string.identity_confirm_guidance
        IdentityRestoreStatus.COMMITTING -> R.string.identity_committing
        IdentityRestoreStatus.DONE -> if (desktop) R.string.identity_desktop_done else R.string.identity_replaced
        IdentityRestoreStatus.ALREADY_CURRENT -> R.string.identity_desktop_already_current
        IdentityRestoreStatus.CANCELLING -> R.string.identity_cancelling
        IdentityRestoreStatus.CANCELLED -> R.string.identity_import_cancelled
        IdentityRestoreStatus.PICKER_UNAVAILABLE -> R.string.identity_picker_unavailable
        IdentityRestoreStatus.FAILED -> R.string.identity_store_failed
    }
}
