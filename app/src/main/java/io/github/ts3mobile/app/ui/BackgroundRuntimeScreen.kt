package io.github.ts3mobile.app.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.ts3mobile.app.R
import io.github.ts3mobile.app.service.BackgroundRuntimeState

@Composable
internal fun BackgroundRuntimeScreen(
    state: BackgroundRuntimeState,
    snackbar: SnackbarHostState,
    onKeepCpuAwakeChanged: (Boolean) -> Unit,
    onBatterySettings: () -> Unit,
    onAppSettings: () -> Unit,
    onCopyDiagnostics: () -> Unit,
    onBack: () -> Unit,
) {
    SettingsPage(R.string.background_runtime_title, onBack, snackbar) {
        Text(stringResource(R.string.background_runtime_explanation), color = MaterialTheme.colorScheme.onSurfaceVariant)
        SettingsSection(stringResource(R.string.background_system_status), UiIcon.Battery) {
            RuntimeStatus(R.string.background_battery_exempt, state.batteryOptimizationExempt)
            RuntimeStatus(R.string.background_power_save, state.powerSaveMode)
            RuntimeStatus(R.string.background_device_idle, state.deviceIdle)
            RuntimeStatus(R.string.background_cpu_lock, state.cpuLockHeld)
            Text(stringResource(R.string.background_cpu_lock_note), style = MaterialTheme.typography.bodySmall)
        }
        SettingsSection(stringResource(R.string.background_session_options), UiIcon.Audio) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.background_keep_cpu_awake), modifier = Modifier.weight(1f))
                Switch(checked = state.keepCpuAwake, onCheckedChange = onKeepCpuAwakeChanged)
            }
            Text(stringResource(R.string.background_keep_cpu_note), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.background_vendor_note), style = MaterialTheme.typography.bodySmall)
        }
        SettingsActionGroup(
            listOf(
                SettingsAction(R.string.background_open_battery_settings, UiIcon.Battery, onClick = onBatterySettings),
                SettingsAction(R.string.background_open_app_settings, UiIcon.Settings, onClick = onAppSettings),
                SettingsAction(R.string.background_copy_diagnostics, UiIcon.Copy, onClick = onCopyDiagnostics),
            ),
            R.string.background_tools,
        )
    }
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
        style = MaterialTheme.typography.bodyMedium,
    )
}
