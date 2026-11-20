package io.github.ts3mobile.app.appearance

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.ts3mobile.app.R
import io.github.ts3mobile.app.ui.SettingsPage
import io.github.ts3mobile.app.ui.theme.tailSyncColors

@Composable
internal fun AppearanceScreen(
    state: ThemePreferenceState,
    onSelect: (ThemeMode) -> Unit,
    onRetry: () -> Unit,
    onReset: () -> Unit,
    onClose: () -> Unit,
) {
    val failed = state.status == ThemePreferenceStatus.READ_FAILED || state.status == ThemePreferenceStatus.WRITE_FAILED
    SettingsPage(R.string.appearance_title, onBack = onClose) {
        Text(stringResource(R.string.appearance_mode_title), style = MaterialTheme.typography.titleMedium)
        Column(
            Modifier.fillMaxWidth().selectableGroup(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ThemeMode.entries.forEach { mode ->
                ThemeModeCard(mode, mode == state.mode, !state.busy && state.status != ThemePreferenceStatus.READ_FAILED) {
                    onSelect(mode)
                }
            }
        }
        if (failed) {
            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.errorContainer) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(state.status.label()), color = MaterialTheme.colorScheme.onErrorContainer)
                    OutlinedButton(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.action_retry_control))
                    }
                    OutlinedButton(onClick = onReset, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.appearance_reset))
                    }
                }
            }
        } else if (state.busy) {
            Text(
                stringResource(state.status.label()),
                Modifier.padding(horizontal = 4.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ThemeModeCard(
    mode: ThemeMode,
    selected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
) {
    val palette = MaterialTheme.colorScheme
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = if (selected) palette.primaryContainer else palette.surface,
        contentColor = if (selected) palette.onPrimaryContainer else palette.onSurface,
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) palette.primary else palette.outlineVariant),
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(
                min = 76.dp,
            ).selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ThemeSwatch(mode)
            Text(stringResource(mode.label()), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            RadioButton(selected = selected, onClick = null, enabled = enabled)
        }
    }
}

@Composable
private fun ThemeSwatch(mode: ThemeMode) {
    Surface(
        Modifier.size(width = 62.dp, height = 44.dp),
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Row {
            if (mode != ThemeMode.DARK) PalettePreview(tailSyncColors(false), Modifier.weight(1f))
            if (mode != ThemeMode.LIGHT) PalettePreview(tailSyncColors(true), Modifier.weight(1f))
        }
    }
}

@Composable
private fun PalettePreview(
    colors: ColorScheme,
    modifier: Modifier,
) {
    Column(modifier.background(colors.background).padding(5.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Box(Modifier.fillMaxWidth(0.55f).height(3.dp).clip(MaterialTheme.shapes.extraSmall).background(colors.onBackground))
        Box(Modifier.fillMaxWidth().weight(1f).clip(MaterialTheme.shapes.extraSmall).background(colors.surfaceContainerHighest))
        Box(Modifier.fillMaxWidth().height(5.dp).clip(MaterialTheme.shapes.extraSmall).background(colors.primary))
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
