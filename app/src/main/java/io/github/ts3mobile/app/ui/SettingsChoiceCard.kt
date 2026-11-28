package io.github.ts3mobile.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

@Composable
internal fun SettingsChoiceCard(
    title: String,
    selected: Boolean,
    enabled: Boolean = true,
    preview: @Composable () -> Unit,
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
            Modifier.fillMaxWidth().heightIn(min = 76.dp)
                .selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            preview()
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            RadioButton(selected = selected, onClick = null, enabled = enabled)
        }
    }
}
