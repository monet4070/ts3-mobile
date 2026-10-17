package io.github.ts3mobile.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.unit.dp
import io.github.ts3mobile.app.R
import io.github.ts3mobile.app.service.MicrophoneMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MicrophoneModeSelector(
    mode: MicrophoneMode,
    onMicrophoneModeChanged: (MicrophoneMode) -> Unit,
) {
    if (LocalDensity.current.fontScale <= 1.3f) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            MicrophoneMode.entries.forEachIndexed { index, option ->
                SegmentedButton(
                    colors =
                        SegmentedButtonDefaults.colors(
                            activeContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            activeContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            inactiveContainerColor = MaterialTheme.colorScheme.surface,
                            inactiveContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            activeBorderColor = MaterialTheme.colorScheme.outlineVariant,
                            inactiveBorderColor = MaterialTheme.colorScheme.outlineVariant,
                        ),
                    selected = mode == option,
                    onClick = { onMicrophoneModeChanged(option) },
                    shape =
                        SegmentedButtonDefaults.itemShape(
                            index = index,
                            count = MicrophoneMode.entries.size,
                            baseShape = RoundedCornerShape(8.dp),
                        ),
                ) {
                    Text(
                        microphoneModeLabel(option),
                    )
                }
            }
        }
    } else {
        var expanded by remember { mutableStateOf(false) }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.microphone_label), style = MaterialTheme.typography.labelLarge)
            Box {
                OutlinedButton(onClick = { expanded = true }, shape = MaterialTheme.shapes.small) {
                    AppIcon(UiIcon.Mic, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(microphoneModeLabel(mode))
                    Spacer(Modifier.width(8.dp))
                    AppIcon(UiIcon.ChevronDown, null, Modifier.size(16.dp))
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    MicrophoneMode.entries.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(microphoneModeLabel(option)) },
                            leadingIcon = { if (mode == option) AppIcon(UiIcon.Check, null) },
                            onClick = {
                                expanded = false
                                onMicrophoneModeChanged(option)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun microphoneModeLabel(mode: MicrophoneMode): String =
    stringResource(
        when (mode) {
            MicrophoneMode.OFF -> R.string.microphone_mode_off
            MicrophoneMode.PUSH_TO_TALK -> R.string.microphone_mode_push_to_talk
            MicrophoneMode.CONTINUOUS -> R.string.microphone_mode_continuous
        },
    )
