package io.github.ts3mobile.app.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.ts3mobile.app.R

@Composable
internal fun NavigationTabs(
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    val largeText = LocalDensity.current.fontScale > 1.3f
    val tabs =
        listOf(
            R.string.tab_channels to UiIcon.Channel,
            R.string.tab_participants to UiIcon.Users,
        )
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
            .then(if (largeText) Modifier.horizontalScroll(rememberScrollState()) else Modifier)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        tabs.forEachIndexed { index, (label, icon) ->
            Surface(
                modifier = if (largeText) Modifier.widthIn(min = 112.dp) else Modifier.weight(1f),
                shape = MaterialTheme.shapes.small,
                color = if (selected == index) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.background,
                contentColor =
                    if (selected == index) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
            ) {
                Column(
                    Modifier.selectable(selected == index, role = Role.Tab, onClick = {
                        onSelect(index)
                    }).heightIn(min = 54.dp).padding(horizontal = 4.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    AppIcon(icon, null, Modifier.size(18.dp))
                    Text(stringResource(label), style = MaterialTheme.typography.labelLarge, maxLines = 1, softWrap = false)
                }
            }
        }
    }
}
