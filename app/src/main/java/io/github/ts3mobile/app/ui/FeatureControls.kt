package io.github.ts3mobile.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable
internal fun FeatureTitle(
    icon: UiIcon,
    title: String,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        IconTile(icon)
        Text(title, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
    }
}

internal enum class FeatureActionStyle { PRIMARY, QUIET, DESTRUCTIVE }

@Composable
internal fun FeatureAction(
    icon: UiIcon,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    style: FeatureActionStyle = FeatureActionStyle.QUIET,
) {
    val target = modifier.heightIn(min = 48.dp)
    val shape = MaterialTheme.shapes.small
    val colors = MaterialTheme.colorScheme
    val padding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
    if (style == FeatureActionStyle.QUIET) {
        OutlinedButton(
            onClick,
            target,
            enabled,
            shape = shape,
            border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = if (enabled) 1f else 0.5f)),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.onSurfaceVariant),
            contentPadding = padding,
        ) { FeatureActionLabel(icon, label) }
    } else {
        Button(
            onClick,
            target,
            enabled,
            shape = shape,
            colors =
                if (style == FeatureActionStyle.DESTRUCTIVE) {
                    ButtonDefaults.buttonColors(colors.error, colors.onError)
                } else {
                    ButtonDefaults.buttonColors()
                },
            contentPadding = padding,
        ) { FeatureActionLabel(icon, label) }
    }
}

@Composable
private fun FeatureActionLabel(
    icon: UiIcon,
    label: String,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        AppIcon(icon, null, Modifier.size(16.dp))
        Text(label)
    }
}

@Composable
internal fun FeatureListRow(
    icon: UiIcon,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    trailing: UiIcon? = UiIcon.ChevronRight,
    detail: String? = null,
) {
    val colors = MaterialTheme.colorScheme
    val tint = if (enabled) colors.onSurface else colors.onSurface.copy(alpha = 0.48f)
    Row(
        modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(icon, null, Modifier.size(18.dp), if (enabled) colors.primary else tint)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = tint)
            if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
        trailing?.let { AppIcon(it, null, Modifier.size(16.dp), tint) }
    }
}

@Composable
internal fun FeatureNotice(
    icon: UiIcon,
    message: String,
    modifier: Modifier = Modifier,
    error: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        color = if (error) colors.errorContainer else colors.surfaceVariant,
        contentColor = if (error) colors.onErrorContainer else colors.onSurfaceVariant,
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Top,
        ) {
            AppIcon(icon, null, Modifier.size(16.dp))
            Text(message, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodySmall)
        }
    }
}
