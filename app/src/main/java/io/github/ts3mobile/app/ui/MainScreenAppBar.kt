package io.github.ts3mobile.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.ts3mobile.app.R
import io.github.ts3mobile.protocol.ConnectionPhase

internal data class MainMenuAction(
    val label: Int,
    val icon: UiIcon,
    val enabled: Boolean = true,
    val startsGroup: Boolean = false,
    val action: () -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MainScreenAppBar(
    phase: ConnectionPhase,
    largeText: Boolean,
    onOpenMenu: () -> Unit,
    actions: List<MainMenuAction>,
) {
    var expanded by remember { mutableStateOf(false) }
    TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                IconTile(UiIcon.Audio, Modifier.size(32.dp))
                Text(
                    stringResource(R.string.app_name),
                    style = if (largeText) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        actions = {
            StatusIndicator(phase)
            Spacer(Modifier.width(4.dp))
            Box {
                IconButton(onClick = {
                    onOpenMenu()
                    expanded = true
                }) { AppIcon(UiIcon.More, stringResource(R.string.action_more)) }
                DropdownMenu(expanded, { expanded = false }, modifier = Modifier.widthIn(min = 240.dp, max = 320.dp)) {
                    actions.forEach { action ->
                        if (action.startsGroup) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        DropdownMenuItem(text = { Text(stringResource(action.label)) }, leadingIcon = {
                            AppIcon(action.icon, null)
                        }, enabled = action.enabled, onClick = {
                            expanded = false
                            action.action()
                        })
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
        },
    )
}
