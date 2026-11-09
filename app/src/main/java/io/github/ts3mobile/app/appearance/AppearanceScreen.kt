package io.github.ts3mobile.app.appearance

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.ts3mobile.app.R

@Composable
internal fun AppearanceScreen(
    state: ThemePreferenceState,
    onSelect: (ThemeMode) -> Unit,
    onRetry: () -> Unit,
    onReset: () -> Unit,
    onClose: () -> Unit,
) {
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.windowInsetsPadding(WindowInsets.safeDrawing).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.appearance_title), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.appearance_explanation))
            Column(Modifier.selectableGroup()) {
                ThemeMode.entries.forEach { mode ->
                    Row(
                        Modifier.fillMaxWidth().selectable(
                            selected = state.mode == mode,
                            enabled = !state.busy && state.status != ThemePreferenceStatus.READ_FAILED,
                            role = Role.RadioButton,
                            onClick = { onSelect(mode) },
                        ).padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        RadioButton(
                            selected = state.mode == mode,
                            onClick = null,
                            enabled = !state.busy && state.status != ThemePreferenceStatus.READ_FAILED,
                        )
                        Text(stringResource(mode.label()))
                    }
                }
            }
            Text(stringResource(state.status.label()))
            if (state.status == ThemePreferenceStatus.READ_FAILED || state.status == ThemePreferenceStatus.WRITE_FAILED) {
                TextButton(onClick = onRetry) { Text(stringResource(R.string.action_retry_control)) }
                TextButton(onClick = onReset) { Text(stringResource(R.string.appearance_reset)) }
            }
            TextButton(onClick = onClose) { Text(stringResource(R.string.action_close)) }
        }
    }
}

private fun ThemeMode.label(): Int =
    when (this) {
        ThemeMode.LIGHT -> R.string.appearance_light
        ThemeMode.DARK -> R.string.appearance_dark
        ThemeMode.SYSTEM -> R.string.appearance_system
    }

private fun ThemePreferenceStatus.label(): Int =
    when (this) {
        ThemePreferenceStatus.LOADING -> R.string.appearance_loading
        ThemePreferenceStatus.SAVING -> R.string.appearance_saving
        ThemePreferenceStatus.SAVED -> R.string.appearance_saved
        ThemePreferenceStatus.READ_FAILED -> R.string.appearance_read_failed
        ThemePreferenceStatus.WRITE_FAILED -> R.string.appearance_write_failed
    }
