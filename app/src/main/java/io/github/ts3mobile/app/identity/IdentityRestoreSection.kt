package io.github.ts3mobile.app.identity

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.ts3mobile.app.R
import io.github.ts3mobile.app.identity.backup.IdentityBackupFailure
import io.github.ts3mobile.app.identity.documents.IdentityImportFailure

@Composable
internal fun IdentityRestoreSection(
    state: IdentityRestoreState,
    useState: IdentityUseState,
    hasRollback: Boolean,
    otherBusy: Boolean,
    onImport: (CharArray) -> Unit,
    onRollback: () -> Unit,
    onConfirm: () -> Unit,
    onRefreshConfirmation: () -> Unit,
    onCancel: () -> Unit,
    onEndConnection: () -> Unit,
) {
    var passphrase by remember { mutableStateOf("") }
    val enabled = !otherBusy && !state.active
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(R.string.identity_import_guidance))
        BackupPasswordField(passphrase, R.string.identity_existing_password, enabled) { passphrase = it }
        Button(onClick = {
            val secret = passphrase.toCharArray()
            passphrase = ""
            onImport(secret)
        }, enabled = enabled) {
            Text(stringResource(R.string.identity_import))
        }
        TextButton(onClick = onRollback, enabled = enabled && hasRollback) { Text(stringResource(R.string.identity_rollback)) }
        if (useState == IdentityUseState.CONNECTION) {
            TextButton(onClick = onEndConnection) { Text(stringResource(R.string.identity_end_connection)) }
        }
        Text(
            stringResource(state.message()),
            color =
                if (state.status == IdentityRestoreStatus.FAILED) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
        )
        if (state.active && state.preview == null &&
            state.status !in setOf(IdentityRestoreStatus.COMMITTING, IdentityRestoreStatus.CANCELLING)
        ) {
            TextButton(onClick = {
                passphrase = ""
                onCancel()
            }) { Text(stringResource(R.string.action_cancel)) }
        }
    }
    state.preview?.let { preview ->
        val committing = state.status == IdentityRestoreStatus.COMMITTING
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
                    Text(stringResource(R.string.identity_current))
                    preview.previous.active?.let { IdentitySummaryView(it) } ?: Text(stringResource(R.string.identity_no_usable_current))
                    if (preview.previous.unreadable) Text(stringResource(R.string.identity_unreadable))
                    Text(stringResource(R.string.identity_proposed))
                    IdentitySummaryView(preview.proposed)
                    Text(stringResource(R.string.identity_permission_impact))
                    Text(stringResource(state.message()))
                    if (state.storeFailure in setOf(IdentityFailure.BUSY, IdentityFailure.STALE_CONFIRMATION)) {
                        TextButton(onClick = onRefreshConfirmation, enabled = !state.working) {
                            Text(stringResource(R.string.identity_refresh_confirmation))
                        }
                    }
                    if (useState == IdentityUseState.CONNECTION) {
                        TextButton(onClick = onEndConnection) { Text(stringResource(R.string.identity_end_connection)) }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = onConfirm,
                    enabled = state.status == IdentityRestoreStatus.PREVIEW && useState == IdentityUseState.IDLE,
                ) {
                    Text(stringResource(R.string.identity_confirm_action))
                }
            },
            dismissButton = {
                TextButton(onClick = onCancel, enabled = !committing) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
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
        return if (it == IdentityImportFailure.INVALID_IDENTITY) {
            R.string.identity_invalid_material
        } else {
            R.string.identity_document_unreadable
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
    return when (status) {
        IdentityRestoreStatus.READY -> R.string.identity_import_ready
        IdentityRestoreStatus.SELECTING -> R.string.identity_import_selecting
        IdentityRestoreStatus.READING -> R.string.identity_import_reading
        IdentityRestoreStatus.PREVIEW -> R.string.identity_confirm_guidance
        IdentityRestoreStatus.COMMITTING -> R.string.identity_committing
        IdentityRestoreStatus.DONE -> R.string.identity_replaced
        IdentityRestoreStatus.CANCELLING -> R.string.identity_cancelling
        IdentityRestoreStatus.CANCELLED -> R.string.identity_import_cancelled
        IdentityRestoreStatus.PICKER_UNAVAILABLE -> R.string.identity_picker_unavailable
        IdentityRestoreStatus.FAILED -> R.string.identity_store_failed
    }
}
