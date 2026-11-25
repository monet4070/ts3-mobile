package io.github.ts3mobile.app.identity

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
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
    // Never save password inputs to the Activity instance state or retained controller.
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var showRestore by rememberSaveable { mutableStateOf(false) }
    val idle = !state.working && !state.pickerOutstanding && !restoreActive
    SettingsPage(R.string.identity_title, onBack = onClose) {
        CurrentIdentitySection(state.snapshot, useState, idle, onRefresh)
        if (restoreContent != null) {
            IdentityOperationSelector(showRestore, idle) { restore ->
                if (restore) {
                    password = ""
                    confirmation = ""
                }
                showRestore = restore
            }
        }
        if (showRestore && restoreContent != null) {
            restoreContent()
        } else {
            SettingsSection(stringResource(R.string.identity_encrypted_backup), UiIcon.Lock) {
                IdentityHint(R.string.identity_backup_guidance)
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
                ) { IdentityActionLabel(UiIcon.Download, R.string.identity_export) }
                if (state.status != IdentityExportStatus.READY) {
                    val verified = state.status == IdentityExportStatus.VERIFIED
                    IdentityStatusLine(state.status.label(), if (verified) UiIcon.Check else UiIcon.Info)
                    if (verified) state.exported?.let { IdentitySummaryView(it) }
                }
                if (state.working || state.pickerOutstanding) {
                    OutlinedButton(onClick = {
                        password = ""
                        confirmation = ""
                        onCancel()
                    }) { Text(stringResource(R.string.action_cancel)) }
                }
            }
        }
    }
}

@Composable
private fun CurrentIdentitySection(
    snapshot: IdentitySnapshot?,
    useState: IdentityUseState,
    idle: Boolean,
    onRefresh: () -> Unit,
) {
    SettingsSection(stringResource(R.string.identity_current), UiIcon.Shield) {
        when {
            snapshot == null -> IdentityHint(R.string.identity_loading)
            snapshot.unreadable -> IdentityStatusLine(R.string.identity_unreadable, UiIcon.Info, error = true)
            snapshot.active != null -> IdentitySummaryView(snapshot.active)
            else -> Text(stringResource(R.string.identity_empty), style = MaterialTheme.typography.titleSmall)
        }
        IdentityHint(R.string.identity_explanation)
        if (snapshot?.rollback != null) IdentityHint(R.string.identity_local_rollback)
        if (useState == IdentityUseState.CONNECTION) IdentityStatusLine(R.string.identity_disconnect_first, UiIcon.Info)
        OutlinedButton(onClick = onRefresh, enabled = idle) { IdentityActionLabel(UiIcon.Refresh, R.string.identity_refresh) }
    }
}

@Composable
private fun IdentityOperationSelector(
    showRestore: Boolean,
    enabled: Boolean,
    onSelect: (Boolean) -> Unit,
) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().guideAnchor(GuideAnchor.IDENTITY_OPERATIONS)) {
        val labels = listOf(R.string.identity_export_tab, R.string.identity_restore_tab)
        labels.forEachIndexed { index, label ->
            val restore = index == 1
            SegmentedButton(
                selected = showRestore == restore,
                onClick = { onSelect(restore) },
                enabled = enabled,
                shape = SegmentedButtonDefaults.itemShape(index, labels.size, MaterialTheme.shapes.medium),
                colors =
                    SegmentedButtonDefaults.colors(
                        activeContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        activeContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        inactiveContainerColor = MaterialTheme.colorScheme.surface,
                        activeBorderColor = MaterialTheme.colorScheme.outlineVariant,
                        inactiveBorderColor = MaterialTheme.colorScheme.outlineVariant,
                    ),
                icon = {},
            ) {
                Text(stringResource(label), textAlign = TextAlign.Center)
            }
        }
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
        IdentityHint(R.string.identity_security, summary.keyOffset, summary.securityLevel)
    }
}

@Composable
internal fun IdentityHint(
    label: Int,
    vararg arguments: Any,
) {
    Text(stringResource(label, *arguments), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
        Text(stringResource(label), style = MaterialTheme.typography.bodySmall, color = tint)
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
