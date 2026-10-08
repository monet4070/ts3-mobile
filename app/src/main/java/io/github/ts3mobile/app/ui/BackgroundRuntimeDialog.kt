package io.github.ts3mobile.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.ts3mobile.app.R
import io.github.ts3mobile.app.service.BackgroundRuntimeState

@Composable
internal fun BackgroundRuntimeDialog(
    state: BackgroundRuntimeState,
    onKeepCpuAwakeChanged: (Boolean) -> Unit,
    onBatterySettings: () -> Unit,
    onCopyDiagnostics: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.background_runtime_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(stringResource(R.string.background_runtime_explanation))
                RuntimeStatus(R.string.background_battery_exempt, state.batteryOptimizationExempt)
                RuntimeStatus(R.string.background_power_save, state.powerSaveMode)
                RuntimeStatus(R.string.background_device_idle, state.deviceIdle)
                RuntimeStatus(R.string.background_cpu_lock, state.cpuLockHeld)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.background_keep_cpu_awake), modifier = Modifier.weight(1f))
                    Switch(checked = state.keepCpuAwake, onCheckedChange = onKeepCpuAwakeChanged)
                }
                Text(stringResource(R.string.background_keep_cpu_note), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = onBatterySettings) { Text(stringResource(R.string.background_open_battery_settings)) }
                TextButton(onClick = onCopyDiagnostics) { Text(stringResource(R.string.background_copy_diagnostics)) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
    )
}

@Composable
private fun RuntimeStatus(
    labelId: Int,
    active: Boolean,
) {
    Text(
        stringResource(
            R.string.background_status_value,
            stringResource(labelId),
            stringResource(if (active) R.string.background_yes else R.string.background_no),
        ),
        style = MaterialTheme.typography.bodySmall,
    )
}
